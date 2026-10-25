"""Beta reputation per node. Each phone keeps its own instance: trust is subjective, no global truth.

Evidence is pseudo-counts on top of a cold-start prior. Evidence decays toward the prior with a
half-life, so old behaviour matters less. Failures weigh more than successes (asymmetric), so
reputation is slow to build and fast to lose.
"""
from __future__ import annotations

import math
from typing import Dict, Tuple

# event -> (alpha weight, beta weight)
EVENTS: Dict[str, Tuple[float, float]] = {
    "verified_arrival":   (1.0, 0.0),
    "confirmed_hazard":   (0.5, 0.0),
    "corroborated_claim": (0.5, 0.0),
    "failed_claim":       (0.0, 3.0),   # decoy / unverifiable emergency
    "failed_challenge":   (0.0, 3.0),
    "false_hazard":       (0.0, 2.0),
    "equivocation":       (0.0, 6.0),   # signed, conflicting M8 logs (a fork); recorded locally only
    "forward_success":    (0.5, 0.0),   # hop ack or watchdog observed forward
    "forward_failure":    (0.0, 3.0),   # watchdog observed dropped packet / timeout
}


class BetaRep:
    def __init__(self, prior_a: float = 1.0, prior_b: float = 1.0, half_life_s: float = 6 * 3600.0,
                 max_event_weight: float = 3.0):
        if prior_a <= 0 or prior_b <= 0 or half_life_s <= 0:
            raise ValueError("prior and half-life must be positive")
        self.pa, self.pb, self.half_life = prior_a, prior_b, half_life_s
        self.cap = max_event_weight
        self._ev: Dict[str, Tuple[float, float, float]] = {}   # node -> (extra_a, extra_b, t_last)

    def _decayed(self, node: str, now: float) -> Tuple[float, float]:
        a, b, t = self._ev.get(node, (0.0, 0.0, now))
        f = 0.5 ** (max(0.0, now - t) / self.half_life)
        return a * f, b * f

    def record(self, node: str, event: str, now: float, weight: float = 1.0):
        if event not in EVENTS:
            raise KeyError(f"unknown event {event!r}")
        w = max(0.0, min(weight, self.cap))
        da, db = EVENTS[event]
        a, b = self._decayed(node, now)
        self._ev[node] = (a + da * w, b + db * w, now)

    def evidence(self, node: str, now: float) -> float:
        """How much (decayed) evidence backs the score: 0 for a stranger."""
        a, b = self._decayed(node, now)
        return a + b

    def mean(self, node: str, now: float) -> float:
        a, b = self._decayed(node, now)
        return (self.pa + a) / (self.pa + a + self.pb + b)

    def std(self, node: str, now: float) -> float:
        a, b = self._decayed(node, now)
        A, B = self.pa + a, self.pb + b
        return math.sqrt(A * B / ((A + B) ** 2 * (A + B + 1)))
