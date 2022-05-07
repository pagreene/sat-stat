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

final case class TelescopePosition(id: String, latitude: Float, longitude: Float)
final case class SatellitePosition(id: String, altitude: Float, latitude: Float, longitude: Float)
final case class ApiResponse(satellites: List[SatellitePosition], position: TelescopePosition)

object MyJsonProtocol extends DefaultJsonProtocol {
  implicit val telescopePositionFormat: RootJsonFormat[TelescopePosition] = jsonFormat3(TelescopePosition)
  implicit val satellitePositionFormat: RootJsonFormat[SatellitePosition] = jsonFormat4(SatellitePosition)
  implicit val apiResponseFormat: RootJsonFormat[ApiResponse] = jsonFormat2(ApiResponse)
}

import MyJsonProtocol._

object Main extends App {
  println("Hello! Let's get some stats on some sats.")

  implicit val actorSystem: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "sat-stat")

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
        Source
          .failed(new RuntimeException(s"Bad response $notOkResponse"))
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

  val merger = Source.combine(
    formApiSource(0),
    formApiSource(1),
    formApiSource(2),
    formApiSource(3),
    formApiSource(4)
  )(
    Merge(_)
  )

  val future: Future[Done] =
    merger
      .runWith(Sink.foreach(response => println(s"Got the response: ${response.position.toString}")))

  future.map { _ =>
    println("Done!")
    actorSystem.terminate()
  }

}
