# Liquidity Ads

## Tasks

- when leasing liquidity:
  - ensure we don't raise our relay fees above what was negotiated while the lease is active
  - disallow mutual close and force-close commands during the lease
  - disallow splice-out during the lease
  - change the format of commit txs
  - use a dedicated bitcoin wallet, on which we never lock utxos (abstract it away at the `BitcoinCoreClient` level)
- when buying liquidity:
  - when doing an RBF, must pay for the lease `fundingWeight` at the new feerate (add test)
  - verify our peer doesn't raise their relay fees above what was negotiated: if they do, send a `warning` and log it
  - ignore remote `shutdown` messages? Send a `warning` and log?
- when the lease expires, "clean up" commitment tx?
  - probably requires an `end_lease` message?
- lease renewal mechanism:
  - unnecessary, it's just a splice that uses the `request_funds` tlv?

## Liquidity ads plugin

Implement the business logic that decides how much to contribute when accepting a `request_funds` in a plugin.
Requires validation of the following fields from `open_channel2`:

- feerate -> it shouldn't be too high unless the buyer is paying for our inputs
- lockTime -> must not be too far away in the future and must match the lease start
- remote amount must allow paying the lease fees and the commit tx fees

## Spec feedback

- restore base routing fee field
- specify RBF behavior
- HTLC output timelocks?
