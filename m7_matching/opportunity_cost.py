"""Shadow price on scarce skills: a medic's time is worth more when critical cases are likely.

price(skill) = value * min(1, expected_demand / idle_supply). Using a skill the claim actually
needs is free; using it on a claim that does not need it costs the price. This is how "the only
medic is not sent to a minor emergency" falls out of the maths instead of a hard rule.
"""
from __future__ import annotations

from typing import Dict, Iterable, Mapping, Optional

from .utility import Claim, Responder

CRITICAL = 0.7


def expected_demand(claims: Iterable[Claim], skill: str, assigned: frozenset = frozenset(),
                    critical: float = CRITICAL) -> float:
    """Unassigned, plausible, critical claims needing `skill`, weighted by p_valid."""
    return sum(c.p_valid for c in claims
               if c.need == skill and c.urgency >= critical and c.id not in assigned)


def prices(responders: Iterable[Responder], claims: Iterable[Claim], assigned: frozenset = frozenset(),
           extra_demand: Optional[Mapping[str, float]] = None, value: float = 0.6) -> Dict[str, float]:
    """extra_demand: expected arrivals not yet seen (unverified reports, base rate)."""
    claims, rs = list(claims), list(responders)
    out = {}
    for s in {s for r in rs for s in r.skills}:
        supply = sum(1 for r in rs if s in r.skills)
        d = expected_demand(claims, s, assigned) + (extra_demand or {}).get(s, 0.0)
        out[s] = value * min(1.0, d / supply)
    return out


def opp_cost(r: Responder, c: Claim, price: Mapping[str, float]) -> float:
    return sum(price.get(s, 0.0) for s in r.skills if s != c.need)
