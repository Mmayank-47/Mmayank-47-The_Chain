# Blocked

| Item | Why | Next |
|------|-----|------|
| Suite 5 P1–P10 | Only one physical phone on ADB | Attach a second (and third) phone |
| Wire v2 on Nearby path | CBOR encode/decode + float-ban in `:transport` (JVM green) | Wire Nearby claim frames through WireV2 on device |
| Suite 4 unattended cold-boot FGS on Nothing | OEM may delay BOOT_COMPLETED / background FGS until user opens app | Grant unrestricted battery via OEM UI; re-test `adb reboot` looking for `ChainBoot` + `RestartMeshWorker` before any UI |
| Simulate BOOT_COMPLETED via adb | SecurityException (shell uid 2000) | Use real reboot or instrumented test with privileges |
