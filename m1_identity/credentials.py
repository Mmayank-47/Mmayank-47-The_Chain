"""Pre-disaster credential bundles, verified offline against pinned authority roots.

Authorities (medical council, NDRF, fire dept) sign (node, role, expiry) before the event. A phone
needs only the pinned roots plus the gossiped revocation list to check a credential, no network.
`claim_check` adapts this to the `credential=` hook of M3's Verifier.
"""
from __future__ import annotations

import hmac
from dataclasses import dataclass
from typing import Callable, Dict, FrozenSet, Optional

from .revocation import Revocation, RevocationList, Root, sign_with

ALIASES: Dict[str, FrozenSet[str]] = {
    "medic": frozenset({"medic", "doctor", "nurse", "paramedic"}),
}


@dataclass(frozen=True)
class Credential:
    node: str
    role: str
    issuer: str
    expires: float
    sig: str

    def body(self) -> list:
        return ["cred", self.issuer, self.node, self.role, self.expires]


class Authority:
    """Issuer side (offline, before the disaster)."""

    def __init__(self, name: str, key: bytes, roles):
        self.name, self._key, self.roles = name, key, frozenset(roles)

    def root(self) -> Root:
        return (self._key, self.roles)

    def issue(self, node: str, role: str, expires: float) -> Credential:
        if role not in self.roles:
            raise ValueError(f"{self.name} may not grant {role!r}")
        c = Credential(node, role, self.name, expires, "")
        return Credential(node, role, self.name, expires, sign_with(self._key, c.body()))

    def revoke(self, node: str, role: str, t: float) -> Revocation:
        if role not in self.roles:
            raise ValueError(f"{self.name} may not revoke {role!r}")
        r = Revocation(self.name, node, role, t, "")
        return Revocation(self.name, node, role, t, sign_with(self._key, r.body()))


class CredentialVerifier:
    def __init__(self, roots: Dict[str, Root], revoked: Optional[RevocationList] = None):
        self.roots = dict(roots)
        self.revoked = revoked if revoked is not None else RevocationList(roots)

    def verify(self, c: Optional[Credential], now: float) -> bool:
        if c is None:
            return False
        root = self.roots.get(c.issuer)
        if root is None or c.role not in root[1] or now > c.expires:
            return False
        if self.revoked.is_revoked(c.node, c.role):
            return False
        return hmac.compare_digest(sign_with(root[0], c.body()), c.sig)

    def claim_check(self, creds: Dict[str, Credential]) -> Callable[[object], Optional[bool]]:
        """For Verifier(credential=...): None = no credential or no skill claimed, True/False = verdict.
        Keyed by the physical device (claim.node), never by the identity the claim asserts."""
        def check(claim) -> Optional[bool]:
            need = getattr(claim, "need", None)
            c = creds.get(str(claim.node))
            if need is None or c is None:
                return None
            ok_roles = ALIASES.get(need, frozenset({need}))
            return self.verify(c, claim.t) and c.role in ok_roles
        return check
