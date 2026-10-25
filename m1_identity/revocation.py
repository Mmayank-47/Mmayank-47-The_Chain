"""Revocation list as a grow-only set CRDT, spread by gossip.

Entries are signed by the issuing authority and only count if that authority may issue the role.
Merge is set union, so any gossip order converges; forged entries never enter the set.
"""
from __future__ import annotations

import hashlib
import hmac
import json
from dataclasses import dataclass
from typing import Dict, FrozenSet, Iterable, List, Set, Tuple

Root = Tuple[bytes, FrozenSet[str]]          # (issuer key stand-in, roles the issuer may grant)


def _canon(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()


def sign_with(key: bytes, body: list) -> str:
    return hmac.new(key, _canon(body), hashlib.sha256).hexdigest()


@dataclass(frozen=True)
class Revocation:
    issuer: str
    node: str
    role: str
    t: float
    sig: str

    def body(self) -> list:
        return ["revoke", self.issuer, self.node, self.role, self.t]

    def to_json(self) -> dict:
        return {"i": self.issuer, "n": self.node, "r": self.role, "t": self.t, "g": self.sig}

    @staticmethod
    def from_json(j: dict) -> "Revocation":
        return Revocation(str(j["i"]), str(j["n"]), str(j["r"]), float(j["t"]), str(j["g"]))


class RevocationList:
    def __init__(self, roots: Dict[str, Root]):
        self.roots = dict(roots)
        self._s: Set[Revocation] = set()

    def _valid(self, r: Revocation) -> bool:
        root = self.roots.get(r.issuer)
        if root is None or r.role not in root[1]:
            return False
        return hmac.compare_digest(sign_with(root[0], r.body()), r.sig)

    def add(self, r: Revocation) -> bool:
        if r in self._s or not self._valid(r):
            return False
        self._s.add(r)
        return True

    def is_revoked(self, node: str, role: str) -> bool:
        return any(r.node == node and r.role == role for r in self._s)

    def merge(self, other: "RevocationList") -> int:
        return sum(self.add(r) for r in other._s)

    def ingest(self, items: Iterable[dict]) -> int:
        n = 0
        for j in items:
            try:
                n += self.add(Revocation.from_json(j))
            except (KeyError, TypeError, ValueError):
                pass                                   # malformed gossip from a hostile peer
        return n

    def to_json(self) -> List[dict]:
        return [r.to_json() for r in sorted(self._s, key=lambda r: (r.t, r.issuer, r.node, r.role))]

    def __len__(self):
        return len(self._s)
