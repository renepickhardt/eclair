package fr.acinq.eclair.testutils

import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingConfirmed, WatchFundingSpent}

import scala.reflect.ClassTag

case class PimpTestProbe(probe: TestProbe) {

  /**
   * Generic method to perform validation on an expected message.
   *
   * @param check should contains asserts on the message
   */
  def expectMsgTypeHaving[T](check: T => Boolean)(implicit t: ClassTag[T]): T = {
    val msg = probe.expectMsgType[T]
    assert(check(msg))
    msg
  }

  def expectWatchFundingSpent(txid: ByteVector32): WatchFundingSpent = expectMsgTypeHaving[WatchFundingSpent](w => w.txId == txid)

  def expectWatchFundingConfirmed(txid: ByteVector32): WatchFundingConfirmed = expectMsgTypeHaving[WatchFundingConfirmed](w => w.txId == txid)
}

object PimpTestProbe {

  implicit def convert(probe: TestProbe): PimpTestProbe = PimpTestProbe(probe)

}
