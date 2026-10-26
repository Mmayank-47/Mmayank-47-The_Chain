# Architecture (Kotlin phone build)

## What is decentralised

Every installed APK is one complete node. Peers discover each other over radio (Google Nearby Connections, `P2P_CLUSTER`). Claims, dispatches, hazard reports, trust evidence, and revocations converge through signed event logs, gossip, and anti-entropy. There is no server, no shared registry, and no internet permission.

## What needs pre-provisioning

- **QR co-presence enrollment** of peer public keys (strangers get only the low-rate distress lane).
- **Authority roots** (and optional role credentials / revocations) loaded by file or QR and then gossiped.
See `docs/PROVISIONING.md`.

## Known limits

- Nearby has concurrent-connection caps; multi-hop is the app’s gossip/DTN layer, not Nearby’s.
- OEM battery killers can stop the foreground service; M7 adds ignore-battery-optimizations and OEM-specific guidance.
- There is no global time source; HLC drift is bounded (`MAX_DRIFT_MS`).
- Wire v2 deterministic CBOR for signed on-air bodies is implemented in `:transport` (float ban, byte-exact round-trip, malformed fuzz); see `docs/WIRE.md`.
- Unattended BootReceiver → FGS can be delayed by OEM battery policy (observed on Nothing); unrestricted battery + OEM autostart steps are required for reliable cold-boot restart.

## Unverified on hardware

Suite 5 (multi-phone, airplane mode) has not passed yet — only one ADB device (`001613558002048`) is attached. Until Suite 5 is green on 2+ phones, only JVM golden vectors and the in-memory `:sim` harness prove protocol logic. One device does not prove mesh behaviour.

Also unverified on hardware: multi-hop A→B→C (P9), hostile-peer debug build (P8), 30‑minute idle battery drain (P10), Room migration durability beyond file snapshots + Keystore.
