name := "sangria-subscriptions-example"
version := "0.1.0-SNAPSHOT"

description := "Sangria Subscriptions Example"

scalaVersion := "2.11.8"
scalacOptions ++= Seq("-deprecation", "-feature")

libraryDependencies ++= Seq(
  "org.sangria-graphql" %% "sangria" % "1.0.0",
  "org.sangria-graphql" %% "sangria-spray-json" % "1.0.0",
  "org.sangria-graphql" %% "sangria-akka-streams" % "1.0.0",

  "com.typesafe.akka" %% "akka-http" % "10.0.1",
  "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.1",
  "de.heikoseeberger" %% "akka-sse" % "2.0.0",

  "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  "com.typesafe.akka" %% "akka-stream-testkit" % "2.4.16" % "test"
)

resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

Revolver.settings