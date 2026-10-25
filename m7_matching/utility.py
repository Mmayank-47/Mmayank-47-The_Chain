"""Pair utility and the dispatch gate.

U = p_valid * urgency * survival_gain * p_reach - route_risk - travel_cost - opportunity_cost
Ineligible pairs (responder lacks the needed skill) get -inf. Constants are uncalibrated
placeholders; the M12 harness is where they get tuned.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import FrozenSet, Optional, Tuple

Pos = Tuple[float, float]


@dataclass(frozen=True)
class Claim:
    id: str
    pos: Pos
    p_valid: float                 # M3
    urgency: float                 # M7 triage x M6 battery urgency
    survival_gain: float
    need: Optional[str] = None     # required skill, None = anyone
    trust: float = 0.5             # M5 trust in the claimant (0.5 = stranger)


@dataclass(frozen=True)
class Responder:
    id: str
    pos: Pos
    skills: FrozenSet[str] = field(default_factory=frozenset)
    battery: float = 1.0


def pair_utility(c: Claim, r: Responder, route_len: float, p_safe: float, p_reach: float,
                 opp_cost: float, *, loss_value: float = 1.0, speed: float = 1.5,
                 horizon: float = 3600.0, time_weight: float = 0.3) -> float:
    if c.need and c.need not in r.skills:
        return -math.inf
    benefit = c.p_valid * c.urgency * c.survival_gain * p_reach
    risk = (1 - p_safe) * loss_value
    travel = time_weight * min(1.0, route_len / speed / horizon)
    return benefit - risk - travel - opp_cost


def gate(p_valid: float, p_route_safe: float, trust: float, p_reach: float,
         base: float = 0.2, slope: float = 0.5, min_valid: float = 0.5) -> str:
    """Chain decision: P = p_valid * p_route_safe * trust * p_reach must clear a threshold that
    rises with danger to the responder. Below it: probe the claim, scout the route, or hold.
    A floor on p_valid stops a trusted claimant from carrying a shaky claim through the product."""
    if p_valid < min_valid:
        return "probe"
    p = p_valid * p_route_safe * trust * p_reach
    thr = min(0.95, base + slope * (1 - p_route_safe))
    if p >= thr:
        return "dispatch"
    if p_valid < 0.6 or trust < 0.4:
        return "probe"          # verification challenge (M3) before anyone moves
    if p_route_safe < 0.7:
        return "scout"          # send someone to look at the route first
    return "hold"
