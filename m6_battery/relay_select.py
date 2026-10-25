"""Relay choice and the responder energy check (chain stage 4: p_reach)."""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import List

from .urgency import BEACON_AT


@dataclass(frozen=True)
class Peer:
    id: str
    battery: float
    neighbors: int


def relay_score(p: Peer, target_degree: int = 6) -> float:
    return p.battery * min(1.0, p.neighbors / target_degree)


def pick_relays(peers: List[Peer], k: int) -> List[Peer]:
    """Charged, well-connected peers first. Beacon-mode phones never relay."""
    ok = [p for p in peers if p.battery > BEACON_AT]
    return sorted(ok, key=lambda p: (-relay_score(p), p.id))[:k]


def energy_needed(route_m: float, speed: float = 1.5, active_rate: float = 1.2e-4,
                  margin: float = 1.5, reserve: float = 0.15) -> float:
    """Battery fraction for the walk (with safety margin) plus a reserve for on-site use."""
    return route_m / speed * active_rate * margin + reserve


def p_reach(level: float, route_m: float, sigma: float = 0.05, **kw) -> float:
    """P(responder still has charge on arrival): Normal CDF of the energy margin (drain-rate uncertainty sigma)."""
    z = (level - energy_needed(route_m, **kw)) / sigma
    return 0.5 * (1 + math.erf(z / math.sqrt(2)))


def can_dispatch(level: float, route_m: float, threshold: float = 0.9, **kw) -> bool:
    return p_reach(level, route_m, **kw) >= threshold
