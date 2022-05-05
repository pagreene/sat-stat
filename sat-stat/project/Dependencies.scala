import sbt._

object Dependencies {
  lazy val test = Seq(
    "org.scalatest" %% "scalatest" % "3.0.8" % Test,
  )

  val circeVersion = "0.13.0"
  val pureconfigVersion = "0.15.0"
  val AkkaVersion = "2.6.19"
  val AkkaHttpVersion = "10.1.12"

  lazy val core = Seq(
    // support for JSON formats
    "io.circe" %% "circe-core" % circeVersion,
    "io.circe" %% "circe-generic" % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion,
    "io.circe" %% "circe-literal" % circeVersion,

    // support for typesafe configuration
    "com.github.pureconfig" %% "pureconfig" % pureconfigVersion,

    // akka streams
    "com.typesafe.akka" %% "akka-stream" % AkkaVersion,

    // akka http
    "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,

    // logging
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
    "ch.qos.logback" % "logback-classic" % "1.2.3"
  )
}
