"""Challenge-response probes for unverified claims.

Verifier sends a fresh nonce-bound challenge ("send current barometer reading + BLE neighbour list").
A decoy far from the claimed spot has to guess the local pressure and invent neighbours that other
phones can contradict. Returns an LLR that M3 adds to the claim's score.
Silence is only a weak negative: real victims may be unconscious or at 5% battery.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import FrozenSet, Optional, Set

PASS, FAIL, LATE, MISMATCH = 2.5, -3.0, -1.0, -4.0


@dataclass(frozen=True)
class Challenge:
    cid: str
    claim_id: int
    claimant: int
    issued: float
    deadline: float


@dataclass(frozen=True)
class Response:
    cid: str
    claimant: int
    baro_hpa: float
    neighbors: FrozenSet[int]
    sent: float


def make_challenge(claim_id: int, claimant: int, now: float, nonce: str, ttl: float = 120.0) -> Challenge:
    """nonce must be fresh and unpredictable (caller supplies it, so tests stay deterministic)."""
    cid = hashlib.sha256(f"{claim_id}|{claimant}|{nonce}".encode()).hexdigest()[:16]
    return Challenge(cid, claim_id, claimant, now, now + ttl)


def check_response(ch: Challenge, resp: Optional[Response], now: float, local_baro_hpa: float,
                   known_nodes: Set[int], *, baro_tol: float = 3.0,
                   min_known_frac: float = 0.5) -> float:
    if resp is None:
        return LATE if now > ch.deadline else 0.0     # 0.0 = still pending
    if resp.cid != ch.cid or resp.claimant != ch.claimant:
        return MISMATCH                                # replayed or relayed answer
    if resp.sent > ch.deadline:
        return LATE
    nb = set(resp.neighbors) - {ch.claimant}
    baro_ok = abs(resp.baro_hpa - local_baro_hpa) <= baro_tol
    nb_ok = bool(nb) and len(nb & known_nodes) / len(nb) >= min_known_frac
    return PASS if (baro_ok and nb_ok) else FAIL
