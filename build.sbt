import scala.collection.Seq

publishAsOSSProject in ThisBuild := true

homepage in ThisBuild := Some(url("https://github.com/slamdata/fs2-gzip"))

scmInfo in ThisBuild := Some(ScmInfo(
  url("https://github.com/slamdata/fs2-gzip"),
  "scm:git@github.com:slamdata/fs2-gzip.git"))

lazy val root = project
  .in(file("."))
  .settings(noPublishSettings)
  .aggregate(core)
  .enablePlugins(AutomateHeaderPlugin)

lazy val core = project
  .in(file("core"))
  .settings(name := "fs2-gzip")
  .settings(
     libraryDependencies ++= Seq(
      "co.fs2"     %% "fs2-core"    % "1.0.0",
      "co.fs2"     %% "fs2-io"      % "1.0.0" % "test",
      "org.specs2" %% "specs2-core" % "4.3.4" % "test"),

     addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.2.4"))
  .enablePlugins(AutomateHeaderPlugin)
