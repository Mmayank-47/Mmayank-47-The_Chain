M4 boot evidence — 2026-10-26T10:02:08.5807457+05:30
device: 001613558002048
apk: android/app/build/outputs/apk/debug/app-universal-debug.apk
pid: 15744
install: Success
launch: am start org.thechain.app/.MainActivity

Key log lines:
- ActivityManager: Background started FGS: ChainForegroundService
- ChainFgs: PhoneNode ready id=n-2ae98e252a712b96 hardwareWrap=true
- NearbyRadio: advertising as n-2ae98e252a712b96
- NearbyRadio: discovering
- NearbyMediums: Started BLE advertising for service org.thechain.mesh.aid
- ActivityTaskManager: Displayed org.thechain.app/.MainActivity

INTERNET: PASS — absent from merged manifest and aapt dump permissions
