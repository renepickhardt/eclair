/*
 * Copyright 2023 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.reputation

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.reputation.Reputation.ReputationConfig
import fr.acinq.eclair.reputation.ReputationRecorder._
import fr.acinq.eclair.{MilliSatoshiLong, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import java.util.UUID
import scala.concurrent.duration.DurationInt

class ReputationRecorderSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val (uuid1, uuid2, uuid3, uuid4, uuid5, uuid6, uuid7, uuid8) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
  val originNode: PublicKey = randomKey().publicKey

  case class FixtureParam(config: ReputationConfig, reputationRecorder: ActorRef[Command], replyTo: TestProbe[Confidence])

  override def withFixture(test: OneArgTest): Outcome = {
    val config = ReputationConfig(1000000000 msat, 10 seconds)
    val replyTo = TestProbe[Confidence]("confidence")
    val reputationRecorder = testKit.spawn(ReputationRecorder(config, Map.empty))
    withFixture(test.toNoArgTest(FixtureParam(config, reputationRecorder.ref, replyTo)))
  }

  test("standard") { f =>
    import f._

    reputationRecorder ! GetConfidence(replyTo.ref, originNode, isEndorsed = true, uuid1, 1100 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    reputationRecorder ! RecordResult(originNode, isEndorsed = true, uuid1, isSuccess = true)
    reputationRecorder ! GetConfidence(replyTo.ref, originNode, isEndorsed = true, uuid2, 1100 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0.5)
    reputationRecorder ! GetConfidence(replyTo.ref, originNode, isEndorsed = true, uuid3, 1100 msat)
    assert(replyTo.expectMessageType[Confidence].value === 0.333 +- 0.001)
    reputationRecorder ! CancelRelay(originNode, isEndorsed = true, uuid3)
    reputationRecorder ! GetConfidence(replyTo.ref, originNode, isEndorsed = true, uuid4, 1100 msat)
    assert(replyTo.expectMessageType[Confidence].value === 0.333 +- 0.001)
    reputationRecorder ! RecordResult(originNode, isEndorsed = true, uuid4, isSuccess = true)
    reputationRecorder ! RecordResult(originNode, isEndorsed = true, uuid2, isSuccess = false)
    // Not endorsed
    reputationRecorder ! GetConfidence(replyTo.ref, originNode, isEndorsed = false, uuid5, 1100 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    // Different origin node
    reputationRecorder ! GetConfidence(replyTo.ref, randomKey().publicKey, isEndorsed = true, uuid6, 1100 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    // Very large HTLC
    reputationRecorder ! GetConfidence(replyTo.ref, originNode, isEndorsed = true, uuid5, 10000000 msat)
    assert(replyTo.expectMessageType[Confidence].value === 0.0 +- 0.001)
  }

  test("trampoline") { f =>
    import f._

    val (a, b, c) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)

    reputationRecorder ! GetTrampolineConfidence(replyTo.ref, Map((a, true) -> 1000.msat, (b, true) -> 2000.msat, (c, false) -> 3000.msat), uuid1)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    reputationRecorder ! RecordTrampolineSuccess(Map((a, true) -> 500.msat, (b, true) -> 1000.msat, (c, false) -> 1500.msat), uuid1)
    reputationRecorder ! GetTrampolineConfidence(replyTo.ref, Map((a, true) -> 1000.msat, (c, false) -> 1000.msat), uuid2)
    assert(replyTo.expectMessageType[Confidence].value === 0.333 +- 0.001)
    reputationRecorder ! GetTrampolineConfidence(replyTo.ref, Map((a, false) -> 1000.msat, (b, true) -> 2000.msat), uuid3)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    reputationRecorder ! RecordTrampolineFailure(Set((a, true), (c, false)), uuid2)
    reputationRecorder ! RecordTrampolineSuccess(Map((a, false) -> 1000.msat, (b, true) -> 2000.msat), uuid3)

    reputationRecorder ! GetConfidence(replyTo.ref, a, isEndorsed = true, uuid4, 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === 0.2 +- 0.001)
    reputationRecorder ! GetConfidence(replyTo.ref, a, isEndorsed = false, uuid5, 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === 0.5 +- 0.001)
    reputationRecorder ! GetConfidence(replyTo.ref, b, isEndorsed = true, uuid6, 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === 0.75 +- 0.001)
    reputationRecorder ! GetConfidence(replyTo.ref, b, isEndorsed = false, uuid7, 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0.0)
    reputationRecorder ! GetConfidence(replyTo.ref, c, isEndorsed = false, uuid8, 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === (3.0 / 7) +- 0.001)
  }
}