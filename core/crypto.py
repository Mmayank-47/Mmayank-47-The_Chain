"""One crypto surface for the whole stack (A4).

M1 (device keys, authority credentials, revocations), M8 (event signatures) and M9 (sealed
envelopes) all reach their primitives through this module, so moving from the dev stand-ins to
real X25519 / Ed25519 / ChaCha20-Poly1305 (F1) means replacing ONE provider, not hunting through
three packages.

Two layers:
  * Protocols the rest of the code is typed against: `Signer` (node-keyed sign/verify, what M8's
    EventStore needs and what M1's Registry implements) and `Box` (sealed-box key, what M9's
    BoxKey implements).
  * `Provider`: the primitive operations. The default `DevProvider` is HMAC-SHA256 and is
    INSECURE ON PURPOSE (public key == secret). `set_provider()` swaps it process-wide.
"""
from __future__ import annotations

import hashlib
import hmac
import os
from typing import Any, Optional, Protocol, Tuple, runtime_checkable

Opened = Optional[Tuple[str, Any, Optional[str]]]     # (sender, payload, sig) or None


@runtime_checkable
class Signer(Protocol):
    """Sign / verify on behalf of a named node. Implemented by m1_identity.keys.Registry."""

    def sign(self, node: str, data: bytes) -> str: ...
    def verify(self, node: str, data: bytes, sig: str) -> bool: ...


@runtime_checkable
class Box(Protocol):
    """A sealed-box recipient key. Implemented by m9_privacy.sealed_envelope.BoxKey."""

    def public(self) -> bytes: ...
    def open(self, blob: bytes) -> Opened: ...


@runtime_checkable
class Provider(Protocol):
    name: str

    def mac_bytes(self, key: bytes, data: bytes) -> bytes: ...
    def rand(self, n: int) -> bytes: ...


class DevProvider:
    """HMAC-SHA256 stand-in. Insecure on purpose; same convention as the old per-module stand-ins."""

    name = "dev-hmac-sha256"

    def mac_bytes(self, key: bytes, data: bytes) -> bytes:
        return hmac.new(key, data, hashlib.sha256).digest()

    def rand(self, n: int) -> bytes:
        return os.urandom(n)


_provider: Provider = DevProvider()


def get_provider() -> Provider:
    return _provider


def set_provider(p: Provider) -> Provider:
    """Install a provider; returns the previous one so callers (tests) can restore it."""
    global _provider
    if not isinstance(p, Provider):
        raise TypeError("provider must implement mac_bytes() and rand()")
    prev, _provider = _provider, p
    return prev


# ---- primitives used by M1 / M8 / M9 -------------------------------------------------------------

def mac_bytes(key: bytes, data: bytes) -> bytes:
    return _provider.mac_bytes(key, data)


def mac(key: bytes, data: bytes) -> str:
    return mac_bytes(key, data).hex()


def ct_equal(a: bytes, b: bytes) -> bool:
    return hmac.compare_digest(a, b)


def mac_ok(key: bytes, data: bytes, sig) -> bool:
    """Constant-time check of a hex MAC. False for anything that is not an ASCII str."""
    return isinstance(sig, str) and sig.isascii() and hmac.compare_digest(mac(key, data), sig)


def kdf(secret: bytes, label: bytes) -> bytes:
    return mac_bytes(secret, label)


def keystream(key: bytes, nonce: bytes, n: int) -> bytes:
    out, i = b"", 0
    while len(out) < n:
        out += mac_bytes(key, nonce + i.to_bytes(4, "big"))
        i += 1
    return out[:n]


def rand(n: int) -> bytes:
    return _provider.rand(n)
