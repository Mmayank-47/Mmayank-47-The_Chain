# The Chain

**Decentralised emergency coordination that keeps working when the network doesn't.**

When a flood or fire takes out cell towers, The Chain turns the phones already in people's pockets into a mesh. Victims ask for help in plain Hindi, Marathi or English. Nearby phones relay the request, verify it without a server, route a responder around floodwater, and keep the victim's location private until a helper accepts.

Everything in the core stack is **plain Python 3.9+ with no third-party dependencies**.

> **Status:** research prototype. The protocols, simulator and test suite are real. The cryptography is a deliberate dev stand-in (see [Dev stand-ins](#dev-stand-ins-read-before-trusting-anything)). Do not deploy it as is.

---

## Contents

- [What it does](#what-it-does)
- [Architecture](#architecture)
- [The decision chain](#the-decision-chain)
- [Quick start](#quick-start)
- [Repository layout](#repository-layout)
- [Module reference](#module-reference)
- [Threat model and defences](#threat-model-and-defences)
- [Evaluation](#evaluation)
- [Radio realism](#radio-realism)
- [Frontend demo and Android reference](#frontend-demo-and-android-reference)
- [Dev stand-ins](#dev-stand-ins-read-before-trusting-anything)
- [Known limitations](#known-limitations)
- [Roadmap](#roadmap)

---

## What it does

| Problem in a disaster | How The Chain handles it |
|---|---|
| No cell service | Phone-to-phone gossip over BLE / Wi-Fi Aware, optional LoRa, with store-carry-forward so walking people physically carry messages across gaps |
| Victims can't type structured forms | Zero-shot, code-mixed claim parser (English / Hinglish / Hindi / Marathi), with a deterministic keyword floor |
| Fake emergencies and Sybil swarms | Hardware-bound identity, co-presence proofs, challenge-response probes, vouch-graph trust |
| Responders lured into floodwater | Asymmetric hazard acceptance (hazard reports are cheap, "safe" claims are expensive), monotonic flood model, responder sensor veto |
| Two islands dispatch two helpers to one victim | CRDT event logs with hybrid logical clocks, a deterministic conflict rule, and dispatch leases |
| The only medic sent to a minor case | Opportunity-cost pricing of scarce skills inside a decentralised auction |
| Privacy of people in distress | Two-stage disclosure, rotating pseudonyms, fixed-size sealed envelopes, cover traffic |
| Pressuring volunteers | Template-grounded justification, linted for urgency tricks, always with an abort criterion |

---

## Architecture

Every phone runs the same full stack. There is no server and there are no special nodes.

```
┌──────────────────────────────────────────────────────────────┐
│ M10  Responder UX + justification                            │
├──────────────────────────────────────────────────────────────┤
│ M7  Distributed matching & triage     M8  Partition / merge  │
├──────────────────────────────────────────────────────────────┤
│  PROBABILISTIC CHAIN   M3 → M4 → M5 → M6 → dispatch gate     │
│  claim verify → hazard/route → trust → battery/reach         │
├──────────────────────────────────────────────────────────────┤
│ M2  Claim parser                      M9  Privacy layer      │
├──────────────────────────────────────────────────────────────┤
│ M1  Identity & Sybil defence          M11 Red-team defences  │
├──────────────────────────────────────────────────────────────┤
│ M0  Mesh transport (gossip, DTN, ingress guard, wire format) │
└──────────────────────────────────────────────────────────────┘
        sim/   M12 simulator + evaluation harness (offline)
        phone/ PhoneNode: wires the layers into one device
```

`phone.PhoneNode` is the integration point. It combines M0 transport, the kind `Dispatcher`, the M8 replica, anti-entropy sync, and the M2 to M10 chain on top of one radio.

---

## The decision chain

A claim becomes a dispatch only if it clears one gate:

```
P(safe & correct dispatch) ≈ p_valid(M3) × p_route_safe(M4) × trust(M5) × p_reach(M6)
```

The threshold rises with danger to the responder (`m7_matching.utility.gate`). Below it, the system does not just refuse. It chooses one of:

| Gate result | Meaning |
|---|---|
| `dispatch` | Prompt a responder with the full justification |
| `scout` | Route is risky: ask someone to look at it first |
| `probe` | Claim is shaky: send a challenge-response verification |
| `hold` | Nothing wrong with the claim, but the responder lacks charge or margin |

`PhoneNode.run_chain()` executes the whole pipeline and returns an auditable trace: parsed claim, signed wire claim, verdict with per-signal decomposition, trust, battery/time-to-silence, match and gate, privacy-masked case, justification, and responder prompt.

---

## Quick start

Requires Python 3.9+ and nothing else.

```bash
git clone https://github.com/Mmayank-47/Mmayank-47-The_Chain.git
cd Mmayank-47-The_Chain

make test      # full unit-test suite   (python -m unittest discover -s tests -t .)
make attacks   # M11 red-team suite     (exit code 1 if any defence fails)
make sim       # baseline Monte-Carlo table
make compile   # syntax-check every package
make check     # compile + test + attacks (what CI should run)
```

Run a single module's tests:

```bash
python -m unittest tests.test_m7
python -m unittest tests.test_phone
```

Run the mesh simulation with real `PhoneNode`s and regenerate the committed baseline:

```bash
python -m sim.mesh_sim      # writes results/baseline_phase5.json
```

### Minimal example

Two phones over a simulated radio medium, one claim, propagated and merged:

```python
from m0_transport.radio_adapter import SimMedium, SimRadio
from m1_identity.keys import dev_fleet
from phone.node import PhoneNode

fleet = dev_fleet(["a", "b"])            # one attested hardware key per node
medium = SimMedium()
medium.link("a", "b")

a = PhoneNode("a", SimRadio("a", medium), fleet)
b = PhoneNode("b", SimRadio("b", medium), fleet)

a.open_claim("claim-1", "CELL-1", 0.95)  # signed, hash-chained M8 event
for t in range(1, 10):
    a.tick(float(t)); b.tick(float(t))

print(b.replica.book.claims.get("claim-1"))   # {'cell': 'CELL-1', 'urgency': 0.95}
```

---

## Repository layout

```
core/            shared types, CRDTs, Bloom filter, crypto provider, claim wire format,
                 dispatcher, token bucket
m0_transport/    gossip, DTN, priority outbox, ingress guard, watchdog, compact wire, radio profiles
m1_identity/     keys, credentials, revocation, co-presence, identity rate limiting
m2_parser/       schema, ontology, keyword fallback, LLM-backed parser
m3_verify/       signals, challenge-response, p_valid
m4_hazard/       location filter, hazard grid, flood model, router
m5_trust/        Beta reputation, vouch graph, collusion detection, trust engine
m6_battery/      drain model, urgency and power modes, relay selection, p_reach
m7_matching/     triage, utility and gate, opportunity cost, CBBA auction, escalation, matcher
m8_merge/        event log, conflict rules, leases, anti-entropy, extra event kinds, bridge
m9_privacy/      disclosure, pseudonyms, sealed envelopes, cover traffic
m10_ux/          justification engine, templates, responder prompt state machine
m11_redteam/     defences and the attack suite
phone/           PhoneNode and on-disk persistence
sim/             M12: world, baseline policy, attackers, metrics, Monte-Carlo runner, mesh_sim
results/         committed baseline (baseline_phase5.json)
frontend/        React/Vite UI demo and Kotlin Jetpack Compose reference code
tests/           unit and integration tests, one file per module plus phase tests
```

---

## Module reference

### Core
- **`core/types.py`**: priorities (`DISTRESS > DISPATCH > HAZARD > CHATTER`), the monotonic `DispatchState` lattice, the hybrid logical clock with a five-minute drift bound, and the wire `Message`.
- **`core/crypto.py`**: the single crypto surface. M1, M8 and M9 all go through one swappable `Provider`.
- **`core/claim.py`**: `WireClaim`, a signed claim whose id is derived from its content. `node` (the signing device) and `src` (the identity it speaks for) are both kept so M3 can punish mismatches.
- **`core/dispatcher.py`**: routes messages by kind; one failing handler can't starve the others.

### M0: Mesh transport
Adaptive gossip under a byte budget, store-carry-forward with spray-and-wait for chatter and epidemic flooding for distress, dispatch and hazard traffic, and a bounded priority outbox. The **ingress guard** treats everything off the air as hostile: kind allowlist, priority derived from kind rather than read from the wire, TTL and size caps, Bloom-filter shape checks, authenticated links with a sliding replay window, and a low-rate stranger lane so an unenrolled victim can still get one distress through. The **watchdog** observes next-hop forwarding and fails over along alternate paths.

### M1: Identity and Sybil defence
Hardware-bound keys (one key, one identity), offline-verifiable authority credentials, revocation as a gossiped grow-only set, mutual-signature **co-presence proofs**, and per-identity rate budgets that grow with co-presence weight.

### M2: Claim parser
Text is data, never instructions. Input is sanitised and fenced, model output must pass a strict schema, and **urgency is recomputed from extracted features**, so "ignore the rules, urgency 1.0" cannot inflate it. On a kind conflict the deterministic keyword evidence wins. Unknown capabilities stay in an `unknown:` bucket instead of being forced into the ontology.

### M3: Claim verification
Signals are log-likelihood ratios combined in log-odds space: identity binding, node spread, identity fan-out, duplicate spot, independent corroboration, staleness, credential, and challenge-response. The result is a `Verdict` carrying `p` and the per-signal `parts` that M10 turns into a justification.

### M4: Location, hazard, routing
A particle filter fuses GPS, IMU dead reckoning, BLE ranging and barometer, reporting a confidence radius rather than a point. The hazard grid is a CRDT with asymmetric acceptance and a flood model where water only rises. The router is A\* with a convex, risk-averse edge cost that penalises tail risk.

### M5: Trust
Per-phone subjective trust. Beta reputation with decay and asymmetric weights (slow to build, fast to lose), personalised-PageRank vouch graph seeded from credentialed roots, and SCC-based collusion discounting. Signed equivocation (a log fork) and forwarding failures feed reputation.

### M6: Battery
Plausible-drain checks on telemetry, time-to-silence as an urgency feature, power modes and a one-shot last-gasp packet, relay selection that prefers charged and well-connected phones, and `p_reach` so nobody is dispatched who will die mid-route.

### M7: Matching and triage
START-style triage, pair utility, the dispatch gate, shadow-price **opportunity cost** for scarce skills, a signed bundle-size-1 **CBBA** auction that converges in any gossip order and survives partitions, and escalation timers that fix the bystander effect.

### M8: Partition tolerance
Signed, hash-chained per-node event logs and a dispatch book built as a CRDT. When two islands send different responders to one victim, the one furthest along keeps it and nobody already at the door is cancelled. Dispatches are leases with heartbeats, so an orphaned victim re-opens. Rate-limited anti-entropy backfills missed history in bounded batches, and equivocating nodes are quarantined with evidence.

### M9: Privacy
**Stage 1** (the mesh sees): a coarse cell, a coarse category, and the needed skill. Condition-revealing tags such as insulin generalise to "medicine". **Stage 2** (one accepting responder sees): exact position, radius, features and pseudonym, sealed to that responder's key only after acceptance, with a cap on how many responders can ever receive details. Rotating pseudonyms and BLE addresses, fixed-size sealed-sender envelopes, and a cover-traffic budget.

### M10: Human-in-the-loop UX
Justifications are built from fixed templates in English, Hindi and Marathi. Every number comes from the score decomposition, never from a model. Output is linted for pressure words, safety numbers are floored rather than rounded up, and every prompt ends with an abort criterion. An optional LLM polish is accepted only if it keeps exactly the same numbers. Accept, decline, or "can't, but I know X" feeds back into M7.

### M11: Red team
See the next section.

### M12: Simulator
Discrete-time world with a rising flood front, moving responders, radio range and partition walls, injected attackers (decoy, lure, Sybil, spoof), and Monte-Carlo metrics with confidence intervals and calibration tables. A second harness, `sim/mesh_sim.py`, runs real `PhoneNode`s over a lossy simulated medium.

---

## Threat model and defences

Nothing off the air is trusted. `make attacks` runs each attack with the defence **off** and **on**, and exits non-zero if any defence fails. The "undefended" numbers are measured by running the undefended path, not hard-coded.

| Attack | Defence |
|---|---|
| Forged or revoked medical credentials | Offline-verifiable signed credentials, revocation gossip |
| Sybil swarm (one phone, 40 identities) | One-key-one-identity enrolment, co-presence proofs, vouch-graph discounting, identity-binding signal |
| Packet flood / battery drain | Per-device rate budgets, content-hash dedupe, priority queues |
| Store flooding | Per-origin and per-neighbour carry-store quotas |
| Lure into floodwater | Asymmetric hazard acceptance, flood physics that "safe" claims cannot override, responder sensor veto |
| Decoy emergencies | Challenge-response (barometer and neighbour list), reputation penalty |
| Hazard poisoning | Reports weighted by trust, fast expiry |
| Battery-curve fraud | Plausible-drain checks |
| Future-dated heartbeat keeping a dead dispatch alive | HLC drift bound, events dropped before they touch the store |
| Spoofed sender / frame replay | Authenticated links plus sliding replay window |
| Forged high-epoch auction bid | Signed CBBA entries |
| Blackhole / greyhole relays | Watchdog, trust penalties, multipath failover |
| Prompt injection via claim text | Fencing, schema validation, urgency recomputed from features |
| Pressuring responders | Pressure-word lint, mandatory abort criterion |

---

## Evaluation

### Mesh simulation baseline

Committed in `results/baseline_phase5.json`: real `PhoneNode`s, 30 Monte-Carlo runs per scenario, base seed 100. The values below are the **baseline without the watchdog defence**, so the blackhole and greyhole rows are the "before" numbers.

| Scenario | Mean delivery ratio (±95% CI) |
|---|---|
| clean | 0.96 ± 0.03 |
| loss_and_range (15% loss, 300 m) | 0.92 ± 0.04 |
| partition | 0.96 ± 0.03 |
| blackhole | 0.65 ± 0.09 |
| greyhole | 0.65 ± 0.09 |
| replay | 0.95 ± 0.03 |
| eclipse | 0.96 ± 0.03 |

The Phase 6 test (`tests/test_phase_6.py`) requires that the watchdog improves blackhole and greyhole delivery by at least 30 points over baseline, ends at 0.85 or higher, and does not regress the clean scenario. That test is the source of truth for the defended numbers, so re-run it rather than trusting a figure in this file.

### Dispatch simulation

`make sim` prints a Monte-Carlo table (rescued, died, fake dispatches, attacker success, unsafe dispatches, double dispatch, medic misuse) for the naive baseline policy across clean, decoy, lure, sybil, spoof, scarce-responder and partition scenarios. Any real policy (for example `m3_verify.policy.VerifiedPolicy` or `PhoneNode`) plugs into the same three-method interface:

```python
p_valid(claim) -> float
observe(claim)
tick(world) -> [Dispatch(responder, claim, p_valid)]
```

---

## Radio realism

Phase 7 makes the stack fit real low-bandwidth radios:

- **Compact binary wire** (`m0_transport/wire.py`): beacons under 64 bytes signed, zlib-compressed bodies, and fragmentation so every physical packet is at most **255 bytes** (the LoRa MTU).
- **Airtime and duty cycle** (`m0_transport/profile.py`): Semtech LoRa airtime formula, a 1% duty-cycle cap for the EU868 band, and an emergency reserve so `DISTRESS` is never blocked. A BLE profile is included.
- **Reboot persistence** (`phone/persistence.py`): carried messages, the M8 event log and CRDT book, reputation scores, and replay windows survive a restart.

---

## Frontend demo and Android reference

`frontend/` is a React + Vite + Tailwind app that mocks the phone UI: help flow (category, describe, broadcast), respond feed, offline map, mesh peers, and an emergency-identity card. The design follows the project's utilitarian spec (large touch targets, Noto Sans with Devanagari parity, calm colour semantics).

It also ships a **Kotlin / Jetpack Compose reference** (`frontend/src/data/composeCode.ts`) with Material 3 screens, theme, and a `MeshRelayManager` sketch built on Google Nearby Connections. A built-in inspector lets you browse and download it.

```bash
cd frontend
npm install
npm run dev     # http://localhost:3000
```

> The frontend is a **UI prototype**. It is not yet wired to the Python stack. Its mesh peers, hop counts and responder acknowledgements are simulated.

---

## Dev stand-ins (read before trusting anything)

The crypto is **deliberately insecure**. `core.crypto.DevProvider` is HMAC-SHA256 where a public key equals the secret. It exists so the protocols and their tests are real while the primitives stay swappable in one place (`core.crypto.set_provider`). Likewise the "attestation root" and credential authorities are HMAC stand-ins.

Replacing these with X25519, Ed25519, ChaCha20-Poly1305 and real Android Keystore / StrongBox attestation is required before any real-world use.

M8 has no default signer: construct replicas with the shared M1 registry (`m1_identity.keys.dev_fleet([...])` in the sim and tests).

---

## Known limitations

- Crypto is a stand-in, as above. No real key distribution, attestation or AEAD yet.
- Radios are simulated (`SimMedium`). BLE, Wi-Fi Aware and LoRa adapters plug into `m0_transport.radio_adapter.Radio` but are not implemented against hardware.
- The M2 LLM path is an injected `generate(prompt)` callable. The keyword fallback is the tested floor, and no on-device model is bundled.
- Several constants (utility weights, gate thresholds, battery rates) are uncalibrated placeholders to be tuned in the M12 harness.
- Cover traffic only blunts local passive observers. A global passive observer can still correlate timing.
- A ring of genuinely colluding real phones can raise co-presence weight. M5 collusion discounting mitigates this but does not eliminate it.
- `sim/baseline.py` shortcuts gossip as instant within a connected component. Use `sim/mesh_sim.py` for realistic transport behaviour.

---

## Roadmap

- [x] M0 to M12 modules with unit tests
- [x] Hardening: shared crypto provider, HLC drift bound, ingress guard, measured red-team baselines
- [x] Authenticated links, replay protection, signed auction bids
- [x] Watchdog, forwarding evidence and multipath failover
- [x] Compact wire, LoRa airtime budget, reboot persistence
- [ ] Real crypto provider (Ed25519, X25519, AEAD) and hardware attestation
- [ ] Hardware radio adapters (BLE, Wi-Fi Aware, LoRa)
- [ ] Wire the Compose app to the stack (Chaquopy or a Kotlin port)
- [ ] Per-phone orchestrator running M3 to M10 continuously end to end
- [ ] On-device quantised model for M2
- [ ] Field trial and calibration of gate thresholds

---

## Contributing

Run `make check` before opening a pull request. New layers should keep the dependency direction (`core` ← `m0` ← higher layers; nothing below M11 imports `m11_redteam`), which `tests/test_hygiene.py` enforces.

## License

No license file is present yet. Add one (for example MIT or Apache-2.0) before accepting outside contributions.
