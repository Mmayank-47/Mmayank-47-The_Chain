Field UI walk on 001613558002048 — 2026-10-26

APK: android/app/build/outputs/apk/debug/app-universal-debug.apk
Node: n-2ae98e252a712b96
Keystore: hardware wrap

Screens
- 05_confirm.png — Help step 2: kind=need, coarse cell 0:0, exact location stays on phone
- 06_sent — step 3 justification, status "Held locally — no peer yet"
- 07_respond / 08_accepted — public card has coarse cell only; Accept reveals sealed hint, not a coordinate
- 09_map — "Exact pin hidden", water 0.00 m, coarse cell 0:0
- 10_mesh / 13_mesh.png — Advertising, Discovering, Handshake ready, waiting for a second phone, battery 100%
- 14_me.png / 15_hi.png — identity, EN/HI/MR; Hindi chrome after HI
- 12_selfcheck — on-device pipeline rows

Self-check (files/selfcheck-report.txt, also selfcheck-report.txt here)
PASS parser, verify, trust, battery, hazard, match, privacy (no public pos), justify EN/HI/MR, keystore, storage reload, ingress oversize, ingress replay, handshake, nearby radio
OPEN nearby_peer — Waiting for a second phone

Battery: Mesh "Battery 100% not charging" matches `dumpsys battery` level 100.
Suite 5 multi-phone stays OPEN.
