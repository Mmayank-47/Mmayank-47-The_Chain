# The Chain

Decentralised emergency coordination that keeps working when the network does not: phones form a
mesh, verify claims without a server, route responders around floodwater, protect victims' privacy,
and stay honest under attack. Everything is plain Python with no third-party dependencies.

## Quick start

```bash
make test      # all unit tests          (python -m unittest discover -s tests -t .)
make attacks   # M11 red-team suite      (exit code 1 if a defense fails)
make sim       # baseline Monte-Carlo table
make check     # test + attacks, what CI runs
```

A single module: `python -m unittest tests.test_m7`.

## Layout

| Package | What it does |
|---|---|
| `core/` | Shared types (priorities, HLC with a drift bound, wire `Message`), CRDTs, Bloom filter, `crypto` (the single provider M1/M8/M9 share) |
| `m0_transport/` | Mesh gossip, store-carry-forward (DTN), priority outbox, **ingress guard** for everything off the air |
| `m1_identity/` | Hardware-bound keys, offline credentials, revocation, co-presence proofs, rate budgets |
| `m2_parser/` | Zero-shot, code-mixed (English / Hindi / Marathi) claim parser, text is data never instructions |
| `m3_verify/` | `p_valid` for each claim from log-odds signals and challenge-response |
| `m4_hazard/` | Location as a distribution, hazard CRDT grid, monotonic flood model, risk-averse routing |
| `m5_trust/` | Beta reputation, vouch-graph trust, collusion discount |
| `m6_battery/` | Battery telemetry checks, urgency, relay selection, `p_reach` |
| `m7_matching/` | Triage, utility and dispatch gate, CBBA auction, escalation |
| `m8_merge/` | Signed event logs, conflict rules, leases, partition reconciliation |
| `m9_privacy/` | Two-stage disclosure, rotating pseudonyms, sealed fixed-size envelopes, cover traffic |
| `m10_ux/` | Template-grounded justification and the responder prompt state machine |
| `m11_redteam/` | Defenses and the attack suite (each attack run with the defense off and on) |
| `sim/` | M12 harness: world, baseline policy, metrics, Monte-Carlo runner |

## Dev stand-ins (read before trusting anything)

The crypto is **deliberately insecure**: `core.crypto.DevProvider` is HMAC-SHA256 where a public
key equals the secret. It exists so the protocols, and their tests, are real while the primitives
are swappable in one place (`core.crypto.set_provider`). Do not deploy it. Real X25519, Ed25519
and AEAD are Phase F1.

M8 has no default signer: construct replicas with the shared M1 registry
(`m1_identity.keys.dev_fleet([...])` in the sim and tests).

## Trust boundary

Nothing off the air is trusted. `m0_transport.ingress.IngressGuard` derives message priority from an
allowlist of kinds, caps TTL, size and Bloom shape, and rate-limits per hardware identity before
gossip sees a frame. M8 drops events whose HLC leads local time by more than five minutes.

## Status

Phases A to F are tracked in the project plan. Phase A (hygiene and hardening) is done; the stack
is not yet wired end to end, that is the per-phone orchestrator in Phase C.
