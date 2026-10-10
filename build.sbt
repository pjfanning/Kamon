/* =========================================================================================
 * Copyright © 2013-2021 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

// This fork only builds and publishes the Pekko instrumentation modules. Everything else (kamon-core,
// kamon-instrumentation-common, kamon-scala-future, kamon-testkit, kanela-agent) comes from the io.kamon jars published
// on Maven Central. The other module directories are kept, unbuilt, so upstream changes can still be merged cleanly.
lazy val kamonVersion = "2.8.1"
lazy val kamonCore = "io.kamon" %% "kamon-core" % kamonVersion
lazy val kamonScalaFuture = "io.kamon" %% "kamon-scala-future" % kamonVersion
lazy val kamonTestkit = "io.kamon" %% "kamon-testkit" % kamonVersion

lazy val kamon = (project in file("."))
  .settings(noPublishing: _*)
  .settings(crossScalaVersions := Nil)
  .aggregate(instrumentation)

val instrumentationProjects = Seq[ProjectReference](
  `kamon-pekko`,
  `kamon-pekko-http`,
  `kamon-pekko-grpc`,
  `kamon-pekko-connectors-kafka`
)

lazy val instrumentation = (project in file("instrumentation"))
  .settings(noPublishing: _*)
  .settings(crossScalaVersions := Nil)
  .aggregate(instrumentationProjects: _*)

lazy val `kamon-pekko` = (project in file("instrumentation/kamon-pekko"))
  .enablePlugins(JavaAgent)
  .settings(instrumentationSettings: _*)
  .settings(Seq(
    crossScalaVersions := Seq(`scala_2.13_version`, scala_3_version),
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor" % pekkoVersion % "provided"
    )
  ))
  .settings(
    libraryDependencies ++= Seq(
      kamonScalaFuture,
      kamonTestkit % "test"
    )
  )

lazy val `kamon-pekko-http` = (project in file("instrumentation/kamon-pekko-http"))
  .enablePlugins(JavaAgent)
  .settings(instrumentationSettings)
  .settings(Seq(
    crossScalaVersions := Seq(`scala_2.13_version`, scala_3_version),
    libraryDependencies ++= Seq(
      kanelaAgent % "provided",
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion % "provided",
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion % "provided",
      scalatest % "test",
      slf4jApi % "test",
      slf4jnop % "test",
      okHttp % "test",
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % "test",
      "com.github.pjfanning" %% "pekko-http-json4s" % "2.0.0" % "test",
      "org.json4s" %% "json4s-native" % "4.0.6" % "test"
    )
  )).dependsOn(`kamon-pekko`)
  .settings(libraryDependencies += kamonTestkit % "test")

lazy val `kamon-pekko-grpc` = (project in file("instrumentation/kamon-pekko-grpc"))
  .enablePlugins(JavaAgent, PekkoGrpcPlugin)
  .settings(instrumentationSettings)
  .settings(Seq(
    PB.additionalDependencies := Seq.empty,
    crossScalaVersions := Seq(`scala_2.13_version`, scala_3_version),
    libraryDependencies ++= Seq(
      kanelaAgent % "provided",
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion % "provided",
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion % "provided",
      "org.apache.pekko" %% "pekko-discovery" % pekkoVersion % "provided",
      "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.8" % "provided",
      "org.apache.pekko" %% "pekko-grpc-runtime" % "1.0.0" % "provided",
      "io.grpc" % "grpc-stub" % "1.43.2" % "provided",
      scalatest % "test",
      slf4jApi % "test",
      logbackClassic % "test"
    )
  )).dependsOn(`kamon-pekko-http`)
  .settings(libraryDependencies += kamonTestkit % "test")

lazy val `kamon-pekko-connectors-kafka` = (project in file("instrumentation/kamon-pekko-connectors-kafka"))
  .enablePlugins(JavaAgent)
  .settings(instrumentationSettings)
  .settings(
    crossScalaVersions := Seq(`scala_2.13_version`, scala_3_version),
    libraryDependencies ++= Seq(
      kanelaAgent % "provided",
      "org.apache.pekko" %% "pekko-connectors-kafka" % "1.0.0" % "provided",
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion % "provided",
      scalatest % "test",
      logbackClassic % "test"
    )
  ).dependsOn(`kamon-pekko`)
  .settings(libraryDependencies ++= Seq(kamonCore, kamonTestkit % "test"))
