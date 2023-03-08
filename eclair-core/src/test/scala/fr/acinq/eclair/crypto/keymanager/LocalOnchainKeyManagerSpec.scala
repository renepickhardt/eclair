package fr.acinq.eclair.crypto.keymanager

import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.psbt.{KeyPathWithMaster, Psbt}
import fr.acinq.bitcoin.scalacompat.{Block, DeterministicWallet, OutPoint, Satoshi, Script, Transaction, TxIn, TxOut}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

import java.util.Base64
import scala.jdk.CollectionConverters.SeqHasAsJava

class LocalOnchainKeyManagerSpec extends AnyFunSuite {
  test("sign psbt (non-reg test)") {
    val seed = ByteVector.fromValidHex("01" * 32)
    val onchainKeyManager = new LocalOnchainKeyManager(seed, Block.TestnetGenesisBlock.hash)
    // data generated by bitcoin core on regtest
    val psbt = Psbt.read(
      Base64.getDecoder.decode("cHNidP8BAHECAAAAAfZo4nGIyTg77MFmEBkQH1Au3Jl8vzB2WWQGGz/MbyssAAAAAAD9////ArAHPgUAAAAAFgAU6j9yVvLg66Zu3GM/xHbmXT0yvyiAlpgAAAAAABYAFODscQh3N7lmDYyV5yrHpGL2Zd4JAAAAAAABAH0CAAAAAaNdmqUNlziIjSaif3JUcvJWdyF0U5bYq13NMe+LbaBZAAAAAAD9////AjSp1gUAAAAAFgAUjfFMfBg8ulo/874n3+0ode7ka0BAQg8AAAAAACIAIPUn/XU17DfnvDkj8gn2twG3jtr2Z7sthy9K2MPTdYkaAAAAAAEBHzSp1gUAAAAAFgAUjfFMfBg8ulo/874n3+0ode7ka0AiBgM+PDdyxsVisa66SyBxiUvhEam8lEP64yujvVsEcGaqIxgPCfOBVAAAgAEAAIAAAACAAQAAAAMAAAAAIgIDWmAhb/sCV9+HjwFpPuy2TyEBi/Y11wrEHZUihe3N80EYDwnzgVQAAIABAACAAAAAgAEAAAAFAAAAAAA=")
    ).getRight

    val psbt1 = onchainKeyManager.signPsbt(psbt, psbt.getInputs.toArray().indices, Seq(0))
    val tx = psbt1.extract()
    assert(tx.isRight)
  }

  test("sign psbt") {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    import fr.acinq.bitcoin.utils.EitherKt

    val seed = ByteVector.fromValidHex("01" * 32)
    val onchainKeyManager = new LocalOnchainKeyManager(seed, Block.TestnetGenesisBlock.hash)

    // create a watch-only BIP84 wallet from our key manager xpub
    val (_, accountPub) = DeterministicWallet.ExtendedPublicKey.decode(onchainKeyManager.getOnchainMasterPubKey(0))
    val mainPub = DeterministicWallet.derivePublicKey(accountPub, 0)
    val changePub = DeterministicWallet.derivePublicKey(accountPub, 1)

    def getPublicKey(index: Long) = DeterministicWallet.derivePublicKey(mainPub, index).publicKey

    val utxos = Seq(
      Transaction(version = 2, txIn = Nil, txOut = TxOut(Satoshi(1000_000), Script.pay2wpkh(getPublicKey(0))) :: Nil, lockTime = 0),
      Transaction(version = 2, txIn = Nil, txOut = TxOut(Satoshi(1100_000), Script.pay2wpkh(getPublicKey(1))) :: Nil, lockTime = 0),
      Transaction(version = 2, txIn = Nil, txOut = TxOut(Satoshi(1200_000), Script.pay2wpkh(getPublicKey(2))) :: Nil, lockTime = 0),
    )
    val bip32paths = Seq(
      new KeyPathWithMaster(onchainKeyManager.getOnchainMasterMasterFingerprint, new fr.acinq.bitcoin.KeyPath("m/84'/1'/0'/0/0")),
      new KeyPathWithMaster(onchainKeyManager.getOnchainMasterMasterFingerprint, new fr.acinq.bitcoin.KeyPath("m/84'/1'/0'/0/1")),
      new KeyPathWithMaster(onchainKeyManager.getOnchainMasterMasterFingerprint, new fr.acinq.bitcoin.KeyPath("m/84'/1'/0'/0/2")),
    )

    val tx = Transaction(version = 2,
      txIn = utxos.map(tx => TxIn(OutPoint(tx, 0), Nil, fr.acinq.bitcoin.TxIn.SEQUENCE_FINAL)),
      txOut = TxOut(Satoshi(1000_000), Script.pay2wpkh(getPublicKey(0))) :: Nil, lockTime = 0)
    val psbt: Psbt =  {
      val p0 = new Psbt(tx).updateWitnessInput(OutPoint(utxos(0), 0), utxos(0).txOut(0), null, Script.pay2pkh(getPublicKey(0)).map(scala2kmp).asJava, null, java.util.Map.of(getPublicKey(0), bip32paths(0)))
      val p1 = EitherKt.flatMap(p0, (psbt: Psbt) => psbt.updateWitnessInput(OutPoint(utxos(1), 0), utxos(1).txOut(0), null, Script.pay2pkh(getPublicKey(1)).map(scala2kmp).asJava, null, java.util.Map.of(getPublicKey(1), bip32paths(1))))
      val p2 = EitherKt.flatMap(p1, (psbt: Psbt) => psbt.updateWitnessInput(OutPoint(utxos(2), 0), utxos(2).txOut(0), null, Script.pay2pkh(getPublicKey(2)).map(scala2kmp).asJava, null, java.util.Map.of(getPublicKey(2), bip32paths(2))))
      val p3 = EitherKt.flatMap(p2, (psbt: Psbt) => psbt.updateNonWitnessInput(utxos(0), 0, null, null, java.util.Map.of()))
      val p4 = EitherKt.flatMap(p3, (psbt: Psbt) => psbt.updateNonWitnessInput(utxos(1), 0, null, null, java.util.Map.of()))
      val p5 = EitherKt.flatMap(p4, (psbt: Psbt) => psbt.updateNonWitnessInput(utxos(2), 0, null, null, java.util.Map.of()))
      val p6 = EitherKt.flatMap(p5, (psbt: Psbt) => psbt.updateWitnessOutput(0, null, null, java.util.Map.of(getPublicKey(0), bip32paths(0))))
      p6.getRight
    }

    {
      // sign all inputs and outputs
      val psbt1 = onchainKeyManager.signPsbt(psbt, Seq(0, 1, 2), Seq(0))
      val signedTx = psbt1.extract().getRight
      Transaction.correctlySpends(signedTx, utxos, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    {
      // sign the first 2 inputs only
      val psbt1 = onchainKeyManager.signPsbt(psbt, Seq(0, 1), Seq(0))
      // extracting the final tx fails because no all inputs as signed
      assert(psbt1.extract().isLeft)
      assert(psbt1.getInput(2).getScriptWitness == null)
    }
    {
      // provide a wrong derivation path for the first input
      val updated = psbt.updateWitnessInput(OutPoint(utxos(0), 0), utxos(0).txOut(0), null, Script.pay2pkh(getPublicKey(0)).map(scala2kmp).asJava, null, java.util.Map.of(getPublicKey(0), bip32paths(2))).getRight // wrong bip32 path
      val error = intercept[IllegalArgumentException] {
        onchainKeyManager.signPsbt(updated, Seq(0, 1, 2), Seq(0))
      }
      assert(error.getMessage.contains("cannot compute private key"))
    }
    {
      // provide a wrong derivation path for the first output
      val updated = psbt.updateWitnessOutput(0, null, null, java.util.Map.of(getPublicKey(0), bip32paths(1))).getRight // wrong path
      val error = intercept[IllegalArgumentException] {
        onchainKeyManager.signPsbt(updated, Seq(0, 1, 2), Seq(0))
      }
      assert(error.getMessage.contains("cannot compute public key"))
    }
    {
      // lie about the amount being spent
      val updated = psbt.updateWitnessInput(OutPoint(utxos(0), 0), utxos(0).txOut(0).copy(amount = Satoshi(10)), null, Script.pay2pkh(getPublicKey(0)).map(scala2kmp).asJava, null, java.util.Map.of(getPublicKey(0), bip32paths(0))).getRight
      val error = intercept[IllegalArgumentException] {
        onchainKeyManager.signPsbt(updated, Seq(0, 1, 2), Seq(0))
      }
      assert(error.getMessage.contains("utxo mismatch"))
    }
  }

  test("compute descriptor checksums") {
    val data = Seq(
      "pkh([6ded4eb8/44h/0h/0h]xpub6C6N5WVF5zmurBR52MZZj8Jxm6eDiKyM4wFCm7xTYBEsAvJPqBKp2u2K7RTsZaYDN8duBWq4acrD4vrwjaKHTYuntGjL334nVHtLNuaj5Mu/0/*)#5mzpq0w6",
      "wpkh([6ded4eb8/84h/0h/0h]xpub6CDeom4xT3Wg7BuyXU2Sd9XerTKttyfxRwJE36mi5HxFYpYdtdwM76Zx8swPnc6zxuArMYJgjNy91fJ13YtGPHgf49YqA8KdXg6D69tzNFh/0/*)#refya6f0",
      "sh(wpkh([6ded4eb8/49h/0h/0h]xpub6Cb8jR9kYsfC6kj9CsE18SyudWjW2V3FnBFkT2oqq6n7NWWvJrjhFin3sAYg8X7ApX8iPophBa98mo4nMvSxnqrXvpnwaRopecQz859Ai1s/0/*))#xrhyhtvl",
      "tr([6ded4eb8/86h/0h/0h]xpub6CDp1iw76taes3pkqfiJ6PYhwURkaYksJ62CrrdTVr6ow9wR9mKAtUGoZQqb8pRDiq2F8k31tYrrJjVGTRSLYGQ7nYpmewH94ThsAgDxJ4h/0/*)#2nm7drky",
      "pkh([6ded4eb8/44h/0h/0h]xpub6C6N5WVF5zmurBR52MZZj8Jxm6eDiKyM4wFCm7xTYBEsAvJPqBKp2u2K7RTsZaYDN8duBWq4acrD4vrwjaKHTYuntGjL334nVHtLNuaj5Mu/1/*)#908qa67z",
      "wpkh([6ded4eb8/84h/0h/0h]xpub6CDeom4xT3Wg7BuyXU2Sd9XerTKttyfxRwJE36mi5HxFYpYdtdwM76Zx8swPnc6zxuArMYJgjNy91fJ13YtGPHgf49YqA8KdXg6D69tzNFh/1/*)#jdv9q0eh",
      "sh(wpkh([6ded4eb8/49h/0h/0h]xpub6Cb8jR9kYsfC6kj9CsE18SyudWjW2V3FnBFkT2oqq6n7NWWvJrjhFin3sAYg8X7ApX8iPophBa98mo4nMvSxnqrXvpnwaRopecQz859Ai1s/1/*))#nzej05eq",
      "tr([6ded4eb8/86h/0h/0h]xpub6CDp1iw76taes3pkqfiJ6PYhwURkaYksJ62CrrdTVr6ow9wR9mKAtUGoZQqb8pRDiq2F8k31tYrrJjVGTRSLYGQ7nYpmewH94ThsAgDxJ4h/1/*)#m87lskxu"
    )
    data.foreach(dnc => {
      val Array(desc, checksum) = dnc.split('#')
      assert(checksum == LocalOnchainKeyManager.descriptorChecksum(desc))
    })
  }
}