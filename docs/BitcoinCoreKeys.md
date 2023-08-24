# Using Eclair to manage your Bitcoin Core wallet's private keys

You can configure Eclair to control (and never expose) the private keys of your Bitcoin Core wallet. This feature was designed to take advantage of deployment where your Eclair node runs in a
"trusted" runtime environment, but is also very useful if your Bitcoin and Eclair nodes run on different machines for example, with a setup for the Bitcoin host that
is less secure than for Eclair (because it is shared among several services for example).

## Configuring Eclair and Bitcoin Core to use a new Eclair-backed bitcoin wallet

Follow these steps to delegate onchain key management to eclair:

1) Generate a BIP39 mnemonic code and passphrase

You can use any BIP39-compatible tool, including most hardware wallets.

2) Create an `eclair-signer.conf` configuration file add it to eclair's data directory

A signer configuration file uses the HOCON format that we already use for `eclair.conf` and must include the following options:

 key                      | description
--------------------------|--------------------------------------------------------------------------
 eclair.signer.wallet     | wallet name
 eclair.signer.mnemonics  | BIP39 mnemonic words
 eclair.signer.passphrase | passphrase
 eclair.signer.timestamp  | wallet creation UNIX timestamp. Set to the current time for new wallets.

This is an example of `eclair-signer.conf` configuration file:

```hocon
{
   eclair {
      signer {
         wallet = "eclair"
         mnemonics = "legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title"
         passphrase = ""
         timestamp = 1686055705
      }
   }
}
```

3) Configure Eclair to handle private keys for this wallet

Set `eclair.bitcoind.wallet` to the name of the wallet in your `eclair-signer.conf` file (`eclair` in the example above) and restart Eclair.
Eclair will automatically create a new, empty, descriptor-enabled, watch-only wallet in Bitcoin Core and import its descriptors.

:warning: Eclair will not import descriptors if the timestamp set in your `eclair-signer.conf` is more than 2 hours old. If the mnemonics and
passphrase that your are using are new, you can safely update this timestamp, but if they have been used before you will need to follow
the steps described in the next section.

You now have a Bitcoin Core watch-only wallet for which only your Eclair node can sign transactions. This Bitcoin Core wallet can
safely be copied to another Bitcoin Core node to monitor your onchain funds.

You can also use `eclair-cli getmasterxpub` to get a BIP32 extended public key that you can import into any compatible Bitcoin wallet
to create a watch-only wallet (Electrum for example) that you can use to monitor your Bitcoin Core balance.

:warning: this means that your Bitcoin Core wallet cannot send funds on its own (since it cannot access private keys to sign transactions).
To send funds onchain you must use `eclair-cli sendonchain`.

:warning: to backup the private keys of this wallet you must either backup your mnemonic code and passphrase, or backup the `eclair-signer.conf` file in your eclair
directory (default is `~/.eclair`) along with your channels and node seed files.

:warning: You can also initialize a backup onchain wallet with the same mnemonic code and passphrase (on a hardware wallet for example), but be warned that using them may interfere with your node's operations (for example you may end up
double-spending funding transactions generated by your node).

## Importing an existing Eclair-backed bitcoin core wallet

The steps above describe how you can simply switch to a new Eclair-backed bitcoin wallet.
If you are already using an Eclair-backed bitcoin wallet that you want to move to another setup, you have several options:

### Copy the bitcoin core wallet and `eclair-signer.conf`

Copy your wallet file to a new Bitcoin Core node, and load it with `bitcoin-cli loadwallet`. Bitcoin Core may need to scan the blockchain which could take some time.
Copy `eclair-signer.conf` to your Eclair data directory and set `eclair.bitcoind.wallet` to the name of the wallet configured in `eclair-signer.conf` (and which should also be the name of your Bitcoin Core wallet).
Once your wallet has been imported, just restart Eclair.

### Create an empty wallet manually and import Eclair descriptors

1) Create an empty, descriptor-enabled, watch-only wallet in Bitcoin Core:

:warning: The name must match the one that you set in `eclair-signer.conf` (here we use "eclair")

```shell
$ bitcoin-cli -named createwallet wallet_name=eclair disable_private_keys=true blank=true descriptors=true load_on_startup=true
```

2) Import public descriptors generated by Eclair

Copy `eclair-signer.conf` to your Eclair data directory but do not change `eclair.bitcoind.wallet`, and restart Eclair.

`eclair-cli getdescriptors` will return public wallet descriptors in a format that is compatible with Bitcoin Core, and that you can import with `bitcoin-cli -rpcwallet=eclair importdescriptors`.
This is an example of descriptors generated by Eclair:

```json
[
   {
      "desc": "wpkh([0d9250da/84h/1h/0h]tpubDDGF9PnrXww2h1mKNjKiXoqdDFGEcZGCZUNq7g26LdzKXKiE31RrFWsogPy1uMLrbG8ksQ8eJS6u6KFLjYUUSVJRuwmMD2SYCr8uG1TcRgM/0/*)#jz5n2pcp",
      "internal": false,
      "timestamp": 1686055705,
      "active": true
   },
   {
      "desc": "wpkh([0d9250da/84h/1h/0h]tpubDDGF9PnrXww2h1mKNjKiXoqdDFGEcZGCZUNq7g26LdzKXKiE31RrFWsogPy1uMLrbG8ksQ8eJS6u6KFLjYUUSVJRuwmMD2SYCr8uG1TcRgM/1/*)#rk3jh5ge",
      "internal": true,
      "timestamp": 1686055705,
      "active": true
   }
]
```

You can generate the descriptors with your Eclair node and import them into a Bitcoin node with the following commands:

```shell
$ eclair-cli getdescriptors | jq --raw-output -c > descriptors.json
$ cat descriptors.json | xargs -0 bitcoin-cli -rpcwallet=eclair importdescriptors
```

:warning: Importing descriptors can take a long time, and your Bitcoin Core node will not be usable until it's done

3) Configure Eclair to handle private keys for this wallet

Set `eclair.bitcoind.wallet` to the name of the wallet in `eclair-signer.conf` and restart Eclair.