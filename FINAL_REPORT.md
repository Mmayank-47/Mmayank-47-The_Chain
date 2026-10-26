# The Chain — Kotlin rewrite final report (interim)

Date: 2026-10-26. This report is honest about what hardware proved and what remains OPEN.

## Product shape

- Native Kotlin multi-module APK under `android/` (`org.thechain.app`).
- No Python at runtime, no WebView-to-localhost, **no `INTERNET` permission** (debug + release aapt / merged manifest).
- Real Ed25519 via Tink, Keystore-wrapped on device (`hardwareWrap=true` on Nothing A059). `:crypto-fake` is not on the `:app` classpath (`assertShippingClasspath`).
- Radio: Nearby Connections `P2P_CLUSTER`, service id `org.thechain.mesh.aid`, signed handshake before `endpointId→nodeId` bind.

Python tree remains the reference spec and offline simulator (not shipped).

## Suite results

| Suite | Result | Notes |
|-------|--------|-------|
| 1 JVM unit + golden vectors | **PASS** | `QA/run_all.ps1` / module `:*-test` |
| 2 JVM multi-node sim | **PASS (logic only)** | `:sim:test` 7/7 — partition/heal, CBBA lease, watchdog, hostile frames, clock-drift, 50-node soak |
| 3 Static guards | **PASS** | no INTERNET; no fake crypto / `:sim` on app classpath |
| 4 Single phone | **PASS with PARTIAL** | boot, FGS, Keystore, force-stop, a11y, airplane+BT/Wi‑Fi; unattended Boot→FGS **PARTIAL** on Nothing OEM |
| 5 Multi-phone P1–P10 | **OPEN** | only 1 ADB device; code path ready (signed hs + Help/Respond claim) |

Full case table: `QA/SCOREBOARD.md`.

## Evidence paths

| Milestone / suite | Path |
|-------------------|------|
| M4 boot | `QA/evidence/m4/` |
| M5 handshake + claim UI smoke | `QA/evidence/m5/` |
| Suite 4 reboot / a11y / airplane / release | `QA/evidence/suite4/` |
| Runners | `QA/run_all.ps1`, `QA/run_all.sh`, `QA/README.md` |

Stable nodeId on device under test: `n-2ae98e252a712b96`.

## Bugs / fixes tracked

See `QA/BUGS.md` and `QA/BLOCKED.md`. Notable OPEN/PARTIAL:

- Suite 5 blocked on second physical phone.
- Nothing OEM: BootReceiver/WM may not raise FGS until task restore unless battery unrestricted / autostart granted.
- Room event-log migrations and Map offline tiles not fully landed (file snapshots + Keystore persist today).

Commit hashes for individual fixes: not recorded in this interim report (no release-tagged DoD commit series requested yet).

## Time-to-discover / battery

- Multi-phone discovery latency (P1) and 30‑minute idle drain (P10): **not measured** — need 2+ phones and a timed idle run.
- Single-phone Nearby advertising/discovering starts with FGS; peer count stays 0 without a second device.

## Reproduction (current hardware)

1. Install `android/app/build/outputs/apk/debug/app-universal-debug.apk` (or release ABI split) on phone A.
2. Grant BT / Nearby / notifications / location as prompted; optionally OEM protect from Me.
3. Confirm notification FGS + Mesh tab; Help 3-step originates a local claim; Respond shows accept path.
4. For Suite 5: attach phone B, install same APK, cross-copy `files/me.pub` → `files/peers/<nodeId>.pub` on each, restart mesh, airplane mode with BT+Wi‑Fi on, run P1–P10 per project GOAL; save logcat+screenshots under `QA/evidence/`.

## OPEN items (DoD incomplete until these close)

1. Suite 5 P1–P10 on ≥2 phones, 5/5 repeats, evidence under `QA/evidence/`.
2. Unattended cold-boot FGS without unlocking UI (OEM unrestricted battery).
3. P8 hostile debug build on a second device; P9 3-phone multi-hop; P10 battery drain ≤8%/h.
4. Optional: Room migrations, speech offline path, Map coarse-cell tiles polish.

## Definition of Done checklist

- [x] Native Kotlin APK, no Python/WebView server, no INTERNET, installed on connected phone(s)
- [x] Suites 1–3 green
- [x] Suite 4 green on runnable cases (PARTIAL noted for auto FGS)
- [ ] Suite 5 green for every case hardware allows, 5/5 repeats
- [x] Real Ed25519/AEAD path; DevProvider not in shipping build
- [x] `docs/ARCHITECTURE.md` states decentralisation, provisioning, limits, unverified hardware
- [ ] This report completed after two consecutive full Suite 5 runs
