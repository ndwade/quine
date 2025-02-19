package com.thatdot.quine.persistor

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Future

import akka.NotUsed
import akka.stream.scaladsl.Source

import com.thatdot.quine.graph.{
  BaseGraph,
  EventTime,
  MultipleValuesStandingQueryPartId,
  NodeEvent,
  StandingQuery,
  StandingQueryId
}
import com.thatdot.quine.model.DomainGraphNode.DomainGraphNodeId
import com.thatdot.quine.model.{DomainGraphNode, QuineId}

/** Wrapper for a persistor that checks that some invariants are upheld:
  *
  *   - for every node: every event occurs at a unique time
  *   - for every node: every snapshot occurs at a unique time
  */
class InvariantWrapper(wrapped: PersistenceAgent) extends WrappedPersistenceAgent(wrapped) {

  private val events = new ConcurrentHashMap[QuineId, ConcurrentHashMap[EventTime, NodeEvent]]
  private val snapshots = new ConcurrentHashMap[QuineId, ConcurrentHashMap[EventTime, Array[Byte]]]

  override def emptyOfQuineData(): Future[Boolean] =
    if (events.isEmpty && snapshots.isEmpty) wrapped.emptyOfQuineData()
    else Future.successful(false)

  /** Persist [[NodeChangeEvent]] values. */
  def persistNodeChangeEvents(id: QuineId, eventsWithTime: Seq[NodeEvent.WithTime]): Future[Unit] = {
    for { NodeEvent.WithTime(event, atTime) <- eventsWithTime } {
      val previous = events
        .computeIfAbsent(id, _ => new ConcurrentHashMap[EventTime, NodeEvent]())
        .put(atTime, event)
      assert(
        (previous eq null) || (previous eq event),
        s"Duplicate events at node id $id and time $atTime: $event & $previous"
      )
    }
    wrapped.persistNodeChangeEvents(id, eventsWithTime)
  }

  /** Persist [[DomainIndexEvent]] values. */
  def persistDomainIndexEvents(id: QuineId, eventsWithTime: Seq[NodeEvent.WithTime]): Future[Unit] = {
    for { NodeEvent.WithTime(event, atTime) <- eventsWithTime } {
      val previous = events
        .computeIfAbsent(id, _ => new ConcurrentHashMap[EventTime, NodeEvent]())
        .put(atTime, event)
      assert(
        (previous eq null) || (previous eq event),
        s"Duplicate events at node id $id and time $atTime: $event & $previous"
      )
    }
    wrapped.persistDomainIndexEvents(id, eventsWithTime)
  }

  def getNodeChangeEventsWithTime(
    id: QuineId,
    startingAt: EventTime,
    endingAt: EventTime
  ): Future[Iterable[NodeEvent.WithTime]] =
    wrapped.getNodeChangeEventsWithTime(id, startingAt, endingAt)

  def getDomainIndexEventsWithTime(
    id: QuineId,
    startingAt: EventTime,
    endingAt: EventTime
  ): Future[Iterable[NodeEvent.WithTime]] =
    wrapped.getDomainIndexEventsWithTime(id, startingAt, endingAt)

  def enumerateJournalNodeIds(): Source[QuineId, NotUsed] = wrapped.enumerateJournalNodeIds()

  def enumerateSnapshotNodeIds(): Source[QuineId, NotUsed] = wrapped.enumerateSnapshotNodeIds()

  def persistSnapshot(id: QuineId, atTime: EventTime, state: Array[Byte]): Future[Unit] = {
    val previous = snapshots
      .computeIfAbsent(id, _ => new ConcurrentHashMap[EventTime, Array[Byte]]())
      .put(atTime, state)
    assert(
      (previous eq null) || (previous eq state),
      s"Duplicate snapshots at node id $id and time $atTime: $state & $previous"
    )
    wrapped.persistSnapshot(id, atTime, state)
  }

  def getLatestSnapshot(id: QuineId, upToTime: EventTime): Future[Option[Array[Byte]]] =
    wrapped.getLatestSnapshot(id, upToTime)

  def persistStandingQuery(standingQuery: StandingQuery): Future[Unit] =
    wrapped.persistStandingQuery(standingQuery)

  def removeStandingQuery(standingQuery: StandingQuery): Future[Unit] = wrapped.removeStandingQuery(standingQuery)

  def getStandingQueries: Future[List[StandingQuery]] = wrapped.getStandingQueries

  def getMultipleValuesStandingQueryStates(
    id: QuineId
  ): Future[Map[(StandingQueryId, MultipleValuesStandingQueryPartId), Array[Byte]]] =
    wrapped.getMultipleValuesStandingQueryStates(id)

  def getAllMetaData(): Future[Map[String, Array[Byte]]] = wrapped.getAllMetaData()
  def getMetaData(key: String): Future[Option[Array[Byte]]] = wrapped.getMetaData(key)
  def setMetaData(key: String, newValue: Option[Array[Byte]]): Future[Unit] = wrapped.setMetaData(key, newValue)

  def persistDomainGraphNodes(domainGraphNodes: Map[DomainGraphNodeId, DomainGraphNode]): Future[Unit] =
    wrapped.persistDomainGraphNodes(domainGraphNodes)
  def removeDomainGraphNodes(domainGraphNodes: Set[DomainGraphNodeId]): Future[Unit] = wrapped.removeDomainGraphNodes(
    domainGraphNodes
  )
  def getDomainGraphNodes(): Future[Map[DomainGraphNodeId, DomainGraphNode]] = wrapped.getDomainGraphNodes()

  override def setMultipleValuesStandingQueryState(
    standingQuery: StandingQueryId,
    id: QuineId,
    standingQueryId: MultipleValuesStandingQueryPartId,
    state: Option[Array[Byte]]
  ): Future[Unit] = wrapped.setMultipleValuesStandingQueryState(standingQuery, id, standingQueryId, state)

  override def ready(graph: BaseGraph): Unit = wrapped.ready(graph)

  def shutdown(): Future[Unit] = wrapped.shutdown()

  def persistenceConfig: PersistenceConfig = wrapped.persistenceConfig

  /** Delete all [DomainIndexEvent]]s by their held DgnId. Note that depending on the storage implementation
    * this may be an extremely slow operation.
    *
    * @param dgnId
    */
  override def deleteDomainIndexEventsByDgnId(dgnId: DomainGraphNodeId): Future[Unit] =
    wrapped.deleteDomainIndexEventsByDgnId(dgnId)
}
