# Spec: core

Taken from `core/types.py`, `core/crdt.py`, `core/bloom.py`, `core/ratelimit.py`, `core/claim.py`, `core/dispatcher.py`, `core/crypto.py`, and `core/claim_adapters.py`.

## Constants

- Priorities, low to high urgency on the wire: DISTRESS 0, DISPATCH 1, HAZARD 2, CHATTER 3. Lower is sent first.
- Dispatch lattice: PROPOSED, ACCEPTED, EN_ROUTE, ARRIVED, RESOLVED. Comparison is the integer order. A state only moves forward (`max`).
- `event_priority`: kind `claim` → DISTRESS, `hazard` → HAZARD, anything else including a non-string → DISPATCH.
- `MAX_DRIFT_MS` = 300_000. `MAX_COUNTER` = 2^20.
- Claim limits: id length 1..64, absolute coordinate ≤ 1e7, radius 0..5000, time ≥ 0, parsed canonical JSON ≤ 900 bytes.
- Critical features for `need_of`: `not_breathing`, `severe_bleeding`, `unconscious`. Those, or a `medic` tag, need a medic. A `boat` tag needs a boat. Otherwise the need is absent.
- Dispatcher kinds: `m8.event`, `m9.case`, `m9.pin`, `m9.card`, `m9.chat`, `m9.ack`, `m9.sealed`, `m1.revoke`, `m3.challenge`, `m3.response`. `m9.pin` is the GNSS fix sent with a help request. `m9.card` is name+occupation after handshake. `m9.chat` is a short message addressed to one saved contact. `m9.ack` is the mesh-wide Accept acknowledgement that ends the distress window.

## HLC

Order is `(wall, counter, node)` with string node ids compared lexicographically.

`now()`: if physical time is greater than the stored wall, set wall to it and counter to 0. Otherwise increment the counter.

`update(remote)` rejects when wall &lt; 0, counter is outside 0..MAX_COUNTER, or remote wall leads physical time by more than the drift bound. A null bound disables the lead check. The new stamp is `max(local wall, remote wall, physical)`. The counter follows Kulkarni: increment when the winning wall equals local or remote, reset to 0 only when physical time is strictly newest.

## LWW map

`set` replaces only when the new HLC is strictly greater. `delete` writes a null tombstone. `get` of a tombstone returns the default. `merge` is pointwise `set`.

## Bloom

SHA-256 of the UTF-8 item. Hash `i` is the big-endian unsigned 32-bit integer at offset `4*i` (shorter tail allowed), modulo `m`. Bits are an arbitrary-precision integer, bit 0 the least significant. `for_capacity` matches CPython: `n = max(n, 16)`, `m = max(64, int(-n ln fp / ln(2)^2))`, `k = max(1, round(m/n ln 2))` with banker's `round`.

## Token bucket

Tokens refill by `rate * max(0, now - t)`, capped at `burst`. Time does not move backwards. A call spends `cost` only when the balance covers it.

## Claim id

SHA-256 hex, first 16 chars, of the canonical body `{n, s, p: [float, float], r: float, t: float, d: parsed}`. `verify` is false on a bad shape, an id that does not match, or a signature the signer rejects. `from_json` checks shape only.

## Message id

SHA-256 hex, first 16 chars, of `{k, o, d, h}`. TTL and created are not part of the id. `expired` is `now - created > ttl`.

## Cell

`cell_of` is `floor(x / 500)` and `floor(y / 500)` joined by `:`. It is the coarse cell. It is not a coordinate.
