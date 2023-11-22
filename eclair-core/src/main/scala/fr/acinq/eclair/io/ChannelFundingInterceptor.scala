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

package fr.acinq.eclair.io

import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.{CMD_GET_CHANNEL_INFO, ChannelData, ChannelDataWithCommitments, Commitments}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Features, InitFeature, InterceptChannelFundingPlugin, Logs, NodeParams}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Short-lived actor that handles accepting or rejecting an funding operation initiated by a remote peer on an existing
 * channel (e.g. splice, RBF).
 */
object ChannelFundingInterceptor {

  // @formatter:off
  sealed trait Command
  case class InterceptRequest(msg: Either[TxInitRbf, SpliceInit]) extends Command {
    val channelId: ByteVector32 = msg.fold(_.channelId, _.channelId)
  }
  private case class WrappedChannelData(data: ChannelData) extends Command
  private case class WrappedPluginResponse(response: InterceptChannelFundingPlugin.InterceptChannelFundingAttemptResponse) extends Command
  private case object PluginTimeout extends Command
  // @formatter:on

  case class AcceptRequest(request: Either[TxInitRbf, SpliceInit], channel: ActorRef[Any], addFunding_opt: Option[LiquidityAds.AddFunding], peerConnection: ActorRef[Any])

  def apply(nodeParams: NodeParams,
            peer: ActorRef[Any], peerConnection: ActorRef[Any], channel: ActorRef[Any],
            remoteNodeId: PublicKey, remoteFeatures: Features[InitFeature], remoteAddress: NodeAddress,
            pluginTimeout: FiniteDuration = 1 minute): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
        new ChannelFundingInterceptor(nodeParams, peer, peerConnection, channel, remoteNodeId, remoteFeatures, remoteAddress, pluginTimeout, context).waitForRequest()
      }
    }
  }

}

private class ChannelFundingInterceptor(nodeParams: NodeParams,
                                        peer: ActorRef[Any], peerConnection: ActorRef[Any], channel: ActorRef[Any],
                                        remoteNodeId: PublicKey, remoteFeatures: Features[InitFeature], remoteAddress: NodeAddress,
                                        pluginTimeout: FiniteDuration,
                                        context: ActorContext[ChannelFundingInterceptor.Command]) {

  import ChannelFundingInterceptor._

  def waitForRequest(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case request: InterceptRequest => nodeParams.channelFundingInterceptor match {
        case Some(plugin) =>
          channel ! CMD_GET_CHANNEL_INFO(context.messageAdapter(info => WrappedChannelData(info.data)))
          waitForChannelData(request, plugin)
        case None =>
          acceptRequest(request, None)
      }
    }
  }

  private def waitForChannelData(request: InterceptRequest, plugin: InterceptChannelFundingPlugin): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedChannelData(data: ChannelDataWithCommitments) => queryPlugin(request, plugin, data.commitments)
      case _ => rejectRequest(request.channelId, "channel unavailable")
    }
  }

  private def queryPlugin(request: InterceptRequest, plugin: InterceptChannelFundingPlugin, commitments: Commitments): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      timers.startSingleTimer(PluginTimeout, pluginTimeout)
      val pluginResponseAdaptor = context.messageAdapter[InterceptChannelFundingPlugin.InterceptChannelFundingAttemptResponse](WrappedPluginResponse)
      val peerDetails = InterceptChannelFundingPlugin.PeerDetails(remoteNodeId, remoteFeatures, remoteAddress)
      plugin.channelFundingInterceptor ! InterceptChannelFundingPlugin.InterceptChannelFundingAttempt(pluginResponseAdaptor, peerDetails, request.msg, commitments)
      Behaviors.receiveMessagePartial {
        case WrappedPluginResponse(r) =>
          r match {
            case InterceptChannelFundingPlugin.AcceptChannelFundingAttempt(addFunding_opt) => acceptRequest(request, addFunding_opt)
            case InterceptChannelFundingPlugin.RejectAttempt(reason) => rejectRequest(request.channelId, reason)
          }
          Behaviors.stopped
        case PluginTimeout =>
          context.log.warn("timed out while waiting for plugin to accept or reject funding attempt: {}", plugin.name)
          acceptRequest(request, None)
      }
    }
  }

  private def acceptRequest(request: InterceptRequest, addFunding_opt: Option[LiquidityAds.AddFunding]): Behavior[Command] = {
    peer ! AcceptRequest(request.msg, channel, addFunding_opt, peerConnection)
    Behaviors.stopped
  }

  private def rejectRequest(channelId: ByteVector32, reason: String): Behavior[Command] = {
    peer ! Peer.OutgoingMessage(TxAbort(channelId, reason), peerConnection.toClassic)
    Behaviors.stopped
  }

}
