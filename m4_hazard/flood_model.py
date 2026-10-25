"""Monotonic flood-rise model: water only rises, so the model is allowed to be conservative.

Confirmed water-level observations can raise the level, never lower it. The projected rate is the
steepest rise ever seen, so the projection errs toward 'more water sooner'.
"""
from __future__ import annotations

import math
from typing import Optional, Tuple


class FloodModel:
    def __init__(self, base_level: float = 0.0, rate: float = 0.0, softness: float = 0.5):
        self.level0, self.t0, self.rate, self.softness = base_level, 0.0, rate, softness
        self._last: Optional[Tuple[float, float]] = None     # (level, t) of last accepted raise

    def level(self, t: float) -> float:
        return self.level0 + self.rate * max(0.0, t - self.t0)

    def observe(self, level: float, t: float) -> bool:
        """Report confirmed water at `level` metres at time t. True if it raised the model."""
        if level <= self.level(t):
            return False                                    # water cannot be lower than we assume
        if self._last and t > self._last[1]:
            self.rate = max(self.rate, (level - self._last[0]) / (t - self._last[1]))
        self.level0, self.t0, self._last = level, t, (level, t)
        return True

    def p_flooded(self, elevation: float, t: float) -> float:
        margin = elevation - self.level(t)
        return 1.0 if margin <= 0 else math.exp(-margin / self.softness)
