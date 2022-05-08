/*
 * This defines the main Akka app.
 *
 * So far, this is almost entirely based off of https://akka.io/alpakka-samples/http-csv-to-kafka
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
import spray.json._

import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import java.nio.file.{Files, Paths}

final case class Coordinate(latitude: Float, longitude: Float)
final case class TelescopePosition(id: String, coordinate: Coordinate)
final case class SatellitePosition(id: String, altitude: Float, coordinate: Coordinate)
final case class ApiResponse(time: Float, satellites: List[SatellitePosition],
                             telescope: TelescopePosition)
final case class Measurement(time: Float, telescope_id: String, satellite_id: String,
                             altitude: Float, coordinate: Coordinate)

final case class CollisionFile(time_range: String, latitude_range: String, longitude_range: String,
                               altitude_range: String) {
  val fileName: String = s"results/collisions/${time_range}_${latitude_range}_${longitude_range}_$altitude_range.csv"
}

object CollisionFile {
  private def round(value: Float, toTheNearest: Float): Float = math.round(value / toTheNearest) * toTheNearest

  private def windowedSequence(value: Float, window: Float, unitLabel: String): Seq[String] = {
    val core = round(value, window)
    val centers = for {
      shift <- Seq(-window/2, 0, window/2)
    } yield (core + shift).toInt

    centers.map(c => f"${c - window/2}%1.1f$unitLabel-${c + window/2}%1.1f$unitLabel")
  }

  def seqFromMeasurement(measurement: Measurement): Seq[CollisionFile] =
    for {
      t <- windowedSequence(measurement.time, 60, "s")  // seconds
      lat <- windowedSequence(measurement.coordinate.latitude, 1, "deg")  // degrees
      long <- windowedSequence(measurement.coordinate.longitude, 1, "deg")  // degrees
      alt <- windowedSequence(measurement.altitude, 100, "m")  // meters
    } yield CollisionFile(t, lat, long, alt)
}

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

object Main extends App {
  println("Hello! Let's get some stats on some sats.")

  implicit val actorSystem: ActorSystem[Nothing] =
    ActorSystem[Nothing](Behaviors.empty, "sat-stat")

  import actorSystem.executionContext

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


  private def formApiSource(channel: Integer): Source[ApiResponse, _] = {
    val httpRequest =
      HttpRequest(uri = s"http://localhost:8333/telescope/$channel")
        .withHeaders(Accept(MediaRanges.`text/*`))

    Source
      .tick(1.seconds, 5.seconds, httpRequest)
      .mapAsync(1)(Http()(actorSystem.toClassic).singleRequest(_))
      .flatMapConcat(extractEntityData)
  }

  val printMeasurementSink: Sink[Measurement, Future[Done]] = Sink.foreach(
    (measure: Measurement) =>
      println(
          s"Measurement: ${measure.telescope_id}, " +
            s"${measure.satellite_id}, " +
            s"(${measure.altitude}, " +
            s"${measure.coordinate.latitude}, " +
            s"${measure.coordinate.longitude})"
      )
  )

  val printCrashedSatellite: Sink[SatellitePosition, Future[Done]] = Sink.foreach(
    (satellite: SatellitePosition) =>
      println(s"CRASH of ${satellite.id}: ${satellite.coordinate}")
  )

  val separateMeasurements: Flow[ApiResponse, List[Measurement], NotUsed] = Flow[ApiResponse].map(
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

  val merger = Source.combine(
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

  val ATMOSPHERIC_HEIGHT = 12e3

  val findCrashingSatellites: Flow[Measurement, SatellitePosition, NotUsed] =
    Flow[Measurement]
      .filter(_.altitude < ATMOSPHERIC_HEIGHT)
      .map(
        (m: Measurement) =>
          SatellitePosition(m.satellite_id, m.altitude, m.coordinate)
      )

  val crashSink: Sink[Measurement, NotUsed] =
    findCrashingSatellites
      .to(printCrashedSatellite)

  val separateFiles: Flow[Measurement, List[(CollisionFile, String)], NotUsed] =
    Flow[Measurement].map(
      (m: Measurement) => for {
        collisionFile <- CollisionFile.seqFromMeasurement(m).toList
      } yield (
        collisionFile,
        s"${m.satellite_id}, ${m.altitude}, ${m.coordinate.latitude}"
      )
    )

  val groupFlow = Flow[Measurement]
    .via(separateFiles)
    .mapConcat(identity)

  val fileSink: Sink[(CollisionFile, String), Future[Done]] = Sink
   .foreach(
     (tup: (CollisionFile, String)) => {
       Files.write(Paths.get(tup._1.fileName), tup._2.getBytes(StandardCharsets.UTF_8))

     }
   )

  val groupSink = groupFlow.to(fileSink)

  val measurementSource =
    merger
      .via(separateMeasurements)
      .mapConcat(identity)

  measurementSource
    .alsoTo(crashSink)
    .alsoTo(groupSink)
    .to(printMeasurementSink).run()

}
