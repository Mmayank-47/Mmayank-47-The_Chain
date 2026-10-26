Suite 4 evidence — 2026-10-26
device: 001613558002048 (Nothing A059 / Asteroids) — only ADB phone
package: org.thechain.app
nodeId (stable): n-2ae98e252a712b96
apk debug: android/app/build/outputs/apk/debug/app-universal-debug.apk
apk release: android/app/build/outputs/apk/release/app-universal-release.apk (R8 + ABI splits)

## 1) Reboot persistence
- Pre: nodeId_before.txt = n-2ae98e252a712b96 ; FGS running (fgs_before.txt)
- adb reboot → wait sys.boot_completed=1
- Post: nodeId_after.txt / nodeId_stable.txt = same id (Keystore persist)
- Cold boot auto-FGS: OEM (Nothing) delayed background start; process came up when task restored from Recents (~20s after boot). Log tags collided with system BootReceiver (since fixed → ChainBoot).
- Hardening after first reboot: BootReceiver now starts FGS directly + expedited RestartMeshWorker; LOCKED_BOOT_COMPLETED + QUICKBOOT; tag=ChainBoot.
- adb cannot inject BOOT_COMPLETED (SecurityException uid 2000) — see BLOCKED.md.
- Screenshots: after_reboot_fgs.png, shell_pre.png

Verdict: nodeId/Keystore PASS; BootReceiver hardened (ChainBoot + direct FGS + expedited WM); unattended cold-boot FGS on Nothing remains OEM-dependent until unrestricted battery / next real reboot with ChainBoot logs.

## 2) Accessibility
- TalkBack contentDescription on all nav tabs (Help/Respond/Map/Mesh/Me) + Help Next + help_text + OEM buttons.
- Touch targets: nav items 168px @ density 420 → 64dp. Primary Help/Respond/OEM buttons heightIn(min=64.dp).
- Body/action text ≥14sp (DEV badge 12sp only when DEV_COMPONENTS=true — off in debug/release shipping builds).
- Evidence: help_a11y.png, respond_a11y.png, oem_protect.png, uiautomator_*.xml
- Notes: a11y_notes.txt

Verdict: PASS (code + hierarchy dump).

## 3) Airplane mode launch
- settings airplane_mode_on=1; svc bluetooth enable + wifi enable → airplane=1 bluetooth_on=1 wifi_on=1 (airplane_radios.txt)
- force-stop → am start MainActivity
- ChainFgs: PhoneNode ready id=n-2ae98e252a712b96 hardwareWrap=true … no FATAL (logcat_airplane.txt, airplane_noclash.txt)
- Screenshot: airplane_launch.png
- Restored airplane_mode_on=0 after capture

Verdict: PASS.

## Release / M7 smoke (same session)
- assembleRelease SUCCESS (R8 minify + shrinkResources, ABI splits, minSdk26/target35, debug keystore signing)
- aapt dump permissions: no INTERNET (release_aapt_permissions.txt)
- OEM protect UI + REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (oem_protect.png; Nothing → generic steps)
- BatteryManager → PhoneNode.batteryLevel (logged on FGS ready); drain sample path ChainBattery every ~30s
- HI/MR critical Help/Respond/permissions/OEM strings filled (values-hi / values-mr)

## Suite 5
Still OPEN — need 2nd physical phone.
