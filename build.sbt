ThisBuild / scalaVersion := "2.13.17"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "aegis"

val chiselVersion = "7.3.0"

lazy val aegis = (project in file("."))
  .settings(
    name := "aegis-soc",
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-Ymacro-annotations",
      "-Ytasty-reader",
    ),
    Compile / mainClass := Some("aegis.elaborate.TopElaborate"),
    libraryDependencies += "org.chipsalliance" %% "chisel" % chiselVersion,
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full),
  )
