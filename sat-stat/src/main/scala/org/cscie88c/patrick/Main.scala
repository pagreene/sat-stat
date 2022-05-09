/** Satellite Statistics
 *
 * Track the readings from many different telescopes, and highlight satellites that are
 * about to de-orbit, as well as sorting satellites that are in similar regions for later
 * batch analysis.
 *
 * This can be run from my PyCharm IDE, however when run with `sbt` directly, it does not
 * appear to properly execute. This is still a mystery to me.
 *
 * I made extensive use of https://akka.io/alpakka-samples
 */

package org.cscie88c.patrick

import akka.{Done, NotUsed}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, MediaRanges}
import akka.stream.scaladsl.{Flow, Merge, Sink, Source}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}

import spray.json._

// --------------------------------------------------------------------------
// CASE CLASSES //

/** Coordinates that map to the surface of the Earth.
 *
 * @param latitude: degrees latitude.
 * @param longitude: degrees longitude.
 */
final case class Coordinate(latitude: Float, longitude: Float)


/** Represent the identity of a Telescope, as reported by the API.
 *
 * @param id: The unique ID of the telescope.
 * @param coordinate: The coordinates of the telescope on the surface.
 */
final case class TelescopePosition(id: String, coordinate: Coordinate)


/** Give the position of a satellite.
 *
 * @param id: The unique ID of the satellite.
 * @param altitude: The height of the satellite above sea level.
 * @param coordinate: The coordinates of the telescope above the ground.
 */
final case class SatellitePosition(id: String, altitude: Float, coordinate: Coordinate)


/** The Response from the API.
 *
 * @param time: The time the measurement was taken, as a timestamp.
 * @param satellites: A list of the satellites observed by the telescope.
 * @param telescope: The identity of the telescope.
 */
final case class ApiResponse(time: Float, satellites: List[SatellitePosition],
                             telescope: TelescopePosition)


/** A flattened representation of the measurement info relevant to the steam.
 *
 * @param time: The time of the measurement, as a timestamp.
 * @param telescope_id: The unique ID of the telescope.
 * @param satellite_id: The unique ID of the satellite.
 * @param altitude: The height of the satellite above the ground.
 * @param coordinate: The coordinates of the satellite on the ground.
 */
final case class Measurement(time: Float, telescope_id: String, satellite_id: String,
                             altitude: Float, coordinate: Coordinate)


/** The information used to designate a collision file.
 *
 * Collision files are written as an artifact of this stream, placed there to be consumed
 * and managed by a downstream batching process. They are created using a sliding window
 * approach in the time, altitude, latitude, and longitude directions, where every satellite
 * will appear in three overlapping windows, to ensure that no pairings will be missed.
 *
 * See `CollisionFile.seqFromMeasurement` for details on how these objects are typically generated.
 *
 * @param time_range: The range of time, in seconds, that the file covers.
 * @param latitude_range: The range of latitudes, in degrees, that the file covers.
 * @param longitude_range: The range of longitudes, in degrees, that the file covers.
 * @param altitude_range: The range of altitudes, in meters, that the file covers.
 */
final case class CollisionFile(time_range: String, latitude_range: String, longitude_range: String,
                               altitude_range: String) {

  /** The collated name of the file.
   */
  val fileName: String =
    s"results/collisions/${time_range}_${latitude_range}_" +
    s"${longitude_range}_$altitude_range.csv"
}


/** Factory for CollisionFile instance. */
object CollisionFile {
  /** Round a value to the nearest multiple of an arbitrary number.
   *
   * For example, round(15.2, 2.0) would yield 16.0, and round(1.45, 0.5) would yield 1.5.
   *
   * @param value: The value to be rounded.
   * @param toTheNearest: The multiple to round it to.
   * @return a new value, rounded to the nearest instance of the given multiple.
   */
  private def round(value: Float, toTheNearest: Float): Float = math.round(value / toTheNearest) * toTheNearest

  /** Return a sequence of three strings, labelling three ranges.
   *
   * @param value: The original value.
   * @param window: The size of the window to use.
   * @param unitLabel: A label for the units (used in the range string).
   * @return a sequence os strings, each labeling a different range of values.
   */
  private def windowedSequence(value: Float, window: Float, unitLabel: String): Seq[String] = {
    val core = round(value, window)
    val centers = for {
      shift <- Seq(-window/2, 0, window/2)
    } yield (core + shift).toInt

    centers.map(c => f"${c - window/2}%1.1f$unitLabel-${c + window/2}%1.1f$unitLabel")
  }

  /** Generate a sequence of collision files for the given measurement.
   *
   * Specifically, generate 3 x 3 x 3 x 3 = 81 files for each measurement, in overlapping regions,
   * all of which contain the satellite. Each satellites central bin is centered on a window determined
   * by rounding the relevant value (e.g. time) to the nearest multiple of the window size.
   *
   * This is an admittedly blunt force (even sloppy) approach, algorithmically speaking, with
   * innumerable ways to optimize it, however the focus of this project was on Akka, not algorithms,
   * so that got priority.
   *
   * @param measurement: A satellite measurement.
   * @return a sequence of 81 collision files in which to place the satellite record.
   */
  def seqFromMeasurement(measurement: Measurement): Seq[CollisionFile] =
    for {
      t <- windowedSequence(measurement.time, 60, "s")  // seconds
      lat <- windowedSequence(measurement.coordinate.latitude, 1, "deg")  // degrees
      long <- windowedSequence(measurement.coordinate.longitude, 1, "deg")  // degrees
      alt <- windowedSequence(measurement.altitude, 100, "m")  // meters
    } yield CollisionFile(t, lat, long, alt)
}


// --------------------------------------------------------------------------
// JSON CONVERSION //

/** Factory used for parsing JSON from the API. */
object MyJsonProtocol extends DefaultJsonProtocol {
  implicit val coordinateFormat: RootJsonFormat[Coordinate] =
    jsonFormat2(Coordinate)
  implicit val telescopePositionFormat: RootJsonFormat[TelescopePosition] =
    jsonFormat2(TelescopePosition)
  implicit val satellitePositionFormat: RootJsonFormat[SatellitePosition] =
    jsonFormat3(SatellitePosition)
  implicit val apiResponseFormat: RootJsonFormat[ApiResponse] =
    jsonFormat3(ApiResponse)
}

import MyJsonProtocol._

/**
 * MAIN
 */
object Main extends App {

  // --------------------------------------------------------------------------
  // CONSTANTS //

  /** The altitude typically considered the edge of the atmosphere, to very low precision. */
  val ATMOSPHERIC_HEIGHT = 12e3 // meters

  /** Initialize the Akka actor system. */
  implicit val actorSystem: ActorSystem[Nothing] =
    ActorSystem[Nothing](Behaviors.empty, "sat-stat")

  // --------------------------------------------------------------------------
  // FUNCTIONS //

  /** Create a source that gets an ApiResponse from the raw HTTP Response.
   *
   * @param response: The raw HTTP Response.
   * @return an `ApiResponse` object containing the body of the API response.
   */
  private def extractEntityData(response: HttpResponse): Source[ApiResponse, _] =
    response match {
      case HttpResponse(OK, _, entity, _) =>
        entity
          .dataBytes
          .map[ApiResponse](rawBytes =>
              rawBytes
                .utf8String
                .parseJson
                .convertTo[ApiResponse]
          )
      case notOkResponse =>
        Source.single(ApiResponse(-1, List(), TelescopePosition("nil", Coordinate(0, 0))))
    }

  /** Create a Source for one of the API endpoints.
   *
   * Each telescope is represented by a channel in the API. In reality, this might be pulling
   * from different web endpoints entirely, each hosting its own API, and quite likely
   * with slightly different data outputs. For the sake of simplicity though, I am modelling
   * all that complexity with a simple channel parameter.
   *
   * @param channel: The integer for the telescope channel.
   * @return a Source that produces a stream of ApiResponse's.
   */
  private def formApiSource(channel: Integer): Source[ApiResponse, _] = {
    val httpRequest =
      HttpRequest(uri = s"http://localhost:8333/telescope/$channel")
        .withHeaders(Accept(MediaRanges.`text/*`))

    Source
      .tick(1.seconds, 5.seconds, httpRequest)
      .mapAsync(1)(Http()(actorSystem.toClassic).singleRequest(_))
      .flatMapConcat(extractEntityData)
  }

  // --------------------------------------------------------------------------
  // SOURCES //

  /** Merge all the telescope Sources together.
   *
   * I had hoped to do this more programmatically, but the status of agents is pre-determined
   * at the start because they can and often do represent physically separate deployed machines.
   * With the dawn of infrastructure-as-code, there may be ways around this, but that is far
   * beyond the scope of this project.
   */
  val mergedSources: Source[ApiResponse, NotUsed] =
    Source
      .combine(
        formApiSource(0),
        formApiSource(1),
        formApiSource(2),
        formApiSource(3),
        formApiSource(4),
        formApiSource(5),
        formApiSource(6),
        formApiSource(7),
        formApiSource(8),
        formApiSource(9),
        formApiSource(10),
        formApiSource(11),
        formApiSource(12),
        formApiSource(13),
        formApiSource(14),
        formApiSource(15),
        formApiSource(16),
        formApiSource(17),
        formApiSource(18),
        formApiSource(19),
      )(
        Merge(_)
      )

  // --------------------------------------------------------------------------
  // FLOWS //

  /** Convert the API Response, which has a list of satellites, into a stream of satellite measurements.
   */
  val separateMeasurements: Flow[ApiResponse, List[Measurement], NotUsed] =
    Flow[ApiResponse]
      .map(
        (resp: ApiResponse) => for {
          sat <- resp.satellites
        } yield Measurement(
          resp.time,
          resp.telescope.id,
          sat.id,
          sat.altitude,
          sat.coordinate
        )
      )

  /** Find measurements that indicate a satellite is about to de-orbit (is below the atmosphere).
   */
  val findCrashingSatellites: Flow[Measurement, SatellitePosition, NotUsed] =
    Flow[Measurement]
      .filter(_.altitude < ATMOSPHERIC_HEIGHT)
      .map(
        (m: Measurement) =>
          SatellitePosition(m.satellite_id, m.altitude, m.coordinate)
      )

  /** Duplicate a measurement into the numerous collision files.
   */
  val separateFiles: Flow[Measurement, List[(CollisionFile, Measurement)], NotUsed] =
    Flow[Measurement].map(
      (measurement: Measurement) => for {
        collisionFile <- CollisionFile.seqFromMeasurement(measurement).toList
      } yield (collisionFile, measurement)
    )

  /** Flatten the stream of lists of collision files.
   */
  val collisionGroupFlow: Flow[Measurement, (CollisionFile, Measurement), NotUsed] =
    Flow[Measurement]
    .via(separateFiles)
    .mapConcat(identity)

  // --------------------------------------------------------------------------
  // SINKS //

  /** Dump the raw measurements to a header-less CSV file.
   *
   * The rows indicate the telescope that made the measurement, the satellite it is
   * measuring, the altitude of the satellite (meters above sea level), and the
   * latitude and longitude.
   */
  val rawMeasurementSink: Sink[Measurement, Future[Done]] =
    Sink
      .foreach(
        (m: Measurement) => {
          val line = s"${m.telescope_id}, ${m.satellite_id}, ${m.altitude}, " +
            s"${m.coordinate.latitude}, ${m.coordinate.longitude}\n"
          Files.write(
            Paths.get("results/raw_measurements.csv"),
            line.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          )
        }
      )

  /** Dump the positions of crashed satellites.
   *
   * Each row gives the ID of the satellite, and the latitude and longitude
   * where it was detected below the atmosphere.
   */
  val crashSink: Sink[SatellitePosition, Future[Done]] =
    Sink
      .foreach(
        (satellite: SatellitePosition) => {
          val line = s"${satellite.id}, ${satellite.coordinate.latitude}, ${satellite.coordinate.longitude}\n"
          Files.write(
            Paths.get("results/crashes.csv"),
            line.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          )
        }
      )

  /** Dump a collision record into the collision file.
   *
   * If two different satellites appear in the same collision file, they are at risk
   * of colliding. Each file will contain a satellite ID, as well as the altitude, latitude,
   * and longitude.
   */
  val collisionSink: Sink[(CollisionFile, Measurement), Future[Done]] =
    Sink
      .foreach(
        (tup: (CollisionFile, Measurement)) => {
          val line =
            s"${tup._2.satellite_id}, ${tup._2.altitude}, " +
            s"${tup._2.coordinate.latitude}, ${tup._2.coordinate.longitude}\n"
          Files.write(
            Paths.get(tup._1.fileName),
            line.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          )
        }
      )

  // --------------------------------------------------------------------------
  // STREAMS //

  /** The flow-to-sink for finding de-orbits. */
  val crashStream: Sink[Measurement, NotUsed] =
    findCrashingSatellites
      .to(crashSink)

  /** The flow-to-sink placing satellites into collision files. */
  val collisionStream: Sink[Measurement, NotUsed] =
    collisionGroupFlow
      .to(collisionSink)

  // --------------------------------------------------------------------------
  // PIPELINE //

  /** The final pipeline. */
  mergedSources
    .via(separateMeasurements)
    .mapConcat(identity)
    .alsoTo(crashStream)
    .alsoTo(collisionStream)
    .to(rawMeasurementSink)
    .run()
}
