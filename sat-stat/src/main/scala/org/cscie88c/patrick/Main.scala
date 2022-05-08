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
import akka.stream.scaladsl.{Sink, Source, Flow, Merge}
import akka.util.ByteString

import spray.json._
import DefaultJsonProtocol._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

final case class Coordinate(latitude: Float, longitude: Float)
final case class TelescopePosition(id: String, coordinate: Coordinate)
final case class SatellitePosition(id: String, altitude: Float, coordinate: Coordinate)
final case class ApiResponse(time: Float, satellites: List[SatellitePosition],
                             telescope: TelescopePosition)
final case class Measurement(time: Float, telescope_id: String, satellite_id: String,
                             altitude: Float, coordinate: Coordinate)

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

  val splitter: Flow[ApiResponse, List[Measurement], NotUsed] = Flow[ApiResponse].map(
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

  val groupSatellites: Flow[Measurement, SatellitePosition, NotUsed] =
    Flow[Measurement]
      .filter(_.altitude < ATMOSPHERIC_HEIGHT)
      .map(
        (m: Measurement) =>
          SatellitePosition(m.satellite_id, m.altitude, m.coordinate)
      )

  val measurementSource = merger.via(splitter).mapConcat(identity)
  measurementSource
    .alsoTo(groupSatellites.to(printCrashedSatellite))
    .to(printMeasurementSink)


  val future: Future[Done] =
    merger
      .via(splitter)
      .mapConcat(identity)
      .runWith(printMeasurementSink)

  future.map { _ =>
    println("Done!")
    actorSystem.terminate()
  }

}
