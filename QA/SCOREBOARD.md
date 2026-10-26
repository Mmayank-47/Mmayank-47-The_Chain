# QA scoreboard

| Suite | Case | Phones | Result | Evidence |
|-------|------|--------|--------|----------|
| 1 | JVM unit + golden vectors (all modules) | 0 | PASS (2026-10-26) | Gradle `:*-test` / `QA/run_all.ps1` |
| 2 | JVM multi-node sim (3-node claim) | 0 | PASS (logic only) | `:sim:test` `MeshHarnessTest.threeNodesPropagateOpenClaim` |
| 2 | partition/heal convergence | 0 | PASS (2026-10-26, logic only) | `:sim:test` `Suite2SimTest.partitionHealConvergesClaimsAndHeads` |
| 2 | CBBA duplicate dispatch / lease winner | 0 | PASS (2026-10-26, logic only) | `:sim:test` `Suite2SimTest.cbbaDuplicateDispatchOneWinnerAfterHeal` |
| 2 | blackhole/greyhole watchdog failover | 0 | PASS (2026-10-26, logic only) | `:sim:test` `Suite2SimTest.blackholeGreyholeWatchdogFailover` |
| 2 | replay / forged / oversize IngressGuard | 0 | PASS (2026-10-26, logic only) | `:sim:test` `Suite2SimTest.replayForgedOversizeFramesDropped` |
| 2 | clock-drift attack (HLC + handshake skew) | 0 | PASS (2026-10-26, logic only) | `:sim:test` `Suite2SimTest.clockDriftAttackRejected` |
| 2 | 50-node randomized soak (fixed seed) | 0 | PASS (2026-10-26, logic only) | `:sim:test` `Suite2SimTest.fiftyNodeRandomizedSoak` |
| 3 | no INTERNET in merged manifest | 1 | PASS (2026-10-26) | `QA/evidence/m4/`; release aapt `QA/evidence/suite4/release_aapt_permissions.txt` |
| 3 | no crypto-fake / :sim on app classpath | 0 | PASS (assertShippingClasspath) | `:app` build |
| 4 | instrumented single phone (boot, FGS, Keystore, UI shell) | 1 | PASS (partial) | `QA/evidence/m4/` |
| 4 | force-stop → relaunch restores nodeId/keystore | 1 | PASS (2026-10-26) | `QA/evidence/m5/` nodeId stable |
| 4 | reboot persistence (nodeId + Keystore) | 1 | PASS (2026-10-26) | `QA/evidence/suite4/` nodeId_before/after |
| 4 | reboot auto-restart FGS via BootReceiver/WM | 1 | PARTIAL | Nothing OEM delayed; FGS after Recents; BootReceiver hardened (ChainBoot + direct FGS) |
| 4 | accessibility (TalkBack labels, 64dp, ≥14sp) | 1 | PASS (2026-10-26) | `QA/evidence/suite4/a11y_notes.txt` + dumps |
| 4 | airplane mode launch (BT+Wi‑Fi on) | 1 | PASS (2026-10-26) | `QA/evidence/suite4/airplane_*` |
| 5 | P1–P2 signed hs + claim (2 phones) | 2 | OPEN | code ready; only 1 ADB device |
| 5 | P3–P10 multi-phone | 2+ | OPEN | only 1 ADB device (`001613558002048`) |

Cases requiring more phones than connected stay **OPEN**. Suite 2 PASS is **logic only** — not phone proof. Suite 5 must not be marked PASS.
