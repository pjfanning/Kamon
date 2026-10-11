/* =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
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

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import kamon.Kamon
import kamon.instrumentation.pekko.ActorMetricsTestActor.TrackedTimings

import scala.concurrent.duration._

/**
  * Typed counterpart of ActorMetricsTestActor.
  */
object TypedActorMetricsTestActor {
  sealed trait Command
  case object Discard extends Command
  case object Fail extends Command
  case object Stop extends Command
  case object Unhandleable extends Command
  final case class Ping(replyTo: ActorRef[Pong.type]) extends Command
  final case class Block(duration: FiniteDuration) extends Command
  final case class TrackTimings(
    replyTo: ActorRef[TrackedTimings],
    sendTimestamp: Long = Kamon.clock().nanos(),
    sleep: Option[FiniteDuration] = None
  ) extends Command

  case object Pong

  def apply(): Behavior[Command] = Behaviors.receiveMessage {
    case Discard      => Behaviors.same
    case Fail         => throw new ArithmeticException("Division by zero.")
    case Stop         => Behaviors.stopped
    case Unhandleable => Behaviors.unhandled
    case Ping(replyTo) =>
      replyTo ! Pong
      Behaviors.same
    case Block(duration) =>
      Thread.sleep(duration.toMillis)
      Behaviors.same
    case TrackTimings(replyTo, sendTimestamp, sleep) =>
      val dequeueTimestamp = Kamon.clock().nanos()
      sleep.foreach(s => Thread.sleep(s.toMillis))
      val afterReceiveTimestamp = Kamon.clock().nanos()

      replyTo ! TrackedTimings(sendTimestamp, dequeueTimestamp, afterReceiveTimestamp)
      Behaviors.same
  }
}

/**
  * A typed actor that spawns a number of TypedActorMetricsTestActor children ("member-1" to "member-N") and forwards
  * messages to all of them. Stopping it stops all its members.
  */
object TypedActorMetricsTestParent {
  sealed trait Command
  final case class ToAllMembers(message: TypedActorMetricsTestActor.Command) extends Command
  case object Stop extends Command

  def apply(memberCount: Int): Behavior[Command] = Behaviors.setup { context =>
    val members = (1 to memberCount).map(id => context.spawn(TypedActorMetricsTestActor(), s"member-$id"))

    Behaviors.receiveMessage {
      case ToAllMembers(message) =>
        members.foreach(_ ! message)
        Behaviors.same
      case Stop =>
        Behaviors.stopped
    }
  }
}
