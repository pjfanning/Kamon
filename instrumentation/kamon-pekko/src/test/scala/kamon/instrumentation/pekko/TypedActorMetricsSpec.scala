/* =========================================================================================
 * Copyright © 2013 the kamon project <http://kamon.io/>
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

package kamon.instrumentation.pekko

import org.apache.pekko.actor.typed.{
  ActorRef,
  ActorSystem,
  Behavior,
  DispatcherSelector,
  Props,
  SpawnProtocol,
  SupervisorStrategy
}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import kamon.instrumentation.pekko.ActorMetricsTestActor.TrackedTimings
import kamon.instrumentation.pekko.PekkoMetrics._
import kamon.instrumentation.pekko.TypedActorMetricsTestActor._
import kamon.instrumentation.pekko.TypedActorMetricsTestParent.ToAllMembers
import kamon.metric.Metric
import kamon.tag.{Lookups, TagSet}
import kamon.testkit.{InitAndStopKamonAfterAll, InstrumentInspection, MetricInspection}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class TypedActorMetricsSpec extends AnyWordSpec
    with MetricInspection.Syntax with InstrumentInspection.Syntax with Matchers
    with Eventually with InitAndStopKamonAfterAll {

  private val SystemName = "TypedActorMetricsSpec"

  // The shared test application.conf undoes pekko-actor-typed's reference.conf settings so they don't affect the
  // classic specs, so this ActorSystem turns them back on. Typed messages carry Behaviors and typed ActorRefs, so
  // serialize-messages is turned off too.
  private val typedConfig: Config = {
    val config = ConfigFactory.load()
    val libraryExtensions = config.getStringList("pekko.library-extensions").asScala :+
      "org.apache.pekko.actor.typed.internal.adapter.ActorSystemAdapter$LoadTypedExtensions"
    ConfigFactory.parseMap(Map[String, AnyRef](
      "pekko.library-extensions" -> libraryExtensions.asJava,
      "pekko.use-slf4j" -> java.lang.Boolean.TRUE,
      "pekko.actor.serialize-messages" -> java.lang.Boolean.FALSE
    ).asJava).withFallback(config)
  }

  private val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), SystemName, typedConfig)
  private val systemMetrics = PekkoMetrics.forSystem(SystemName)

  "the Kamon typed actor metrics" should {
    "track the user guardian of a typed ActorSystem, but not the root guardian" in {
      spawn(TypedActorMetricsTestActor(), "spawned-to-wake-up-the-guardian")

      // The processing time is recorded after receive returns, which can be after the reply has arrived
      eventually {
        val pathValues = ActorProcessingTime.tagValues("path")
        pathValues should contain(s"$SystemName/user")
        pathValues should not contain (s"$SystemName/")
      }
    }

    "track typed actors spawned by the user guardian" in {
      spawnAndPing("tracked-typed-actor")

      eventually {
        ActorProcessingTime.tagValues("path") should contain(s"$SystemName/user/tracked-typed-actor")
      }
    }

    "respect the configured include and exclude filters" in {
      spawnAndPing("tracked-typed-filtered")
      spawnAndPing("non-tracked-actor")
      spawnAndPing("tracked-explicitly-excluded")

      eventually {
        ActorProcessingTime.tagValues("path") should contain(s"$SystemName/user/tracked-typed-filtered")
      }
      ActorProcessingTime.tagValues("path") shouldNot contain(s"$SystemName/user/non-tracked-actor")
      ActorProcessingTime.tagValues("path") shouldNot contain(s"$SystemName/user/tracked-explicitly-excluded")
    }

    "tag typed actor metrics with the path, system and dispatcher, but not the class" in {
      spawnAndPing("tracked-typed-tags")

      eventually {
        val tagSets = ActorProcessingTime.instruments(TagSet.of("path", s"$SystemName/user/tracked-typed-tags")).keys
        tagSets should have size 1
        tagSets.head shouldBe actorTags("tracked-typed-tags")
        tagSets.head.get(Lookups.option("class")) shouldBe empty
      }

      // The user guardian is typed too
      eventually {
        val guardianTagSets = ActorProcessingTime.instruments(TagSet.of("path", s"$SystemName/user")).keys
        guardianTagSets.map(_.get(Lookups.option("class"))) should contain only (None)
      }
    }

    "tag typed actor metrics with the dispatcher picked by the DispatcherSelector" in {
      spawnAndPing("tracked-typed-on-fjp", props = DispatcherSelector.fromConfig("tracked-fjp"))

      eventually {
        ActorProcessingTime.instruments(TagSet.of("path", s"$SystemName/user/tracked-typed-on-fjp")).keys
          .map(_.get(Lookups.plain("dispatcher"))) should contain only ("tracked-fjp")
      }
    }

    "record the processing-time of the behavior" in {
      val actor = spawnAndPing("measuring-typed-processing-time", resetState = true)
      val probe = TestProbe()(system.classicSystem)
      actor ! TrackTimings(probe.ref.toTyped[TrackedTimings], sleep = Some(100.millis))

      val timings = probe.expectMsgType[TrackedTimings](10.seconds)

      // The processing time is recorded after receive returns, which can be after the reply has arrived
      eventually {
        val processingTimeDistribution = ActorProcessingTime
          .withTags(actorTags("measuring-typed-processing-time"))
          .distribution(resetState = false)

        processingTimeDistribution.count should be(1L)
        processingTimeDistribution.buckets.size should be(1L)
        processingTimeDistribution.buckets.head.value should be(timings.approximateProcessingTime +- 10.millis.toNanos)
      }
    }

    "record the mailbox-size" in {
      val actor = spawnAndPing("measuring-typed-mailbox-size", resetState = true)
      val probe = TestProbe()(system.classicSystem)
      actor ! TrackTimings(probe.ref.toTyped[TrackedTimings], sleep = Some(1.second))
      (1 to 10).foreach(_ => actor ! Discard)
      actor ! Ping(probe.ref.toTyped[Pong.type])

      probe.expectMsgType[TrackedTimings](10.seconds)
      probe.expectMsg(10.seconds, Pong)

      val mailboxSizeDistribution = ActorMailboxSize.withTags(actorTags("measuring-typed-mailbox-size")).distribution()

      mailboxSizeDistribution.min should be(0L +- 1L)
      mailboxSizeDistribution.max should be(11L +- 1L)
    }

    "record the time-in-mailbox" in {
      val actor = spawnAndPing("measuring-typed-time-in-mailbox", resetState = true)
      val probe = TestProbe()(system.classicSystem)
      actor ! TrackTimings(probe.ref.toTyped[TrackedTimings], sleep = Some(100.millis))
      val timings = probe.expectMsgType[TrackedTimings](10.seconds)

      // The time-in-mailbox is recorded after receive returns, which can be after the reply has arrived
      eventually {
        val timeInMailboxDistribution = ActorTimeInMailbox
          .withTags(actorTags("measuring-typed-time-in-mailbox"))
          .distribution(resetState = false)

        timeInMailboxDistribution.count should be(1L)
        timeInMailboxDistribution.buckets.head.frequency should be(1L)
        timeInMailboxDistribution.buckets.head.value should be(timings.approximateTimeInMailbox +- 10.millis.toNanos)
      }
    }

    "clean up the associated recorder when a typed actor stops" in {
      val actor = spawnAndPing("tracked-typed-stop")

      eventually {
        ActorProcessingTime.tagValues("path") should contain(s"$SystemName/user/tracked-typed-stop")
      }

      stopAndAwaitTermination(actor, Stop)

      eventually {
        ActorProcessingTime.tagValues("path") shouldNot contain(s"$SystemName/user/tracked-typed-stop")
      }
    }

    "not auto-group typed actors that aren't tracked" in {
      val parent = spawn(TypedActorMetricsTestParent(3), "untracked-typed-parent")
      pingAllMembers(parent, 3)

      GroupMembers.tagValues("group").filter(_.startsWith(SystemName)) shouldBe empty
      ActorProcessingTime.tagValues("path").filter(_.contains("untracked-typed-parent")) shouldBe empty

      stopAndAwaitTermination(parent, TypedActorMetricsTestParent.Stop)
    }
  }

  "the Kamon actor system metrics for a typed ActorSystem" should {
    "record active actor counts for spawned and stopped typed actors" in {
      // TestProbes are actors too (and aren't stopped), so the same one is used throughout. Its actor starts
      // asynchronously and is only counted once it has, so wait for it to process a message before the baseline.
      val probe = TestProbe()(system.classicSystem)
      probe.ref ! "started"
      probe.expectMsg(10.seconds, "started")

      val baseActorCount = eventually {
        val activeActors = systemMetrics.activeActors.distribution()
        activeActors.count should be > 0L
        activeActors.min shouldBe activeActors.max
        activeActors.max
      }

      // A parent and its three members
      val parent = spawn(TypedActorMetricsTestParent(3), "typed-active-actors")
      pingAllMembers(parent, 3, probe)

      eventually {
        val activeActors = systemMetrics.activeActors.distribution()
        activeActors.count should be > 0L
        activeActors.min shouldBe (baseActorCount + 4)
        activeActors.max shouldBe (baseActorCount + 4)
      }

      stopAndAwaitTermination(parent, TypedActorMetricsTestParent.Stop, probe)

      eventually {
        val activeActors = systemMetrics.activeActors.distribution()
        activeActors.count should be > 0L
        activeActors.min shouldBe baseActorCount
        activeActors.max shouldBe baseActorCount
      }
    }

    "record dead letters sent to stopped typed actors" in {
      val doa = spawnAndPing("typed-doa")
      stopAndAwaitTermination(doa, Stop)
      systemMetrics.deadLetters.value(true)

      (1 to 7).foreach(_ => doa ! Discard)

      eventually {
        systemMetrics.deadLetters.value(false).toInt should be(7)
      }
    }

    "record messages left unhandled by typed behaviors" in {
      val actor = spawnAndPing("typed-unhandled")
      systemMetrics.unhandledMessages.value(true)

      (1 to 10).foreach(_ => actor ! Unhandleable)

      eventually {
        systemMetrics.unhandledMessages.value(false).toInt should be(10)
      }
    }

    "record processed messages counts for tracked and non-tracked typed actors" in {
      val tracked = spawnAndPing("tracked-typed-counts")
      val nonTracked = spawnAndPing("non-tracked-typed-counts")
      systemMetrics.processedMessagesByTracked.value(true)
      systemMetrics.processedMessagesByNonTracked.value(true)

      (1 to 10).foreach(_ => tracked ! Discard)
      (1 to 15).foreach(_ => nonTracked ! Discard)

      eventually {
        systemMetrics.processedMessagesByTracked.value(false) should be >= 10L
        systemMetrics.processedMessagesByNonTracked.value(false) should be >= 15L
      }
    }
  }

  "the Kamon actor-group metrics for typed actors" should {
    "increase and decrease the member count as typed actors matching the group are spawned and stopped" in {
      val firstParent = spawn(TypedActorMetricsTestParent(5), "typed-members-1")
      pingAllMembers(firstParent, 5)

      eventually {
        GroupMembers.withTags(groupTags("typed-group-members")).distribution().max shouldBe 5
      }

      val secondParent = spawn(TypedActorMetricsTestParent(5), "typed-members-2")
      pingAllMembers(secondParent, 5)

      eventually {
        GroupMembers.withTags(groupTags("typed-group-members")).distribution().max shouldBe 10
      }

      stopAndAwaitTermination(firstParent, TypedActorMetricsTestParent.Stop)
      stopAndAwaitTermination(secondParent, TypedActorMetricsTestParent.Stop)

      eventually {
        GroupMembers.withTags(groupTags("typed-group-members")).distribution().max shouldBe 0
      }
    }

    "record the processing-time and time-in-mailbox of the members" in {
      val parent = spawn(TypedActorMetricsTestParent(3), "typed-timings")
      pingAllMembers(parent, 3)

      val tags = groupTags("typed-group-timings")
      eventually {
        GroupProcessingTime.withTags(tags).distribution(resetState = false).count should be >= 3L
      }
      GroupProcessingTime.withTags(tags).distribution(resetState = true)
      GroupTimeInMailbox.withTags(tags).distribution(resetState = true)

      val probe = TestProbe()(system.classicSystem)
      parent ! ToAllMembers(TrackTimings(probe.ref.toTyped[TrackedTimings], sleep = Some(100.millis)))
      val timings = probe.receiveN(3, 10.seconds).map(_.asInstanceOf[TrackedTimings])

      eventually {
        val processingTime = GroupProcessingTime.withTags(tags).distribution(resetState = false)
        processingTime.count shouldBe 3L
        processingTime.min should be(timings.map(_.approximateProcessingTime).min +- 10.millis.toNanos)
        processingTime.max should be(timings.map(_.approximateProcessingTime).max +- 10.millis.toNanos)

        GroupTimeInMailbox.withTags(tags).distribution(resetState = false).count shouldBe 3L
      }

      stopAndAwaitTermination(parent, TypedActorMetricsTestParent.Stop)
    }

    "record the pending messages of the members and clean them up when the members stop" in {
      val parent = spawn(TypedActorMetricsTestParent(5), "typed-pending")
      pingAllMembers(parent, 5)

      (1 to 1000).foreach(_ => parent ! ToAllMembers(Block(1.millis)))

      eventually {
        GroupPendingMessages.withTags(groupTags("typed-group-pending")).distribution().max should be > 0L
      }

      stopAndAwaitTermination(parent, TypedActorMetricsTestParent.Stop)

      eventually {
        val pendingMessagesDistribution = GroupPendingMessages.withTags(groupTags("typed-group-pending")).distribution()
        pendingMessagesDistribution.count should be > 0L
        pendingMessagesDistribution.max shouldBe 0L
      }
    }

    "count the errors of the members, which typed supervision stops by default" in {
      val parent = spawn(TypedActorMetricsTestParent(3), "typed-errors")
      pingAllMembers(parent, 3)

      eventually {
        GroupMembers.withTags(groupTags("typed-group-errors")).distribution().max shouldBe 3
      }

      parent ! ToAllMembers(Fail)

      eventually {
        GroupErrors.withTags(groupTags("typed-group-errors")).value(resetState = false) shouldBe 3L
      }
      eventually {
        GroupMembers.withTags(groupTags("typed-group-errors")).distribution().max shouldBe 0
      }

      stopAndAwaitTermination(parent, TypedActorMetricsTestParent.Stop)
    }

    "count the errors of supervised members that are restarted" in {
      val parent = spawn(TypedActorMetricsTestParent(3, restarting), "typed-supervised-errors")
      pingAllMembers(parent, 3)

      parent ! ToAllMembers(Fail)
      parent ! ToAllMembers(Fail)
      pingAllMembers(parent, 3)

      eventually {
        GroupErrors.withTags(groupTags("typed-group-supervised-errors")).value(resetState = false) shouldBe 6L
      }
      GroupMembers.withTags(groupTags("typed-group-supervised-errors")).distribution().max shouldBe 3

      stopAndAwaitTermination(parent, TypedActorMetricsTestParent.Stop)
    }
  }

  "the Kamon actor errors metric for typed actors" should {
    "count the failure of an unsupervised typed actor, which stops it" in {
      val actor = spawnAndPing("tracked-typed-unsupervised-failure")
      stopAndAwaitTermination(actor, Fail)

      // The stopped actor's instruments are only removed once the next snapshot has reported them
      reportedErrors("tracked-typed-unsupervised-failure") shouldBe 1L
    }

    "count the failure a stop supervisor stops a typed actor for once" in {
      val actor = spawnAndPing(
        "tracked-typed-stop-supervised-failure",
        Behaviors.supervise(TypedActorMetricsTestActor()).onFailure[Exception](SupervisorStrategy.stop)
      )
      stopAndAwaitTermination(actor, Fail)

      reportedErrors("tracked-typed-stop-supervised-failure") shouldBe 1L
    }

    "count the failures a restart supervisor restarts a typed actor for" in {
      assertErrorsCountedWhileAlive("tracked-typed-restart-supervised-failures", restarting)
    }

    "count the failures a restart supervisor restarts a typed actor for when its logging is disabled" in {
      assertErrorsCountedWhileAlive(
        "tracked-typed-restart-supervised-quiet-failures",
        Behaviors.supervise(_).onFailure[Exception](SupervisorStrategy.restart.withLoggingEnabled(false))
      )
    }

    "count the failures a resume supervisor resumes a typed actor for" in {
      assertErrorsCountedWhileAlive(
        "tracked-typed-resume-supervised-failures",
        Behaviors.supervise(_).onFailure[Exception](SupervisorStrategy.resume)
      )
    }

    "count the failures a backoff supervisor restarts a typed actor for" in {
      assertErrorsCountedWhileAlive(
        "tracked-typed-backoff-supervised-failures",
        Behaviors.supervise(_).onFailure[Exception](
          SupervisorStrategy.restartWithBackoff(10.millis, 50.millis, randomFactor = 0.0)
        )
      )
    }

    "count every failure when a restart supervisor reaches its restart limit and stops the typed actor" in {
      val actor = spawnAndPing(
        "tracked-typed-restart-limit-failures",
        Behaviors.supervise(TypedActorMetricsTestActor())
          .onFailure[Exception](SupervisorStrategy.restart.withLimit(2, 1.minute))
      )
      actor ! Fail
      actor ! Fail
      stopAndAwaitTermination(actor, Fail)

      reportedErrors("tracked-typed-restart-limit-failures") shouldBe 3L
    }
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = 5.seconds, interval = 50.millis)

  override protected def afterAll(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 20.seconds)
    super.afterAll()
  }

  private def actorTags(name: String): TagSet =
    TagSet.from(
      Map(
        "path" -> s"$SystemName/user/$name",
        "system" -> SystemName,
        "dispatcher" -> "pekko.actor.default-dispatcher"
      )
    )

  private def groupTags(group: String): TagSet =
    TagSet.from(
      Map(
        "system" -> SystemName,
        "group" -> group
      )
    )

  private def spawn[T](behavior: Behavior[T], name: String, props: Props = Props.empty): ActorRef[T] = {
    val spawned = system.ask[ActorRef[T]](SpawnProtocol.Spawn(behavior, name, props, _))(
      Timeout(10.seconds),
      system.scheduler
    )
    Await.result(spawned, 10.seconds)
  }

  /**
    * Spawns a TypedActorMetricsTestActor and waits for it to process a Ping, optionally resetting its metrics after.
    */
  private def spawnAndPing(
    name: String,
    behavior: Behavior[TypedActorMetricsTestActor.Command] = TypedActorMetricsTestActor(),
    props: Props = Props.empty,
    resetState: Boolean = false
  ): ActorRef[TypedActorMetricsTestActor.Command] = {
    val actor = spawn(behavior, name, props)
    val probe = TestProbe()(system.classicSystem)
    actor ! Ping(probe.ref.toTyped[Pong.type])
    probe.expectMsg(10.seconds, Pong)

    if (resetState) {
      val tags = actorTags(name)

      // The Ping's processing time and time-in-mailbox are recorded after the behavior returns, which can be after
      // the Pong has arrived. Wait for them, or they could be recorded after the reset below.
      eventually {
        ActorTimeInMailbox.withTags(tags).distribution(resetState = false).count should be >= 1L
      }

      ActorTimeInMailbox.withTags(tags).distribution(resetState = true)
      ActorProcessingTime.withTags(tags).distribution(resetState = true)
      ActorMailboxSize.withTags(tags).distribution(resetState = true)
      ActorErrors.withTags(tags).value(resetState = true)
    }

    actor
  }

  private def restarting(behavior: Behavior[TypedActorMetricsTestActor.Command])
    : Behavior[TypedActorMetricsTestActor.Command] =
    Behaviors.supervise(behavior).onFailure[Exception](SupervisorStrategy.restart)

  /**
    * Fails a tracked typed actor three times, with the supervision added by the given function, and checks that the
    * failures are counted and the actor is still alive and tracked.
    */
  private def assertErrorsCountedWhileAlive(
    name: String,
    supervise: Behavior[TypedActorMetricsTestActor.Command] => Behavior[TypedActorMetricsTestActor.Command]
  ): Unit = {
    val actor = spawnAndPing(name, supervise(TypedActorMetricsTestActor()))
    (1 to 3).foreach(_ => actor ! Fail)

    val probe = TestProbe()(system.classicSystem)
    actor ! Ping(probe.ref.toTyped[Pong.type])
    probe.expectMsg(10.seconds, Pong)

    eventually {
      ActorErrors.withTags(actorTags(name)).value(resetState = false) shouldBe 3L
    }
    ActorProcessingTime.tagValues("path") should contain(s"$SystemName/user/$name")
  }

  /**
    * The errors reported for a typed actor, including those of a stopped actor whose instruments are flagged for
    * removal (which the MetricInspection syntax doesn't show) but are still reported by the next snapshot.
    */
  private def reportedErrors(name: String): Long =
    ActorErrors.asInstanceOf[Metric.Snapshotting[Metric.Settings.ForValueInstrument, Long]]
      .snapshot(resetState = false)
      .instruments
      .filter(_.tags.get(Lookups.option("path")).contains(s"$SystemName/user/$name"))
      .map(_.value)
      .sum

  private def pingAllMembers(
    parent: ActorRef[TypedActorMetricsTestParent.Command],
    memberCount: Int,
    probe: TestProbe = TestProbe()(system.classicSystem)
  ): Unit = {
    parent ! ToAllMembers(Ping(probe.ref.toTyped[Pong.type]))
    probe.receiveN(memberCount, 10.seconds)
  }

  private def stopAndAwaitTermination[T](
    actor: ActorRef[T],
    stopMessage: T,
    probe: TestProbe = TestProbe()(system.classicSystem)
  ): Unit = {
    probe.watch(actor.toClassic)
    actor ! stopMessage
    probe.expectTerminated(actor.toClassic, 10.seconds)
  }
}
