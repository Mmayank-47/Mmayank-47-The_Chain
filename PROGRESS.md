# The Chain — Kotlin rewrite progress

Python packages remain the reference and offline simulator. The shipping product is the Gradle multi-module tree under `android/`.

## Milestones

| Tag | Status | Notes |
|-----|--------|-------|
| **M1** core + identity + merge on golden vectors | **done** | Canonical JSON, HLC, CRDT, Bloom, claims, Ed25519 registry, handshake, EventStore/Replica |
| **M2** transport + parser + verify/trust/battery/hazard | **done** | IngressGuard, CarryStore, gossip, Wire v1 + WireV2 deterministic CBOR (float-ban), M2–M6 ports |
| **M3** matching + privacy/ux + redteam; JVM mesh | **done** | CBBA, M9 public/sealed (Sealer), M10 EN/HI/MR, redteam vs `vectors/redteam.json`, PhoneNode + SimMedium 3-node claim |
| **M4** Android app boots on a real phone | **done** | FGS + Keystore + Nearby + Compose shell on `001613558002048`; no INTERNET; evidence `QA/evidence/m4/` |
| **M5** Nearby two-phone claim | **in progress** | Signed hs + Help/Respond claim path on 1 phone (`QA/evidence/m5/`); Suite 5 P1–P2 **blocked on 2nd phone** |
| **M6** P1–P5 / 3-phone | open | |
| **M7** OEM / battery / a11y / release | **in progress** | OEM protect UI + battery opt; BatteryManager→PhoneNode; a11y 64dp/TalkBack; HI/MR strings; `assembleRelease` R8+ABI splits; `QA/run_all.ps1` Suites 1–3; Suite 4 reboot/a11y/airplane evidence in `QA/evidence/suite4/` |

## Evidence rule

Suite 2 (JVM sim) proves logic only. Suite 5 on 2+ physical phones in airplane mode (BT+Wi‑Fi on) is the only proof the product works on phones. One device is currently attached over ADB (`001613558002048`); multi-phone cases stay OPEN until more hardware is connected.

### Suite 2 evidence (2026-10-26, logic only)

`:sim:test` — 7/7 PASS (`MeshHarnessTest` + `Suite2SimTest`): partition/heal + AE head convergence; CBBA + DispatchBook single winner after heal; watchdog blackhole/greyhole failover; IngressGuard replay/forge/oversize; HLC + handshake clock-drift rejection; 50-node seeded soak (≥85% claim coverage). Not phone proof.

### M7 / Suite 4 notes (2026-10-26)

- Reboot: nodeId `n-2ae98e252a712b96` + hardware Keystore stable across `adb reboot`. Unattended FGS on Nothing was delayed until task restore; BootReceiver now direct-starts FGS + expedited WM (`ChainBoot` tag).
- Airplane + BT/Wi‑Fi: launch OK, FGS up, no crash (`QA/evidence/suite4/`).
- Release: R8 + shrinkResources + ABI splits; no INTERNET in release aapt permissions.
- Runners: `QA/run_all.ps1` / `QA/run_all.sh` + `QA/README.md`.

## Vectors

`tools/gen_vectors.py` dumps Python outputs into `vectors/*.json`. Kotlin tests load them via `vectors.dir`. Floats: absolute 1e-9. Discrete outputs: exact.
