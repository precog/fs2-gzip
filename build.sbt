import scala.collection.Seq

performMavenCentralSync in ThisBuild := false   // basically just ignores all the sonatype sync parts of things

publishAsOSSProject in ThisBuild := true

homepage in ThisBuild := Some(url("https://github.com/slamdata/fs2-gzip"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/fs2-gzip"),
  "scm:git@github.com:slamdata/fs2-gzip.git"))

// Include to also publish a project's tests
lazy val publishTestsSettings = Seq(
  publishArtifact in (Test, packageBin) := true)

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)
  .enablePlugins(AutomateHeaderPlugin)

lazy val core = project
  .in(file("core"))
  .settings(name := "fs2-gzip")
  .settings(
    /*
    libraryDependencies += ...
     */)
  .enablePlugins(AutomateHeaderPlugin)
