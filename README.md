# Kamon Pekko instrumentation (fork)

This is a fork of [Kamon](https://github.com/kamon-io/Kamon) that only builds and publishes Kamon's
[Apache Pekko](https://pekko.apache.org/) and Play 3 instrumentation modules, with changes on top of the upstream code.

For everything else about Kamon, including documentation, the other instrumentation modules and support, go to the main
[Kamon repository](https://github.com/kamon-io/Kamon) and [kamon.io](https://kamon.io/).

## Modules

These modules are published under the `com.github.pjfanning` group ID, for Scala 2.13 and Scala 3. They keep the
upstream artifact and package names.

| Module | Instruments |
|---|---|
| `kamon-pekko` | Pekko actors, routers, dispatchers, remoting and cluster sharding |
| `kamon-pekko-http` | Pekko HTTP server and client |
| `kamon-pekko-grpc` | Pekko gRPC |
| `kamon-pekko-connectors-kafka` | Pekko Connectors Kafka producers |
| `kamon-play` | Play Framework 3 (Pekko HTTP and Netty servers, Play WS client) |

This fork's `kamon-play` supports Play 3 only (Pekko-based). For Play 2.8 and 2.9 (Akka-based), use `io.kamon:kamon-play` from the main Kamon project.

Kamon itself is not part of this fork. The modules depend on the `io.kamon` jars published on Maven Central:
`kamon-core`, `kamon-instrumentation-common`, `kamon-scala-future`, `kamon-executors` and the `kanela-agent`.

## Usage

Add the modules you need alongside `kamon-core` (or the individual Kamon modules you use), for example with sbt:

```scala
libraryDependencies ++= Seq(
  "io.kamon" %% "kamon-core" % "2.8.1",
  "com.github.pjfanning" %% "kamon-pekko" % "<version>",
  "com.github.pjfanning" %% "kamon-pekko-http" % "<version>"
)
```

These modules use the same package and class names as Kamon's own Pekko and Play modules, so don't put both on the
classpath. In particular, don't combine them with `kamon-bundle`, which already includes Kamon's versions.

## License

This software is licensed under the Apache 2 license, quoted below.

Copyright © 2013-2022 the kamon project <https://kamon.io/>

Licensed under the Apache License, Version 2.0 (the "License"); you may not
use this file except in compliance with the License. You may obtain a copy of
the License at

    [http://www.apache.org/licenses/LICENSE-2.0]

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations under
the License.
