/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.channel.states.e

import akka.actor.ActorRef
import akka.testkit.TestProbe
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee._
import fr.acinq.eclair.channel.Helpers.Closing.{LocalClose, RemoteClose}
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.FullySignedSharedTransaction
import fr.acinq.eclair.channel.publish.TxPublisher.PublishFinalTx
import fr.acinq.eclair.channel.states.ChannelStateTestsTags.NoMaxHtlcValueInFlight
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

/**
 * Created by PM on 05/07/2016.
 */

class NormalSplicesStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val tags = test.tags + ChannelStateTestsTags.DualFunding
    val nodeParamsB = TestConstants.Bob.nodeParams
      // we can't simply .modify(_.onChainFeeConf.defaultFeerateTolerance) because FeerateTolerance has private members
      .modify(_.onChainFeeConf).usingIf(test.tags.contains("small_channel"))(oc => OnChainFeeConf(
      feeTargets = oc.feeTargets, feeEstimator = oc.feeEstimator, spendAnchorWithoutHtlcs = oc.spendAnchorWithoutHtlcs, closeOnOfflineMismatch = oc.closeOnOfflineMismatch, updateFeeMinDiffRatio = oc.updateFeeMinDiffRatio, defaultFeerateTolerance = FeerateTolerance(0.01, 100.0, TestConstants.anchorOutputsFeeratePerKw, DustTolerance(25_000 sat, closeOnUpdateFeeOverflow = true)), perNodeFeerateTolerance = Map.empty
    ))
    val setup = init(nodeParamsB = nodeParamsB, tags = tags)
    import setup._
    reachNormal(setup, tags)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    withFixture(test.toNoArgTest(setup))
  }

  def initiateSplice(f: FixtureParam, spliceIn_opt: Option[SpliceIn] = None, spliceOut_opt: Option[SpliceOut] = None) = {
    import f._

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt, spliceOut_opt)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    sender.expectMsgType[RES_SUCCESS[CMD_SPLICE]]

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    if (spliceIn_opt.isDefined) {
      alice2bob.expectMsgType[TxAddInput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
    }
    if (spliceOut_opt.isDefined) {
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
    }
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.isInstanceOf[FullySignedSharedTransaction])
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.isInstanceOf[FullySignedSharedTransaction])
  }

  test("recv CMD_SPLICE (splice-in)") { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.remoteCommit.spec.toLocal == 700_000_000.msat)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.capacity == 2_000_000.sat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 1_300_000_000.msat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.remoteCommit.spec.toLocal == 700_000_000.msat)
  }

  test("recv CMD_SPLICE (splice-out)") { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.remoteCommit.spec.toLocal == 700_000_000.msat)

    initiateSplice(f, spliceOut_opt = Some(SpliceOut(100_000 sat, ByteVector32.Zeroes)))

//    val feerate = TestConstants.Alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(TestConstants.Alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
//    val miningFee = Transactions.weight2fee(feerate, 600)
//    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.capacity == 1_400_000.sat - miningFee)
//    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 700_000_000.msat - miningFee)
//    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.remoteCommit.spec.toLocal == 700_000_000.msat)
  }

  test("recv WatchFundingConfirmedTriggered on splice tx", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val sender = TestProbe()
    // command for a large payment (larger than local balance pre-slice)
    val cmd = CMD_ADD_HTLC(sender.ref, 1_000_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    // first attempt at payment fails (not enough balance)
    alice ! cmd
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage()

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2blockchain.expectNoMessage()
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)

    // 2nd attempt works!
    alice ! cmd
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
  }

  test("recv WatchFundingConfirmedTriggered with two unconfirmed splice txs", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val watchConfirmed1 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2blockchain.expectNoMessage()
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    assert(fundingTx1.txid == watchConfirmed1.txId)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    // we're not putting a watch confirm immediately for the second watch
    alice2blockchain.expectNoMessage()
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get

    // splice 1 confirms
    watchConfirmed1.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    // watch for splice 2 is set
    val watchConfirmed2 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    assert(fundingTx2.txid == watchConfirmed2.txId)

    // splice 2 confirms
    watchConfirmed2.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
  }

  test("recv CMD_ADD_HTLC with multiple commitments") { f =>
    import f._
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
  }

  test("recv CMD_ADD_HTLC while a splice is requested") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage()
  }

  test("recv CMD_ADD_HTLC while a splice is in progress") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    sender.expectMsgType[RES_SUCCESS[CMD_SPLICE]]
    alice2bob.expectMsgType[TxAddInput]
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage()
  }

  test("recv UpdateAddHtlc while a splice is requested") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    // we're not forwarding the splice_init to create a race

    val (_, cmdAdd: CMD_ADD_HTLC) = makeCmdAdd(5_000_000 msat, bob.underlyingActor.remoteNodeId, bob.underlyingActor.nodeParams.currentBlockHeight)
    bob ! cmdAdd
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob)
  }

  test("recv UpdateAddHtlc while a splice is in progress") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    sender.expectMsgType[RES_SUCCESS[CMD_SPLICE]]
    alice2bob.expectMsgType[TxAddInput]

    // have to build a htlc manually because eclair would refuse to accept this command as it's forbidden
    val fakeHtlc = UpdateAddHtlc(channelId = randomBytes32(), id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentHash = randomBytes32(), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    bob2alice.forward(alice, fakeHtlc)
    alice2bob.expectMsgType[Error]
  }

  test("re-send splice_locked on reconnection") { f =>
    import f._

    def disconnect() = {
      alice ! INPUT_DISCONNECTED
      bob ! INPUT_DISCONNECTED
      awaitCond(alice.stateName == OFFLINE)
      awaitCond(bob.stateName == OFFLINE)
    }

    def reconnect() = {
      val aliceInit = Init(alice.stateData.asInstanceOf[PersistentChannelData].commitments.params.localParams.initFeatures)
      val bobInit = Init(bob.stateData.asInstanceOf[PersistentChannelData].commitments.params.localParams.initFeatures)
      alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
      bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
      alice2bob.expectMsgType[ChannelReestablish]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[ChannelReestablish]
      bob2alice.forward(alice)

      alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
      bob2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    }

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed1a = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    val watchConfirmed1b = bob2blockchain.expectMsgType[WatchFundingConfirmed]
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    // we now have two unconfirmed splices

    disconnect()
    reconnect()

    alice2bob.expectMsgType[ChannelUpdate]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ChannelUpdate]
    bob2alice.forward(bob)

    // channel_ready are not re-sent because the channel has already been used (for building splices)
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

    // splice 1 confirms on alice's side
    watchConfirmed1a.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    val watchConfirmed2 = alice2blockchain.expectMsgType[WatchFundingConfirmed]

    alice2bob.ignoreMsg { case _: ChannelUpdate => true }
    bob2alice.ignoreMsg { case _: ChannelUpdate => true }

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)

    // splice 2 confirms on alice's side
    watchConfirmed2.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

    // splice 1 confirms on bob's side
    watchConfirmed1b.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    bob2alice.forward(alice)
    bob2blockchain.expectMsgType[WatchFundingSpent]
    val watchConfirmed2b = bob2blockchain.expectMsgType[WatchFundingConfirmed]

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

    // splice 2 confirms on bob's side
    watchConfirmed2b.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    bob2blockchain.expectMsgType[WatchFundingSpent]

    // NB: we disconnect *before* transmitting the splice_confirmed to alice

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    // this time alice received the splice_confirmed for funding tx 2
    bob2alice.forward(alice)
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

    disconnect()
    reconnect()

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()

  }

  test("force-close with multiple splices (simple)") { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed1 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    alice2blockchain.expectNoMessage()
    // we now have two unconfirmed splices

    alice ! CMD_FORCECLOSE(ActorRef.noSender)
    alice2bob.expectMsgType[Error]
    val commitTx2 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val claimMainDelayed2 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val watchConfirmedCommit2 = alice2blockchain.expectMsgType[WatchTxConfirmed]
    val watchConfirmedClaimMainDelayed2 = alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice ! WatchFundingSpentTriggered(commitTx2)
    alice2blockchain.expectNoMessage()

    // splice 1 confirms
    watchConfirmed1.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    val watchConfirmed2 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2blockchain.expectNoMessage()

    // splice 2 confirms
    watchConfirmed2.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    alice2blockchain.expectNoMessage()

    // commit tx confirms
    watchConfirmedCommit2.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, commitTx2)

    // claim-main-delayed tx confirms
    watchConfirmedClaimMainDelayed2.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMainDelayed2)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[LocalClose]))
  }

  test("force-close with multiple splices (previous remote wins)") { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed1 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2bob.expectNoMessage()
    bob2alice.expectNoMessage()
    alice2blockchain.expectNoMessage()
    // we now have two unconfirmed splices

    alice ! CMD_FORCECLOSE(ActorRef.noSender)
    alice2bob.expectMsgType[Error]
    val aliceCommitTx2 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val claimMainDelayed2 = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val watchConfirmedCommit2 = alice2blockchain.expectMsgType[WatchTxConfirmed]
    val watchConfirmedClaimMainDelayed2 = alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice ! WatchFundingSpentTriggered(aliceCommitTx2)
    alice2blockchain.expectNoMessage()

    // splice 1 confirms
    watchConfirmed1.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    val watchConfirmed2 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2blockchain.expectNoMessage()

    // oops! remote commit for splice 1 is published
    val bobCommitTx1 = bob.stateData.asInstanceOf[PersistentChannelData].commitments.active.find(_.fundingTxIndex == 1).get.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx1)
    val watchAlternativeConfirmed = alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed]
    alice2blockchain.expectNoMessage()

    // remote commit tx confirms
    watchAlternativeConfirmed.replyTo ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)

    // we're back to the normal handling of remote commit
    val claimMain = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val watchConfirmedRemoteCommit = alice2blockchain.expectMsgType[WatchTxConfirmed]
    assert(watchConfirmedRemoteCommit.txId == bobCommitTx1.txid)
    // this one fires immediately, tx is already confirmed
    watchConfirmedRemoteCommit.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)
    val watchConfirmedClaimMain = alice2blockchain.expectMsgType[WatchTxConfirmed]

    // claim-main tx confirms
    watchConfirmedClaimMain.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMain)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RemoteClose]))
  }


}
