/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair

import akka.actor.typed.ActorRef
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.channel.{Commitments, Origin}
import fr.acinq.eclair.io.OpenChannelInterceptor.DefaultParams
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner.IncomingHtlc
import fr.acinq.eclair.wire.protocol._

/** Custom plugin parameters. */
trait PluginParams {
  /** Plugin's friendly name. */
  def name: String
}

/** Parameters for a plugin that adds support for an experimental or unofficial Bolt9 feature. */
trait CustomFeaturePlugin extends PluginParams {
  /** A set of LightningMessage tags that the plugin wants to listen to. */
  def messageTags: Set[Int]

  /** Feature bit that the plugin wants to advertise through Init message. */
  def feature: Feature

  /** Plugin feature is always defined as unknown and optional. */
  def pluginFeature: UnknownFeature = UnknownFeature(feature.optional)
}

/** Parameters for a plugin that defines custom commitment transactions (or non-standard HTLCs). */
trait CustomCommitmentsPlugin extends PluginParams {
  /**
   * If we do nothing after a restart, incoming HTLCs that were committed upstream but not relayed will eventually
   * expire. If your plugin defines non-standard HTLCs, and they need to be automatically failed, they should be
   * returned by this method.
   */
  def getIncomingHtlcs(nodeParams: NodeParams, log: LoggingAdapter): Seq[IncomingHtlc]

  /**
   * Outgoing HTLC sets that are still pending may either succeed or fail: we need to watch them to properly forward the
   * result upstream to preserve channels. If you have non-standard HTLCs that may be in this situation, they should be
   * returned by this method.
   */
  def getHtlcsRelayedOut(htlcsIn: Seq[IncomingHtlc], nodeParams: NodeParams, log: LoggingAdapter): Map[Origin, Set[(ByteVector32, Long)]]
}

/**
 * Plugins implementing this trait can intercept funding attempts initiated by a remote peer:
 *  - new channel creation
 *  - splicing on an existing channel
 *  - RBF attempt (on new channel creation or splice)
 *
 * Plugins can either accept or reject the funding attempt, and decide to contribute some funds.
 */
trait InterceptChannelFundingPlugin extends PluginParams {
  def channelFundingInterceptor: ActorRef[InterceptChannelFundingPlugin.Command]
}

object InterceptChannelFundingPlugin {
  /** Details about the remote peer. */
  case class PeerDetails(nodeId: PublicKey, features: Features[InitFeature], address: NodeAddress)

  // @formatter:off
  sealed trait Command {
    def peer: PeerDetails
  }
  case class InterceptOpenChannelAttempt(replyTo: ActorRef[InterceptOpenChannelResponse], peer: PeerDetails, open: Either[OpenChannel, OpenDualFundedChannel], defaultParams: DefaultParams) extends Command {
    val temporaryChannelId: ByteVector32 = open.fold(_.temporaryChannelId, _.temporaryChannelId)
    val remoteFundingAmount: Satoshi = open.fold(_.fundingSatoshis, _.fundingAmount)
  }
  case class InterceptChannelFundingAttempt(replyTo: ActorRef[InterceptChannelFundingAttemptResponse], peer: PeerDetails, request: Either[TxInitRbf, SpliceInit], commitments: Commitments) extends Command

  sealed trait InterceptOpenChannelResponse
  case class AcceptOpenChannelAttempt(temporaryChannelId: ByteVector32, defaultParams: DefaultParams, addFunding_opt: Option[LiquidityAds.AddFunding]) extends InterceptOpenChannelResponse
  sealed trait InterceptChannelFundingAttemptResponse
  case class AcceptChannelFundingAttempt(addFunding_opt: Option[LiquidityAds.AddFunding]) extends InterceptChannelFundingAttemptResponse
  case class RejectAttempt(reason: String) extends InterceptOpenChannelResponse with InterceptChannelFundingAttemptResponse
  // @formatter:on
}