"""Glue: M3/M4/M5/M6 outputs -> per-responder utilities for the auction, plus gate decisions."""
from __future__ import annotations

from typing import Callable, Dict, Iterable, Optional, Tuple

from .opportunity_cost import opp_cost, prices
from .utility import Claim, Responder, gate, pair_utility

RouteFn = Callable[[Responder, Claim], Optional[Tuple[float, float]]]   # -> (length_m, p_safe) | None
ReachFn = Callable[[Responder, float], float]                           # (responder, route_len) -> p_reach


def build_utilities(claims: Iterable[Claim], responders: Iterable[Responder], route_fn: RouteFn,
                    reach_fn: ReachFn, assigned: frozenset = frozenset(), extra_demand=None,
                    **kw):
    """-> (utilities {responder: {claim: U > 0}}, decisions {(responder, claim): gate result})."""
    cs = [c for c in claims if c.id not in assigned]
    rs = list(responders)
    price = prices(rs, cs, assigned, extra_demand)
    util: Dict[str, Dict[str, float]] = {r.id: {} for r in rs}
    decisions: Dict[Tuple[str, str], str] = {}
    for r in rs:
        for c in cs:
            if c.need and c.need not in r.skills:
                continue
            route = route_fn(r, c)
            if route is None:
                decisions[(r.id, c.id)] = "no_route"
                continue
            length, p_safe = route
            p_reach = reach_fn(r, length)
            d = decisions[(r.id, c.id)] = gate(c.p_valid, p_safe, c.trust, p_reach)
            if d != "dispatch":
                continue
            u = pair_utility(c, r, length, p_safe, p_reach, opp_cost(r, c, price), **kw)
            if u > 0:
                util[r.id][c.id] = u
    return util, decisions


def ranking(utilities: Dict[str, Dict[str, float]], claim_id: str):
    """Deterministic responder order for one claim (same on every phone given the same inputs)."""
    return [r for _, r in sorted((-u[claim_id], r) for r, u in utilities.items() if claim_id in u)]
