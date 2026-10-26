# Port divergences

Differences between the Python reference and the Kotlin phone build. A line is here only when the Python behaviour is insecure by design (the spec says to replace it) or when a Python result cannot be reproduced for a reason recorded below. Algorithm outputs in `vectors/*.json` are otherwise binding.

| Topic | Python | Kotlin phone | Why |
|---|---|---|---|
| Signatures | HMAC-SHA256, public key equals the secret (`DevProvider`) | Ed25519 via Tink | Required. HMAC remains in `:crypto-fake` for vector checks and is not on the app classpath. |
| Sealed envelope | XOR with an HMAC keystream, fixed 512 bytes | X25519 + ChaCha20-Poly1305 (HPKE), padded to 512 bytes | Required. Plaintext schema `{s,d,g}` is unchanged. |
| Key id | SHA-256 of the HMAC secret | SHA-256 of `kid:` + Ed25519 public keyset | The secret is not a public identifier. One-key-one-node still keys on this id. |
| `to_m7` / `from_sim` | Lazy imports inside `core/claim_adapters.py` | Live in `:matching` and `:sim` | `:core` must not import upper modules. |
| On-air JSON frames | `m0_transport/wire.py` | Wire v2 CBOR for signed bodies (`docs/WIRE.md`) | Phones do not interoperate with the Python process. |
| M10 patterns | Python `re` flag `(?U)` and an unescaped `}` in `\{name}` | Same classes without `(?U)`; closing brace escaped | Android ICU rejects `(?U)` and a bare `}` (`PatternSyntaxException` while loading `JustifyKt`). Devanagari stays in the place pattern explicitly. |
