package com.thatdot.quine.routes

import endpoints4s.algebra.Tag
import endpoints4s.generic.{docs, title}

trait AlgorithmRoutes
    extends endpoints4s.algebra.Endpoints
    with endpoints4s.algebra.JsonEntitiesFromSchemas
    with endpoints4s.generic.JsonSchemas
    with exts.QuineEndpoints
    with exts.AnySchema {

  private val api = path / "api" / "v1"
  private val algorithmsPrefix = api / "algorithm"

  private[this] val algorithmTag = Tag("Graph Algorithms")
    .withDescription(
      Some(
        "High-level operations on the graph to support graph AI, ML, and other algorithms."
      )
    )

  val walkLength: QueryString[Option[Int]] = qs[Option[Int]](
    "length",
    docs = Some("maximum length of a walk")
  )

  /* WARNING: these values duplicate `AlgorithmGraph.defaults.walkPrefix` and `walkSuffix` from the
   * `com.thatdot.quine.graph` package which is not available here.
   * Beware of changes in one place not mirrored to the other!
   */
  private val queryPrefix = "MATCH (thisNode) WHERE id(thisNode) = $n "
  private val querySuffix = "RETURN id(thisNode)"

  val onNodeQuery: QueryString[Option[String]] = qs[Option[String]](
    "query",
    docs = Some(
      s"""Cypher query run on each node of the walk. You can use this query to collect properties instead of node IDs.
         |A `RETURN` statement can return any number of values, separated by `,`s. If returning the same value
         |multiple times, you will need to alias subsequent values with `AS` so that column names are unique. If a list
         |is returned, its content will be flattened out one level and concatenated with the rest of the aggregated
         |values.
         |
         |The provided query will have the following prefix prepended: `$queryPrefix` where `${"$n"}` evaluates
         |to the ID of the node on which the query is executed. The default value of this parameter is:
         |`$querySuffix`""".stripMargin
    )
  )

  val numberOfWalks: QueryString[Option[Int]] = qs[Option[Int]](
    "count",
    docs = Some("integer for how many random walks from a node to generate")
  )

  val returnParameter: QueryString[Option[Double]] = qs[Option[Double]](
    "return",
    docs = Some(
      "the `p` parameter to determine likelihood of returning to the node just visited: `1/p`  Lower is " +
      "more likely; but if `0`, never return to previous node. Defaults to `1`."
    )
  )

  val inOutParameter: QueryString[Option[Double]] = qs[Option[Double]](
    "in-out",
    docs = Some(
      "the `q` parameter to determine likelihood of visiting a node outside the neighborhood of the" +
      " starting node: `1/q`  Lower is more likely; but if `0`, never visit the neighborhood. Defaults to `1`."
    )
  )

  val randomSeedOpt: QueryString[Option[String]] = qs[Option[String]](
    name = "seed",
    docs = Some("Optionally specify a random seed for generating walks")
  )

  sealed trait SaveLocation
  @title("Local File")
  case class LocalFile(
    @docs("Optional name of the file to save in the working directory") fileName: Option[String]
  ) extends SaveLocation

  @title("S3 Bucket")
  case class S3Bucket(
    @docs("S3 bucket name") bucketName: String,
    @docs("Optional name of the file in the S3 bucket") key: Option[String]
  ) extends SaveLocation

  implicit lazy val localFileSchema: JsonSchema[LocalFile] = genericJsonSchema[LocalFile]
  implicit lazy val s3BucketSchema: JsonSchema[S3Bucket] = genericJsonSchema[S3Bucket]
  implicit lazy val saveLocationSchema: JsonSchema[SaveLocation] = genericJsonSchema[SaveLocation]

  final val algorithmSaveRandomWalks: Endpoint[
    (
      Option[Int],
      Option[Int],
      Option[String],
      Option[Double],
      Option[Double],
      Option[String],
      AtTime,
      Int,
      SaveLocation
    ),
    Either[ClientErrors, String]
  ] =
    endpoint(
      request = put(
        url = algorithmsPrefix / "walk" /?
          (walkLength & numberOfWalks & onNodeQuery & returnParameter &
          inOutParameter & randomSeedOpt & atTime & parallelism),
        entity = jsonRequestWithExample[SaveLocation](example = S3Bucket("your-s3-bucket-name", None))
      ),
      response = badRequest(docs = Some("Invalid file"))
        .orElse(accepted(textResponse)),
      docs = EndpointDocs()
        .withSummary(Some("Save random walks to a file"))
        .withDescription(
          Some(
            """Generate random walks from all nodes in the graph (optionally: at a specific historical time), and save
              |the results.
              |
              |The output file is a CSV where each row is one random walk. The first column will always
              |be the node ID where the walk originated. Each subsequent column will be either:
              |
              |a.) by default, the ID of each node encountered (including the starting node ID again in the second
              |column), or
              |
              |b.) optionally, the results of Cypher query executed from each node encountered on the walk; where
              |multiple columns and rows returned from this query will be concatenated together sequentially into
              |the aggregated walk results.
              |
              |**The resulting CSV may have rows of varying length.**""".stripMargin
          )
        )
        .withTags(List(algorithmTag))
    )

  final val algorithmRandomWalk: Endpoint[
    (Id, (Option[Int], Option[String], Option[Double], Option[Double], Option[String], AtTime)),
    Either[ClientErrors, List[String]]
  ] =
    endpoint(
      request = get(
        algorithmsPrefix / "walk" / nodeIdSegment /?
        (walkLength & onNodeQuery & returnParameter & inOutParameter & randomSeedOpt & atTime)
      ),
      response = badRequest().orElse(ok(jsonResponse[List[String]])),
      docs = EndpointDocs()
        .withSummary(Some("Generate a random walk"))
        .withTags(List(algorithmTag))
    )
}
