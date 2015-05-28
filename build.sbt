name := "akka-http-load-balancer"

version := "1.0"

scalaVersion := "2.11.6"


libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http-experimental" % "1.0-RC3",
  "com.typesafe.akka" %% "akka-http-spray-json-experimental" % "1.0-RC3"
)