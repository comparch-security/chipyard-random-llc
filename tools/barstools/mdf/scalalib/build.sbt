organization := "edu.berkeley.cs"
name := "mdf"
version := "0.1-SNAPSHOT"
scalaVersion := "2.12.12"
scalacOptions := Seq("-deprecation", "-feature", "-language:reflectiveCalls")
libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-json" % "2.6.10",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)
