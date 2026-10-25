"""Hardware-bound device keys (Android Keystore / StrongBox stand-in) and the enrollment registry.

A Keystore never exposes its secret to callers except through `public()`, which is the HMAC stand-in
for a public key (swap for Ed25519 on a real phone). Enrollment needs an attestation from a pinned
manufacturer root, and the registry refuses to bind one hardware key to two identities, so one
phone cannot become many nodes. Registry.sign/verify implement core.crypto.Signer, which is what
M8's EventStore and Replica now require (there is no separate dev signer any more).
All MAC primitives come from core.crypto, shared with M9.
"""
from __future__ import annotations

import hashlib
from typing import Dict, Iterable, Optional, Tuple

from core import crypto


class Keystore:
    """One per physical device. The secret stays inside."""

    def __init__(self, secret: Optional[bytes] = None):
        self._secret = secret if secret is not None else crypto.rand(32)
        self.key_id = hashlib.sha256(b"kid:" + self._secret).hexdigest()[:16]

    def sign(self, data: bytes) -> str:
        return crypto.mac(self._secret, data)

    def public(self) -> bytes:
        """STAND-IN for the public key. Insecure on purpose: it is the HMAC secret."""
        return self._secret


class AttestationRoot:
    """Pinned manufacturer root: vouches that a key_id lives in real secure hardware."""

    def __init__(self, key: bytes):
        self._key = key

    def attest(self, key_id: str) -> str:
        return crypto.mac(self._key, b"attest|" + key_id.encode())

    def check(self, key_id: str, att: Optional[str]) -> bool:
        return bool(att) and crypto.mac_ok(self._key, b"attest|" + key_id.encode(), att)


class Registry:
    """node -> bound hardware key. Verifies signatures; in the sim it also holds keystores to sign."""

    def __init__(self, root: AttestationRoot):
        self.root = root
        self._pub: Dict[str, Tuple[str, bytes]] = {}     # node -> (key_id, public stand-in)
        self._by_key: Dict[str, str] = {}                # key_id -> node
        self._ks: Dict[str, Keystore] = {}               # sim only: real phones sign locally

    def enroll(self, node: str, ks: Keystore, att: Optional[str]) -> bool:
        if not node or not self.root.check(ks.key_id, att):
            return False
        if self._by_key.get(ks.key_id, node) != node:
            return False                                 # this hardware already speaks for someone else
        cur = self._pub.get(node)
        if cur is not None and cur[0] != ks.key_id:
            return False                                 # identity cannot hop to a new key
        self._pub[node], self._by_key[ks.key_id], self._ks[node] = (ks.key_id, ks.public()), node, ks
        return True

    def enrolled(self, node: str) -> bool:
        return node in self._pub

    def key_id_of(self, node: str) -> Optional[str]:
        e = self._pub.get(node)
        return e[0] if e else None

    def sign(self, node: str, data: bytes) -> str:
        ks = self._ks.get(node)
        if ks is None:
            raise KeyError(f"no keystore for {node!r}")
        return ks.sign(data)

    def verify(self, node: str, data: bytes, sig: str) -> bool:
        e = self._pub.get(node)
        if e is None or not isinstance(sig, str):
            return False
        return crypto.mac_ok(e[1], data, sig)


def dev_fleet(names: Iterable[str], root_key: bytes = b"dev-attestation-root") -> Registry:
    """Sim/test helper: a Registry with one freshly attested keystore per name.

    This is how M8 replicas (and the sim) get their signer: one shared Registry, one hardware key
    per node, exactly as on a real deployment but with the dev crypto provider.
    """
    root = AttestationRoot(root_key)
    reg = Registry(root)
    for n in names:
        ks = Keystore()
        if not reg.enroll(n, ks, root.attest(ks.key_id)):
            raise ValueError(f"could not enroll {n!r}")
    return reg
