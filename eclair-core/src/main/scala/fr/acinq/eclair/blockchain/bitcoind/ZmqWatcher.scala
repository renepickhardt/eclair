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

package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.bitcoin.{Block, BlockHeader}
import fr.acinq.eclair.blockchain.Monitoring.Metrics
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog
import fr.acinq.eclair.wire.protocol.ChannelAnnouncement
import fr.acinq.eclair.{BlockHeight, KamonExt, NodeParams, RealShortChannelId, TimestampSecond}

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by PM on 21/02/2016.
 */

/**
 * A blockchain watcher that:
 *  - receives bitcoin events (new blocks and new txs) directly from the bitcoin network
 *  - also uses bitcoin-core rpc api, most notably for tx confirmation count and block count (because reorgs)
 */
object ZmqWatcher {

  val blockTimeout: FiniteDuration = 15 minutes

  // @formatter:off
  sealed trait Command
  sealed trait Watch[T <: WatchTriggered] extends Command {
    def replyTo: ActorRef[T]
  }
  type GenericWatch = Watch[_ <: WatchTriggered]
  sealed trait WatchTriggered
  private case class TriggerEvent[T <: WatchTriggered](replyTo: ActorRef[T], watch: Watch[T], event: T) extends Command
  private[bitcoind] case class StopWatching[T <: WatchTriggered](sender: ActorRef[T]) extends Command
  case class ListWatches(replyTo: ActorRef[Set[GenericWatch]]) extends Command

  private case object TickNewBlock extends Command
  private case object TickBlockTimeout extends Command
  private case class GetBlockCountFailed(t: Throwable) extends Command
  private case class CheckBlockHeight(current: BlockHeight) extends Command
  private case class PublishBlockHeight(current: BlockHeight) extends Command
  private case class ProcessNewBlock(blockHash: ByteVector32) extends Command
  private case class ProcessNewTransaction(tx: Transaction) extends Command

  final case class ValidateRequest(replyTo: ActorRef[ValidateResult], ann: ChannelAnnouncement) extends Command
  final case class ValidateResult(c: ChannelAnnouncement, fundingTx: Either[Throwable, (Transaction, UtxoStatus)])

  final case class GetTxWithMeta(replyTo: ActorRef[GetTxWithMetaResponse], txid: ByteVector32) extends Command
  final case class GetTxWithMetaResponse(txid: ByteVector32, tx_opt: Option[Transaction], lastBlockTimestamp: TimestampSecond)
  final case class SetBlockIndex(index: IndexedBlocks) extends Command
  final case class BlockIndexFailure(error: Throwable) extends Command
  sealed trait UtxoStatus
  object UtxoStatus {
    case object Unspent extends UtxoStatus
    case class Spent(spendingTxConfirmed: Boolean) extends UtxoStatus
  }

  /** Watch for confirmation of a given transaction. */
  sealed trait WatchConfirmed[T <: WatchConfirmedTriggered] extends Watch[T] {
    /** TxId of the transaction to watch. */
    def txId: ByteVector32
    /** Number of confirmations. */
    def minDepth: Long
  }

  /**
   * Watch for transactions spending the given outpoint.
   *
   * NB: an event will be triggered *every time* a transaction spends the given outpoint. This can be useful when:
   *  - we see a spending transaction in the mempool, but it is then replaced (RBF)
   *  - we see a spending transaction in the mempool, but a conflicting transaction "wins" and gets confirmed in a block
   */
  sealed trait WatchSpent[T <: WatchSpentTriggered] extends Watch[T] {
    /** TxId of the outpoint to watch. */
    def txId: ByteVector32
    /** Index of the outpoint to watch. */
    def outputIndex: Int
    /**
     * TxIds of potential spending transactions; most of the time we know the txs, and it allows for optimizations.
     * This argument can safely be ignored by watcher implementations.
     */
    def hints: Set[ByteVector32]
  }

  /**
   * Watch for the first transaction spending the given outpoint. We assume that txid is already confirmed or in the
   * mempool (i.e. the outpoint exists).
   *
   * NB: an event will be triggered only once when we see a transaction that spends the given outpoint. If you want to
   * react to the transaction spending the outpoint, you should use [[WatchSpent]] instead.
   */
  sealed trait WatchSpentBasic[T <: WatchSpentBasicTriggered] extends Watch[T] {
    /** TxId of the outpoint to watch. */
    def txId: ByteVector32
    /** Index of the outpoint to watch. */
    def outputIndex: Int
  }

  /** This event is sent when a [[WatchConfirmed]] condition is met. */
  sealed trait WatchConfirmedTriggered extends WatchTriggered {
    /** Block in which the transaction was confirmed. */
    def blockHeight: BlockHeight
    /** Index of the transaction in that block. */
    def txIndex: Int
    /** Transaction that has been confirmed. */
    def tx: Transaction
  }

  /** This event is sent when a [[WatchSpent]] condition is met. */
  sealed trait WatchSpentTriggered extends WatchTriggered {
    /** Transaction spending the watched outpoint. */
    def spendingTx: Transaction
  }

  /** This event is sent when a [[WatchSpentBasic]] condition is met. */
  sealed trait WatchSpentBasicTriggered extends WatchTriggered

  case class WatchExternalChannelSpent(replyTo: ActorRef[WatchExternalChannelSpentTriggered], txId: ByteVector32, outputIndex: Int, shortChannelId: RealShortChannelId) extends WatchSpentBasic[WatchExternalChannelSpentTriggered]
  case class WatchExternalChannelSpentTriggered(shortChannelId: RealShortChannelId) extends WatchSpentBasicTriggered

  case class WatchFundingSpent(replyTo: ActorRef[WatchFundingSpentTriggered], txId: ByteVector32, outputIndex: Int, hints: Set[ByteVector32]) extends WatchSpent[WatchFundingSpentTriggered]
  case class WatchFundingSpentTriggered(spendingTx: Transaction) extends WatchSpentTriggered

  case class WatchOutputSpent(replyTo: ActorRef[WatchOutputSpentTriggered], txId: ByteVector32, outputIndex: Int, hints: Set[ByteVector32]) extends WatchSpent[WatchOutputSpentTriggered]
  case class WatchOutputSpentTriggered(spendingTx: Transaction) extends WatchSpentTriggered

  /** Waiting for a wallet transaction to be published guarantees that bitcoind won't double-spend it in the future, unless we explicitly call abandontransaction. */
  case class WatchPublished(replyTo: ActorRef[WatchPublishedTriggered], txId: ByteVector32) extends Watch[WatchPublishedTriggered]
  case class WatchPublishedTriggered(tx: Transaction) extends WatchTriggered

  case class WatchFundingConfirmed(replyTo: ActorRef[WatchFundingConfirmedTriggered], txId: ByteVector32, minDepth: Long) extends WatchConfirmed[WatchFundingConfirmedTriggered]
  case class WatchFundingConfirmedTriggered(blockHeight: BlockHeight, txIndex: Int, tx: Transaction) extends WatchConfirmedTriggered

  case class WatchFundingDeeplyBuried(replyTo: ActorRef[WatchFundingDeeplyBuriedTriggered], txId: ByteVector32, minDepth: Long) extends WatchConfirmed[WatchFundingDeeplyBuriedTriggered]
  case class WatchFundingDeeplyBuriedTriggered(blockHeight: BlockHeight, txIndex: Int, tx: Transaction) extends WatchConfirmedTriggered

  case class WatchTxConfirmed(replyTo: ActorRef[WatchTxConfirmedTriggered], txId: ByteVector32, minDepth: Long) extends WatchConfirmed[WatchTxConfirmedTriggered]
  case class WatchTxConfirmedTriggered(blockHeight: BlockHeight, txIndex: Int, tx: Transaction) extends WatchConfirmedTriggered

  case class WatchParentTxConfirmed(replyTo: ActorRef[WatchParentTxConfirmedTriggered], txId: ByteVector32, minDepth: Long) extends WatchConfirmed[WatchParentTxConfirmedTriggered]
  case class WatchParentTxConfirmedTriggered(blockHeight: BlockHeight, txIndex: Int, tx: Transaction) extends WatchConfirmedTriggered

  case class WatchAlternativeCommitTxConfirmed(replyTo: ActorRef[WatchAlternativeCommitTxConfirmedTriggered], txId: ByteVector32, minDepth: Long) extends WatchConfirmed[WatchAlternativeCommitTxConfirmedTriggered]
  case class WatchAlternativeCommitTxConfirmedTriggered(blockHeight: BlockHeight, txIndex: Int, tx: Transaction) extends WatchConfirmedTriggered

  private sealed trait AddWatchResult
  private case object Keep extends AddWatchResult
  private case object Ignore extends AddWatchResult
  // @formatter:on

  def apply(nodeParams: NodeParams, blockCount: AtomicLong, client: BitcoinCoreClient): Behavior[Command] =
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[NewBlock](b => ProcessNewBlock(b.blockHash)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[NewTransaction](t => ProcessNewTransaction(t.tx)))
      Behaviors.withTimers { timers =>
        // we initialize block count
        timers.startSingleTimer(TickNewBlock, 1 second)
        // we start a timer in case we don't receive ZMQ block events
        timers.startSingleTimer(TickBlockTimeout, blockTimeout)
        new ZmqWatcher(nodeParams, blockCount, client, context, timers).waitingForIndex(Set.empty[GenericWatch])
      }
    }

  private def utxo(w: GenericWatch): Option[OutPoint] = {
    w match {
      case w: WatchSpent[_] => Some(OutPoint(w.txId.reverse, w.outputIndex))
      case w: WatchSpentBasic[_] => Some(OutPoint(w.txId.reverse, w.outputIndex))
      case _ => None
    }
  }

  /**
   * The resulting map allows checking spent txs in constant time wrt number of watchers.
   */
  def addWatchedUtxos(m: Map[OutPoint, Set[GenericWatch]], w: GenericWatch): Map[OutPoint, Set[GenericWatch]] = {
    utxo(w) match {
      case Some(utxo) => m.get(utxo) match {
        case Some(watches) => m + (utxo -> (watches + w))
        case None => m + (utxo -> Set(w))
      }
      case None => m
    }
  }

  def removeWatchedUtxos(m: Map[OutPoint, Set[GenericWatch]], w: GenericWatch): Map[OutPoint, Set[GenericWatch]] = {
    utxo(w) match {
      case Some(utxo) => m.get(utxo) match {
        case Some(watches) if watches - w == Set.empty => m - utxo
        case Some(watches) => m + (utxo -> (watches - w))
        case None => m
      }
      case None => m
    }
  }

  case class IndexedBlock(height: BlockHeight, header: BlockHeader, txids: Vector[ByteVector32], spent: Map[OutPoint, Int]) {
    def getSpendingTx(outPoint: OutPoint): Option[ByteVector32] = spent.get(outPoint).map(index => txids(index))
  }

  object IndexedBlock {
    def apply(block: Block, height: BlockHeight): IndexedBlock = {
      import fr.acinq.bitcoin.scalacompat.KotlinUtils._
      var index = 0
      val txids = new Array[ByteVector32](block.tx.size())
      val spent = collection.mutable.Map.empty[OutPoint, Int]

      val it = block.tx.iterator()
      while (it.hasNext) {
        val tx = it.next()
        txids(index) = kmp2scala(tx.txid)
        val it1 = tx.txIn.iterator()
        while (it1.hasNext) {
          val input = it1.next()
          spent += kmp2scala(input.outPoint) -> index
        }
        index = index + 1
      }

      new IndexedBlock(height, block.header, txids.toVector, spent.toMap)
    }
  }

  case class IndexedBlocks(indexes: Vector[IndexedBlock], maxSize: Int) {
    def append(indexedBlock: IndexedBlock): IndexedBlocks = {
      if (indexes.isEmpty) {
        IndexedBlocks(Vector(indexedBlock), maxSize)
      } else {
        require(indexedBlock.height == indexes.last.height + 1)
        require(indexedBlock.header.hashPreviousBlock == indexes.last.header.hash)
        val indexes1 = if (indexes.size == maxSize) indexes.drop(1) :+ indexedBlock else indexes :+ indexedBlock
        IndexedBlocks(indexes1, maxSize)
      }
    }

    def prepend(indexedBlock: IndexedBlock): IndexedBlocks = {
      if (indexes.isEmpty) {
        IndexedBlocks(Vector(indexedBlock), maxSize)
      } else {
        require(indexedBlock.height == indexes.head.height - 1)
        require(indexedBlock.header.hash == indexes.head.header.hashPreviousBlock)
        val indexes1 = if (indexes.size == maxSize) indexedBlock +: indexes.dropRight(1) else indexedBlock +: indexes
        IndexedBlocks(indexes1, maxSize)
      }
    }

    def append(block: Block, height: BlockHeight): IndexedBlocks = {
      val indexedBlock = IndexedBlock(block, height)
      append(indexedBlock)
    }

    def prepend(block: Block, height: BlockHeight): IndexedBlocks = {
      val indexedBlock = IndexedBlock(block, height)
      prepend(indexedBlock)
    }

    def getTxHeight(txid: ByteVector32): Option[(BlockHeight, Int)] = {
      val it = indexes.iterator
      while (it.hasNext) {
        val indexedBlock = it.next()
        val index = indexedBlock.txids.indexOf(txid)
        if (index >= 0) return Some((indexedBlock.height, index))
      }
      None
    }

    def getSpendingTx(outPoint: OutPoint): Option[ByteVector32] = indexes.find(_.getSpendingTx(outPoint).isDefined).flatMap(_.getSpendingTx(outPoint))
  }

  object IndexedBlocks {
    def apply(indexedBlock: IndexedBlock, maxSize: Int): IndexedBlocks = new IndexedBlocks(Vector(indexedBlock), maxSize)

    def apply(block: Block, height: BlockHeight, maxSize: Int): IndexedBlocks = IndexedBlocks(IndexedBlock(block, height), maxSize)

    def build(maxSize: Int, bitcoinClient: BitcoinCoreClient)(implicit ec: ExecutionContext): Future[IndexedBlocks] = {
      def loop(indexedBlocks: IndexedBlocks): Future[IndexedBlocks] = if (indexedBlocks.indexes.size == indexedBlocks.maxSize) Future.successful(indexedBlocks) else {
        import fr.acinq.bitcoin.scalacompat.KotlinUtils._
        for {
          block <- bitcoinClient.getBlock(indexedBlocks.indexes.head.header.hashPreviousBlock.reversed())
          updated <- loop(indexedBlocks.prepend(block, indexedBlocks.indexes.head.height - 1))
        } yield updated
      }

      for {
        height <- bitcoinClient.getBlockHeight()
        blockId <- bitcoinClient.getBlockId(height.toLong)
        block <- bitcoinClient.getBlock(blockId)
        indexedBlocks <- loop(IndexedBlocks(block, height, maxSize))
      } yield indexedBlocks
    }
  }
}

private class ZmqWatcher(nodeParams: NodeParams, blockHeight: AtomicLong, client: BitcoinCoreClient, context: ActorContext[ZmqWatcher.Command], timers: TimerScheduler[ZmqWatcher.Command])(implicit ec: ExecutionContext = ExecutionContext.global) {

  import ZmqWatcher._

  private val log = context.log

  private val watchdog = context.spawn(Behaviors.supervise(BlockchainWatchdog(nodeParams, 150 seconds)).onFailure(SupervisorStrategy.resume), "blockchain-watchdog")

  context.pipeToSelf(IndexedBlocks.build(100, client)) {
    case Success(index) => SetBlockIndex(index)
    case Failure(e) => BlockIndexFailure(e)
  }

  private def waitingForIndex(watches: Set[GenericWatch]): Behavior[Command] = {
    Behaviors.withStash(1000) { buffer =>
      Behaviors.receiveMessage {
        case w: Watch[_] =>
          if (watches.contains(w)) {
            Behaviors.same
          } else {
            log.debug("adding watch {}", w)
            context.watchWith(w.replyTo, StopWatching(w.replyTo))
            waitingForIndex(watches + w)
          }

        case StopWatching(origin) =>
          // we remove watches associated to dead actors
          val deprecatedWatches = watches.filter(_.replyTo == origin)
          waitingForIndex(watches -- deprecatedWatches)

        case ValidateRequest(replyTo, ann) =>
          client.validate(ann).map(replyTo ! _)
          Behaviors.same

        case GetTxWithMeta(replyTo, txid) =>
          client.getTransactionMeta(txid).map(replyTo ! _)
          Behaviors.same

        case r: ListWatches =>
          r.replyTo ! watches
          Behaviors.same

        case SetBlockIndex(index) =>
          log.info("block index is ready")
          watches.foreach(w => context.self ! w)
          buffer.unstashAll(watching(Set.empty[GenericWatch], Map.empty[OutPoint, Set[GenericWatch]], index))

        case BlockIndexFailure(error) =>
          log.error("cannot build block index", error)
          watches.foreach(w => context.self ! w)
          buffer.unstashAll(watching(Set.empty[GenericWatch], Map.empty[OutPoint, Set[GenericWatch]], IndexedBlocks(Vector.empty[IndexedBlock], 100)))

        case other =>
          buffer.stash(other)
          Behaviors.same
      }
    }
  }

  private def watching(watches: Set[GenericWatch], watchedUtxos: Map[OutPoint, Set[GenericWatch]], indexedBlocks: IndexedBlocks): Behavior[Command] = {
    Behaviors.receiveMessage {
      case ProcessNewTransaction(tx) =>
        log.debug("analyzing txid={} tx={}", tx.txid, tx)
        tx.txIn
          .map(_.outPoint)
          .flatMap(watchedUtxos.get)
          .flatten
          .foreach {
            case w: WatchExternalChannelSpent => context.self ! TriggerEvent(w.replyTo, w, WatchExternalChannelSpentTriggered(w.shortChannelId))
            case w: WatchFundingSpent => context.self ! TriggerEvent(w.replyTo, w, WatchFundingSpentTriggered(tx))
            case w: WatchOutputSpent => context.self ! TriggerEvent(w.replyTo, w, WatchOutputSpentTriggered(tx))
            case _: WatchPublished => // nothing to do
            case _: WatchConfirmed[_] => // nothing to do
          }
        watches.collect {
          case w: WatchPublished if w.txId == tx.txid => context.self ! TriggerEvent(w.replyTo, w, WatchPublishedTriggered(tx))
        }
        Behaviors.same

      case ProcessNewBlock(blockHash) =>
        log.debug("received blockhash={}", blockHash)
        log.debug("scheduling a new task to check on tx confirmations")
        // we have received a block, so we can reset the block timeout timer
        timers.startSingleTimer(TickBlockTimeout, blockTimeout)
        // we do this to avoid herd effects in testing when generating a lots of blocks in a row
        timers.startSingleTimer(TickNewBlock, 2 seconds)
        Behaviors.same

      case TickBlockTimeout =>
        // we haven't received a block in a while, we check whether we're behind and restart the timer.
        timers.startSingleTimer(TickBlockTimeout, blockTimeout)
        context.pipeToSelf(client.getBlockHeight()) {
          case Failure(t) => GetBlockCountFailed(t)
          case Success(currentHeight) => CheckBlockHeight(currentHeight)
        }
        Behaviors.same

      case GetBlockCountFailed(t) =>
        log.error("could not get block count from bitcoind", t)
        Behaviors.same

      case CheckBlockHeight(height) =>
        val current = blockHeight.get()
        if (height.toLong > current) {
          log.warn("block {} wasn't received via ZMQ, you should verify that your bitcoind node is running", height.toLong)
          context.self ! TickNewBlock
        }
        Behaviors.same

      case TickNewBlock =>
        context.pipeToSelf(client.getBlockHeight()) {
          case Failure(t) => GetBlockCountFailed(t)
          case Success(currentHeight) => PublishBlockHeight(currentHeight)
        }
        // TODO: beware of the herd effect
        KamonExt.timeFuture(Metrics.NewBlockCheckConfirmedDuration.withoutTags()) {
          Future.sequence(watches.collect {
            case w: WatchPublished => checkPublished(w)
            case w: WatchConfirmed[_] => checkConfirmed(w, indexedBlocks)
          })
        }
        Behaviors.same

      case PublishBlockHeight(currentHeight) =>
        log.debug("setting blockHeight={}", currentHeight)
        blockHeight.set(currentHeight.toLong)
        context.system.eventStream ! EventStream.Publish(CurrentBlockHeight(currentHeight))
        Behaviors.same

      case TriggerEvent(replyTo, watch, event) =>
        if (watches.contains(watch)) {
          log.debug("triggering {}", watch)
          replyTo ! event
          watch match {
            case _: WatchSpent[_] =>
              // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx or the commit tx
              // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
              Behaviors.same
            case _ =>
              watching(watches - watch, removeWatchedUtxos(watchedUtxos, watch), indexedBlocks)
          }
        } else {
          Behaviors.same
        }

      case w: Watch[_] =>
        // We call check* methods and store the watch unconditionally.
        // Maybe the tx is already confirmed or spent, in that case the watch will be triggered and removed immediately.
        val result = w match {
          case _ if watches.contains(w) =>
            Ignore // we ignore duplicates
          case w: WatchSpentBasic[_] =>
            checkSpentBasic(w, indexedBlocks)
            Keep
          case w: WatchSpent[_] =>
            checkSpent(w, indexedBlocks)
            Keep
          case w: WatchConfirmed[_] =>
            checkConfirmed(w, indexedBlocks)
            Keep
          case w: WatchPublished =>
            checkPublished(w)
            Keep
        }
        result match {
          case Keep =>
            log.debug("adding watch {}", w)
            context.watchWith(w.replyTo, StopWatching(w.replyTo))
            watching(watches + w, addWatchedUtxos(watchedUtxos, w), indexedBlocks)
          case Ignore =>
            Behaviors.same
        }

      case StopWatching(origin) =>
        // we remove watches associated to dead actors
        val deprecatedWatches = watches.filter(_.replyTo == origin)
        val watchedUtxos1 = deprecatedWatches.foldLeft(watchedUtxos) { case (m, w) => removeWatchedUtxos(m, w) }
        watching(watches -- deprecatedWatches, watchedUtxos1, indexedBlocks)

      case ValidateRequest(replyTo, ann) =>
        client.validate(ann).map(replyTo ! _)
        Behaviors.same

      case GetTxWithMeta(replyTo, txid) =>
        client.getTransactionMeta(txid).map(replyTo ! _)
        Behaviors.same

      case r: ListWatches =>
        r.replyTo ! watches
        Behaviors.same

      case other => Behaviors.same
    }
  }

  private def checkSpentBasic(w: WatchSpentBasic[_ <: WatchSpentBasicTriggered], indexedBlocks: IndexedBlocks): Future[Unit] = {
    indexedBlocks.getSpendingTx(OutPoint(w.txId.reverse, w.outputIndex)) match {
      case Some(_) =>
        log.info(s"output=${w.txId}:${w.outputIndex} has already been spent")
        w match {
          case w: WatchExternalChannelSpent =>
            context.self ! TriggerEvent(w.replyTo, w, WatchExternalChannelSpentTriggered(w.shortChannelId))
            Future.successful(())
        }
      case None =>
        // NB: we assume parent tx was published, we just need to make sure this particular output has not been spent
        client.isTransactionOutputSpendable(w.txId, w.outputIndex, includeMempool = true).collect {
          case false =>
            log.info(s"output=${w.txId}:${w.outputIndex} has already been spent")
            w match {
              case w: WatchExternalChannelSpent => context.self ! TriggerEvent(w.replyTo, w, WatchExternalChannelSpentTriggered(w.shortChannelId))
            }
        }
    }
  }

  private def checkSpent(w: WatchSpent[_ <: WatchSpentTriggered], indexedBlocks: IndexedBlocks): Future[Unit] = {
    indexedBlocks.getSpendingTx(OutPoint(w.txId.reverse, w.outputIndex)) match {
      case Some(txid) => client.getTransaction(txid).map { tx =>
        log.warn(s"found the spending tx of ${w.txId}:${w.outputIndex} in the blockchain: txid=${tx.txid}")
        context.self ! ProcessNewTransaction(tx)
      }
      case None =>
        // first let's see if the parent tx was published or not
        client.getTxConfirmations(w.txId).collect {
          case Some(_) =>
            // parent tx was published, we need to make sure this particular output has not been spent
            client.isTransactionOutputSpendable(w.txId, w.outputIndex, includeMempool = true).collect {
              case false =>
                // the output has been spent, let's find the spending tx
                // if we know some potential spending txs, we try to fetch them directly
                Future.sequence(w.hints.map(txid => client.getTransaction(txid).map(Some(_)).recover { case _ => None }))
                  .map(_
                    .flatten // filter out errors
                    .find(tx => tx.txIn.exists(i => i.outPoint.txid == w.txId && i.outPoint.index == w.outputIndex)) match {
                    case Some(spendingTx) =>
                      // there can be only one spending tx for an utxo
                      log.info(s"${w.txId}:${w.outputIndex} has already been spent by a tx provided in hints: txid=${spendingTx.txid}")
                      context.self ! ProcessNewTransaction(spendingTx)
                    case None =>
                      // no luck, we have to do it the hard way...
                      log.info(s"${w.txId}:${w.outputIndex} has already been spent, looking for the spending tx in the mempool")
                      client.getMempool().map { mempoolTxs =>
                        mempoolTxs.filter(tx => tx.txIn.exists(i => i.outPoint.txid == w.txId && i.outPoint.index == w.outputIndex)) match {
                          case Nil =>
                            log.warn(s"${w.txId}:${w.outputIndex} has already been spent, spending tx not in the mempool, looking in the blockchain...")
                            client.lookForSpendingTx(None, w.txId, w.outputIndex).map { tx =>
                              log.warn(s"found the spending tx of ${w.txId}:${w.outputIndex} in the blockchain: txid=${tx.txid}")
                              context.self ! ProcessNewTransaction(tx)
                            }
                          case txs =>
                            log.info(s"found ${txs.size} txs spending ${w.txId}:${w.outputIndex} in the mempool: txids=${txs.map(_.txid).mkString(",")}")
                            txs.foreach(tx => context.self ! ProcessNewTransaction(tx))
                        }
                      }
                  })
            }
        }
    }
  }

  private def checkPublished(w: WatchPublished): Future[Unit] = {
    log.debug("checking publication of txid={}", w.txId)
    client.getTransaction(w.txId).map(tx => context.self ! TriggerEvent(w.replyTo, w, WatchPublishedTriggered(tx)))
  }

  private def buildTrigger(w: WatchConfirmed[_ <: WatchConfirmedTriggered], height: BlockHeight, index: Int, tx: Transaction): TriggerEvent[_ <: WatchConfirmedTriggered] = w match {
    case w1: WatchFundingConfirmed => TriggerEvent(w1.replyTo, w1, WatchFundingConfirmedTriggered(height, index, tx))
    case w1: WatchFundingDeeplyBuried => TriggerEvent(w1.replyTo, w1, WatchFundingDeeplyBuriedTriggered(height, index, tx))
    case w1: WatchTxConfirmed => TriggerEvent(w1.replyTo, w1, WatchTxConfirmedTriggered(height, index, tx))
    case w1: WatchParentTxConfirmed => TriggerEvent(w1.replyTo, w1, WatchParentTxConfirmedTriggered(height, index, tx))
    case w1: WatchAlternativeCommitTxConfirmed => TriggerEvent(w1.replyTo, w1, WatchAlternativeCommitTxConfirmedTriggered(height, index, tx))
  }

  private def checkConfirmed(w: WatchConfirmed[_ <: WatchConfirmedTriggered], indexedBlocks: IndexedBlocks): Future[Unit] = {
    log.debug("checking confirmations of txid={}", w.txId)
    indexedBlocks.getTxHeight(w.txId) match {
      case Some((height, index)) => client.getTransaction(w.txId).map(tx => context.self ! buildTrigger(w, height, index, tx))
      case None =>
        // NB: this is very inefficient since internally we call `getrawtransaction` three times, but it doesn't really
        // matter because this only happens once, when the watched transaction has reached min_depth
        client.getTxConfirmations(w.txId).flatMap {
          case Some(confirmations) if confirmations >= w.minDepth =>
            client.getTransaction(w.txId).flatMap { tx =>
              client.getTransactionShortId(w.txId).map {
                case (height, index) => context.self ! buildTrigger(w, height, index, tx)
              }
            }
          case _ => Future.successful((): Unit)
        }
    }
  }

}
