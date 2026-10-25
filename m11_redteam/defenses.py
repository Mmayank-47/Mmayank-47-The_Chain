"""Stand-alone defenses from the M11 table: rate budgets, content-hash dedupe, offline-verifiable
credentials with revocation, and the responder's local sensor veto / on-site abort.

Credentials here are an HMAC stand-in for M1's signed bundles (root key pinned before the disaster).
"""
from __future__ import annotations

import hashlib
import hmac
from typing import Dict, Optional, Set, Tuple

from core.ratelimit import TokenBucket  # noqa: F401  (moved to core; re-exported for old imports)


class RateLimiter:
    """One bucket per key. Key by physical device (hardware-bound), not by claimed identity,
    so rotating fake identities from one phone share a single budget."""

    def __init__(self, rate: float = 2.0, burst: float = 10.0, max_keys: int = 4096):
        self.rate, self.burst, self.max_keys = rate, burst, max_keys
        self.b: Dict[object, TokenBucket] = {}

    def allow(self, key, now: float, cost: float = 1.0) -> bool:
        if key not in self.b:
            if len(self.b) >= self.max_keys:
                return False                       # refuse new keys rather than grow without bound
            self.b[key] = TokenBucket(self.rate, self.burst)
            self.b[key].t = now
        return self.b[key].allow(now, cost)


class Dedupe:
    """Drop repeats of identical content for `ttl` seconds."""

    def __init__(self, ttl: float = 600.0):
        self.ttl, self.seen = ttl, {}

    def first(self, payload: bytes, now: float) -> bool:
        for h in [h for h, t in self.seen.items() if now - t > self.ttl]:
            del self.seen[h]
        h = hashlib.sha256(payload).hexdigest()
        if h in self.seen:
            return False
        self.seen[h] = now
        return True


def sign_credential(root_key: bytes, node, role: str) -> str:
    return hmac.new(root_key, f"{node}|{role}".encode(), "sha256").hexdigest()


def verify_credential(root_key: bytes, node, role: str, sig: Optional[str],
                      revoked: Optional[Set[Tuple[object, str]]] = None) -> bool:
    if not sig or (revoked and (node, role) in revoked):
        return False
    return hmac.compare_digest(sign_credential(root_key, node, role), sig)


class SensorVeto:
    """Responder's own sensors outrank the mesh. Water at the responder's feet aborts the run no
    matter what the hazard grid or a 'route is safe' claim says. No sensor reading = no veto."""

    def __init__(self, max_water_m: float = 0.15):
        self.max_water_m = max_water_m

    def veto(self, local_water_m: Optional[float]) -> bool:
        return local_water_m is not None and local_water_m >= self.max_water_m

    def abort(self, local_water_m: Optional[float], risk_ahead: float, risk_limit: float = 0.8) -> bool:
        return self.veto(local_water_m) or risk_ahead >= risk_limit
