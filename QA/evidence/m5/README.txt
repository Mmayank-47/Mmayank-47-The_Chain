M5 prep evidence — 2026-10-26
device: 001613558002048 (only ADB phone)
apk: android/app/build/outputs/apk/debug/app-universal-debug.apk
package: org.thechain.app
install: Success (adb install -r)

## What landed
- Signed Nearby handshake (HandshakeCoordinator / HandshakeTable) before endpoint→nodeId bind
- Mesh frames dropped until verified; IngressGuard sees verified node ids
- Help 3-step claim originate → public m9.case only (exact loc held locally)
- Respond shows public meta + Accept (M9 two-stage sealed path stub)
- Peer enrollment stub: files/me.pub export + files/peers/<nodeId>.pub import
- IdentityLimiter stranger lane for unenrolled senders
- Service StateFlow + Binder bridge for UI

## Single-phone smoke
- ChainFgs: PhoneNode ready id=n-2ae98e252a712b96 hardwareWrap=true enrolledPeers=0 hs=on
- NearbyRadio: advertising + discovering (0 peers expected)
- force-stop → relaunch: nodeId stable n-2ae98e252a712b96 (keystore persistence)
- Screenshots: help.png, respond.png, mesh.png

## Suite status (honest)
- Suite 4 extras: force-stop nodeId restore PASS on this device
- Suite 5 P1–P2 (two-phone claim): OPEN — needs 2nd physical phone
  To prep two enrolled phones: copy each me.pub → other phone files/peers/<nodeId>.pub

## INTERNET
- No INTERNET permission added (M4 still holds)

## JVM tests
- :identity HandshakeCoordinatorTest (mutual / spoof / replay / reflection / enrollPublic)
- :node PhoneNodeTest.originateHelpPublicWireHidesExactLocation
- BUILD SUCCESSFUL
