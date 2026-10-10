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

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable
import org.apache.pekko.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import kamon.instrumentation.pekko.PekkoMetrics._
import kamon.instrumentation.pekko.TypedActor._
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

  // The shared test application.conf undoes pekko-actor-typed's reference.conf settings so they don't affect the
  // classic specs, so this ActorSystem turns them back on
  private val typedConfig: Config = {
    val config = ConfigFactory.load()
    val libraryExtensions = config.getStringList("pekko.library-extensions").asScala :+
      "org.apache.pekko.actor.typed.internal.adapter.ActorSystemAdapter$LoadTypedExtensions"
    ConfigFactory.parseMap(Map[String, AnyRef](
      "pekko.library-extensions" -> libraryExtensions.asJava,
      "pekko.use-slf4j" -> java.lang.Boolean.TRUE
    ).asJava).withFallback(config)
  }

  private val system: ActorSystem[Greet] = ActorSystem(TypedActor.guardian(), "greet-service", typedConfig)

  "the Kamon typed actor metrics" should {
    "track the user guardian of a typed ActorSystem, but not the root guardian" in {
      sendMessage("world")

      // The processing time is recorded after receive returns, which can be after the reply has arrived
      eventually {
        val pathValues = ActorProcessingTime.tagValues("path")
        pathValues should contain("greet-service/user")
        pathValues should not contain ("greet-service/")
      }
    }

    "track typed actors spawned by the user guardian" in {
      sendMessage("child")

      eventually {
        ActorProcessingTime.tagValues("path") should contain("greet-service/user/child-greeter")
      }
    }
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = 5.seconds, interval = 50.millis)

  override protected def afterAll(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 20.seconds)
    super.afterAll()
  }

  private def sendMessage(name: String): Unit = {
    val reply = system.ask[Greeted](Greet(name, _))(Timeout(10.seconds), system.scheduler)
    Await.result(reply, 10.seconds)
  }
}
