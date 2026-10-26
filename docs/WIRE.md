# Wire v2

The Kotlin phones do not speak the Python JSON frames on the air. Python remains the reference for protocol decisions (what is accepted, what is dropped, how ties break). Wire v2 is the on-air encoding.

## Content ids

Message ids, claim ids, and event hashes stay SHA-256 over Python's canonical JSON:

`json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True)`

That keeps golden vectors exact. `org.thechain.core.CanonicalJson` is tested against `vectors/core.json`.

## On-air frames

Signed bodies are canonical CBOR (implemented in `:transport` as the frames are ported):

- major types: unsigned/negative integers, byte strings, text strings, arrays, maps
- map keys sorted by their encoded bytes
- no floating-point, no tags, no indefinite lengths
- coordinates are micro-degrees (`Int`, degrees × 1_000_000)
- times are milliseconds (`Long`)
- urgency, trust, and probabilities that must be signed are milli-units (`Int`, 0..1000)
- `encode(decode(x))` must be byte-identical
- malformed input is rejected, never partially trusted

The HMAC-XOR sealed box in the Python tree is not used on the phone. Stage-2 envelopes are X25519 HPKE with ChaCha20-Poly1305 (Tink template `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305`), padded to a fixed 512 bytes so length leaks nothing. The plaintext inside is still the canonical JSON object `{s, d, g}` from the Python spec, so the disclosed fields match.

## Link handshake

Nearby `endpointId` values are ephemeral. A frame is admitted only after a signed handshake:

1. Each side contributes a nonce.
2. The signature covers node id, endpoint id, both nonces, and a timestamp.
3. The timestamp must be within five minutes of local time.
4. A repeated transcript, a matching nonce on both sides, or our own node id is rejected.

`IngressGuard` then keys replay windows and rate limits on the verified node id, not on the radio endpoint id.

## What is intentionally different

See `docs/PORT_DIVERGENCES.md`. Signature bytes are Ed25519, not HMAC-SHA256. The HMAC implementation lives in `:crypto-fake` only so vectors can still check the reference algorithm. `:app` must not depend on it.
