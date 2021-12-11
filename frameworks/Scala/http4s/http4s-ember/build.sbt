name := "http4s-ember"

version := "1.0"

scalaVersion := "2.13.7"

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-feature",
  "-unchecked",
  "-language:reflectiveCalls",
  "-Ywarn-numeric-widen",
  "-target:11",
  "-Xlint:-byname-implicit",
  "-Xlint"
)

enablePlugins(SbtTwirl)

val http4sVersion = "0.23.6"

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-ember-server" % http4sVersion,
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-twirl" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.tpolecat" %% "natchez-http4s" % "0.2.0",
  "org.tpolecat" %% "natchez-jaeger" % "0.1.5",
  "org.tpolecat" %% "skunk-core" % "0.2.0",
  // Optional for auto-derivation of JSON codecs
  "io.circe" %% "circe-generic" % "0.14.1",
  "org.typelevel" %% "cats-effect" % "3.3.0",
  "co.fs2" %% "fs2-core" % "3.2.3",
  "co.fs2" %% "fs2-io" % "3.2.3",
  "ch.qos.logback" % "logback-classic" % "1.2.5"
)

assembly / assemblyMergeStrategy := {
 case PathList("META-INF", xs @ _*) => MergeStrategy.discard
 case x => MergeStrategy.first
}

addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
