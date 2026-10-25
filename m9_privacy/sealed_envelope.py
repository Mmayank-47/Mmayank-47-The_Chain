"""Sealed, fixed-size envelopes: relays see only an opaque PACKET-byte blob and a coarse routing tag.

Sender identity sits inside the ciphertext (sealed sender). Every blob is exactly PACKET bytes, so
length leaks nothing. Encrypt-then-MAC with an HMAC-SHA256 keystream.
STAND-IN: box keys are symmetric, so `public()` is the secret. Insecure on purpose; swap for an
X25519 sealed box (and AEAD) on a real phone. Same convention as M1's keys.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
from typing import Any, Optional, Tuple

from core.types import canonical

PACKET, NONCE_LEN, TAG_LEN = 512, 16, 32
BODY = PACKET - NONCE_LEN - TAG_LEN
MAX_PLAIN = BODY - 2


def _kdf(secret: bytes, label: bytes) -> bytes:
    return hmac.new(secret, label, hashlib.sha256).digest()


def _stream(key: bytes, nonce: bytes, n: int) -> bytes:
    out, i = b"", 0
    while len(out) < n:
        out += hmac.new(key, nonce + i.to_bytes(4, "big"), hashlib.sha256).digest()
        i += 1
    return out[:n]


def _xor(a: bytes, b: bytes) -> bytes:
    return bytes(x ^ y for x, y in zip(a, b))


class BoxKey:
    def __init__(self, secret: Optional[bytes] = None):
        self._s = secret if secret is not None else os.urandom(32)

    def public(self) -> bytes:
        """STAND-IN for the public key (it is the secret)."""
        return self._s

    def open(self, blob: bytes) -> Optional[Tuple[str, Any, Optional[str]]]:
        return open_box(self._s, blob)


def seal(box_pub: bytes, sender: str, payload: Any, *, nonce: Optional[bytes] = None,
         sig: Optional[str] = None) -> bytes:
    nonce = os.urandom(NONCE_LEN) if nonce is None else nonce
    if len(nonce) != NONCE_LEN:
        raise ValueError("bad nonce length")
    data = canonical({"s": sender, "d": payload, "g": sig})
    if len(data) > MAX_PLAIN:
        raise ValueError("payload too large for one packet")
    body = len(data).to_bytes(2, "big") + data
    body += b"\0" * (BODY - len(body))
    ct = _xor(body, _stream(_kdf(box_pub, b"enc"), nonce, BODY))
    tag = hmac.new(_kdf(box_pub, b"mac"), nonce + ct, hashlib.sha256).digest()
    return nonce + ct + tag


def open_box(secret: bytes, blob: bytes) -> Optional[Tuple[str, Any, Optional[str]]]:
    """-> (sender, payload, sig) or None for anything that is not a valid envelope for this key."""
    if not isinstance(blob, (bytes, bytearray)) or len(blob) != PACKET:
        return None
    blob = bytes(blob)
    nonce, ct, tag = blob[:NONCE_LEN], blob[NONCE_LEN:NONCE_LEN + BODY], blob[NONCE_LEN + BODY:]
    if not hmac.compare_digest(hmac.new(_kdf(secret, b"mac"), nonce + ct, hashlib.sha256).digest(), tag):
        return None
    body = _xor(ct, _stream(_kdf(secret, b"enc"), nonce, BODY))
    n = int.from_bytes(body[:2], "big")
    if n > MAX_PLAIN:
        return None
    try:
        j = json.loads(body[2:2 + n])
        return str(j["s"]), j["d"], j.get("g")
    except (ValueError, KeyError, TypeError):
        return None
