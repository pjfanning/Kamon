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

This fork's `kamon-play` supports Play 3 only (Pekko-based), which requires Java 11+. For Play 2.8 and 2.9 (Akka-based), use `io.kamon:kamon-play` from the main Kamon project.

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

## Configuring actor metrics

`kamon-pekko` decides which actors get metrics using filters under `kamon.instrumentation.pekko.filters`. Each filter
has `includes` and `excludes` glob patterns, matched against the actor's path prefixed with the actor system's name.
For example, an actor at `/user/orders/order-1` in the `shop` system is matched as `shop/user/orders/order-1`. `*`
matches one path element and `**` matches any number of them. See the `kamon-pekko`
[reference.conf](instrumentation/kamon-pekko/src/main/resources/reference.conf) for every setting.

There are two ways to get actor metrics:

- **Tracked actors** (`filters.actors.track`) get their own `pekko.actor.*` metrics (processing time, time in
  mailbox, mailbox size and errors), tagged with the actor's path. Only track a small, fixed set of actors: every
  tracked actor creates its own metric series. The `**` wildcard is not allowed in `track` includes, unless you turn
  on `filters.actors.doomsday-wildcard`.
- **Actor groups** (`filters.groups.<group-name>`) get `pekko.group.*` metrics (member count, processing time, time in
  mailbox, pending messages and errors) aggregated across all the actors that match. Use groups for actors that are
  created dynamically, such as one actor per entity or per request.

### Classic actors

```hocon
kamon.instrumentation.pekko.filters {
  actors.track {
    includes = [ "shop/user/order-service", "shop/user/payment-service" ]
    excludes = [ ]
  }

  groups {
    order-workers {
      includes = [ "shop/user/order-service/worker-*" ]
    }
  }
}
```

Classic actors that are neither tracked nor in a group are **auto-grouped**: actors of the same class at the same
level of the tree share a group named after the classes along their path. For example, `Worker` children of an
`OrderService` actor at `/user/order-service` are grouped as `shop/user/OrderService/Worker`.
Turn this off with `kamon.instrumentation.pekko.auto-grouping = off`, or limit it with the
`filters.groups.auto-grouping` filter.

Classic pool routers are matched by the `filters.routers` filter, which tracks all routers by default, and get
`pekko.router.*` metrics.

### Typed actors

Typed actors are matched in the same way, by path. In a typed actor system, the behavior you pass to
`ActorSystem(behavior, "shop")` runs as the user guardian at `shop/user`, and the actors it spawns sit below it:

```scala
val system = ActorSystem(Guardian(), "shop")
// Guardian spawns context.spawn(OrderService(), "order-service"),
// which spawns context.spawn(Order(id), s"order-$id") for each order
```

```hocon
kamon.instrumentation.pekko.filters {
  actors.track {
    # The user guardian, i.e. the behavior passed to ActorSystem(...), and one of its children
    includes = [ "shop/user", "shop/user/order-service" ]
    excludes = [ ]
  }

  groups {
    orders {
      includes = [ "shop/user/order-service/order-*" ]
    }
  }
}
```

Some things work differently for typed actors:

- **Typed actors are never auto-grouped.** Pekko runs every typed actor with the same internal class, so auto-grouping
  would put unrelated actors into one group. A typed actor that is neither tracked nor in a group gets no actor
  metrics, so define groups for the typed actors you care about.
- **There's no `class` tag** on the metrics of typed actors, for the same reason.
- **Typed routers are ordinary typed actors.** `Routers.pool(...)` and `Routers.group(...)` are not Pekko routers, so
  the `filters.routers` filter and the `pekko.router.*` metrics don't apply. Track the router actor itself, or put
  its routees in a group: a pool router's routees are its children, so `"shop/user/order-service/workers/*"` matches
  the routees of a pool spawned as `workers`.
- **Actors spawned with `system.systemActorOf(...)`** live under `shop/system`, which the default `track` filter
  excludes.
- **Errors are counted however the actor is supervised.** A failure counts in `pekko.actor.errors` and
  `pekko.group.errors` whether the actor is unsupervised (and so stops) or supervised with `Behaviors.supervise` and
  any `SupervisorStrategy` (stop, resume, restart or backoff).

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
