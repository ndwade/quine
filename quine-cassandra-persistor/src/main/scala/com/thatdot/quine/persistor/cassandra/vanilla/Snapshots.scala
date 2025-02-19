package com.thatdot.quine.persistor.cassandra.vanilla

import scala.compat.ExecutionContexts
import scala.compat.java8.DurationConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import cats.Monad
import cats.implicits._
import com.datastax.oss.driver.api.core.cql.{PreparedStatement, SimpleStatement}
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder.DESC
import com.datastax.oss.driver.api.core.{ConsistencyLevel, CqlSession}
import com.datastax.oss.driver.api.querybuilder.select.Select

import com.thatdot.quine.graph.EventTime
import com.thatdot.quine.model.QuineId

trait SnapshotsColumnNames {
  import CassandraCodecs._
  final protected val quineIdColumn: CassandraColumn[QuineId] = CassandraColumn[QuineId]("quine_id")
  final protected val timestampColumn: CassandraColumn[EventTime] = CassandraColumn[EventTime]("timestamp")
  final protected val dataColumn: CassandraColumn[Array[Byte]] = CassandraColumn[Array[Byte]]("data")
  final protected val multipartIndexColumn: CassandraColumn[Int] = CassandraColumn[Int]("multipart_index")
  final protected val multipartCountColumn: CassandraColumn[Int] = CassandraColumn[Int]("multipart_count")
}

object Snapshots extends TableDefinition with SnapshotsColumnNames {
  protected val tableName = "snapshots"
  protected val partitionKey = quineIdColumn
  protected val clusterKeys: List[CassandraColumn[_]] = List(timestampColumn, multipartIndexColumn)
  protected val dataColumns: List[CassandraColumn[_]] = List(dataColumn, multipartCountColumn)

  private val createTableStatement: SimpleStatement =
    makeCreateTableStatement.withClusteringOrder(timestampColumn.name, DESC).build.setTimeout(createTableTimeout)

  private val getLatestTime: Select =
    select
      .columns(timestampColumn.name)
      .where(quineIdColumn.is.eq)
      .limit(1)

  private val getLatestTimeBefore: SimpleStatement =
    getLatestTime
      .where(timestampColumn.is.lte)
      .build()

  private val getParts: SimpleStatement = select
    .columns(dataColumn.name, multipartIndexColumn.name, multipartCountColumn.name)
    .where(quineIdColumn.is.eq)
    .where(timestampColumn.is.eq)
    .build()

  private val selectAllQuineIds: SimpleStatement = select.distinct
    .column(quineIdColumn.name)
    .build()

  def create(
    session: CqlSession,
    readConsistency: ConsistencyLevel,
    writeConsistency: ConsistencyLevel,
    insertTimeout: FiniteDuration,
    selectTimeout: FiniteDuration,
    shouldCreateTables: Boolean
  )(implicit
    futureMonad: Monad[Future]
  ): Future[Snapshots] = {
    logger.debug("Preparing statements for {}", tableName)

    def prepare(statement: SimpleStatement): Future[PreparedStatement] = {
      logger.trace("Preparing {}", statement.getQuery)
      session.prepareAsync(statement).toScala
    }

    val createdSchema =
      if (shouldCreateTables)
        session.executeAsync(createTableStatement).toScala
      else
        Future.unit

    createdSchema.flatMap(_ =>
      (
        prepare(insertStatement.setTimeout(insertTimeout.toJava).setConsistencyLevel(writeConsistency)),
        prepare(getLatestTime.build().setTimeout(selectTimeout.toJava).setConsistencyLevel(readConsistency)),
        prepare(getLatestTimeBefore.setTimeout(selectTimeout.toJava).setConsistencyLevel(readConsistency)),
        prepare(getParts.setTimeout(selectTimeout.toJava).setConsistencyLevel(readConsistency)),
        prepare(selectAllQuineIds.setTimeout(selectTimeout.toJava).setConsistencyLevel(readConsistency))
      ).mapN(new Snapshots(session, _, _, _, _, _))
    )(ExecutionContexts.parasitic)
  }

}

class Snapshots(
  session: CqlSession,
  insertStatement: PreparedStatement,
  getLatestTimeStatement: PreparedStatement,
  getLatestTimeBeforeStatement: PreparedStatement,
  getPartsStatement: PreparedStatement,
  selectAllQuineIds: PreparedStatement
) extends CassandraTable(session)
    with SnapshotsColumnNames {
  import syntax._

  def nonEmpty(): Future[Boolean] = yieldsResults(Snapshots.arbitraryRowStatement)

  def persistSnapshotPart(
    id: QuineId,
    atTime: EventTime,
    part: Array[Byte],
    partIndex: Int,
    partCount: Int
  ): Future[Unit] = executeFuture(
    insertStatement.bindColumns(
      quineIdColumn.set(id),
      timestampColumn.set(atTime),
      dataColumn.set(part),
      multipartIndexColumn.set(partIndex),
      multipartCountColumn.set(partCount)
    )
  )

  def getLatestSnapshotTime(
    id: QuineId,
    upToTime: EventTime
  ): Future[Option[EventTime]] =
    session
      .executeAsync(upToTime match {
        case EventTime.MaxValue =>
          getLatestTimeStatement.bindColumns(quineIdColumn.set(id))
        case _ =>
          getLatestTimeBeforeStatement.bindColumns(
            quineIdColumn.set(id),
            timestampColumn.setLt(upToTime)
          )
      })
      .toScala
      .map(results => Option(results.one).map(timestampColumn.get))(ExecutionContexts.parasitic)

  def getSnapshotParts(
    id: QuineId,
    atTime: EventTime
  )(implicit mat: Materializer): Future[Seq[(Array[Byte], Int, Int)]] =
    selectColumns(
      getPartsStatement.bindColumns(
        quineIdColumn.set(id),
        timestampColumn.set(atTime)
      ),
      dataColumn,
      multipartIndexColumn,
      multipartCountColumn
    )

  def enumerateAllNodeIds(): Source[QuineId, NotUsed] =
    executeSource(selectAllQuineIds.bind()).map(quineIdColumn.get).named("cassandra-all-node-scan")
}
