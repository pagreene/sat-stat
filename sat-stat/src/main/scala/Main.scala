package org.cscie88c.patrick

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, MediaRanges }
import akka.stream.scaladsl.{ Sink, Source }

import scala.concurrent.Future


object Main extends App {
  println("Hello! Let's get some stats on some sats.")

  implicit val actorSystem: ActorSystem = ActorSystem("alpakka-samples")

  import actorSystem.dispatcher

  val httpRequest = HttpRequest(uri = "http://localhost:8333/telescope/0")
    .withHeaders(Accept(MediaRanges.`text/*`))

  val future: Future[Done] =
    Source
      .single(httpRequest) //: HttpRequest
      .mapAsync(1)(Http().singleRequest(_)) //: HttpResponse
      .runWith(Sink.foreach(println))

  future.map { _ =>
    println("Done!")
    actorSystem.terminate()
  }

}
