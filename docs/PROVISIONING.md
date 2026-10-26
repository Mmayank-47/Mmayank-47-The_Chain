# Offline provisioning

There is no account server. A phone learns who to trust before, or during, the outage.

## This phone's key

On first launch the app generates an Ed25519 key in Tink. The private keyset is wrapped by an Android Keystore AES key (StrongBox when the device has it, TEE otherwise). If the Keystore rejects the wrap, the app falls back to an app-private file and logs `keystore=software` on the Mesh screen. The public keyset and a 16-hex key id (SHA-256 of `kid:` concatenated with the public keyset) are what peers store.

## Co-presence enrollment (QR)

The Me screen shows a QR containing the node id, key id, public keyset, and an attestation if an authority has signed this key. The other phone scans it while both are in the same place. That scan is the co-presence enrollment: the scanner stores the public key in its directory. Until that happens, the peer is a stranger and only the low-rate distress lane in `IngressGuard` applies.

A second identity cannot be enrolled on a key id that already belongs to someone else, and an enrolled node cannot silently hop to a new key.

## Authority roots and revocations

Medical council, NDRF, and fire credentials are signed offline, before the disaster, over `(issuer, node, role, expiry)`. The pinned root public keys ship as a file the operator loads, or as a QR. Revocations are the same shape, grow-only, and gossiped. A phone never needs the internet to check them.

## What you cannot skip

Without a QR exchange, two phones still discover each other over Nearby, but each treats the other as a stranger. Dispatch to a stranger stays in the low-trust lane. There is no global directory that fills this in later.
