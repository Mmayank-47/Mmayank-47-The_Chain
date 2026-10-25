"""Location as a distribution: particle filter fusing GPS, IMU dead-reckoning, BLE ranging, barometer.

estimate() returns (mean position, confidence radius), where the radius holds `q` of the weight.
Downstream code (M7 matching, M10 text) should use the radius, never the bare point.
"""
from __future__ import annotations

import math
import random
from typing import Callable, List, Optional, Tuple

Pos = Tuple[float, float]


class ParticleFilter:
    def __init__(self, pos: Pos, sigma: float, n: int = 300, rng: Optional[random.Random] = None):
        self.rng = rng or random.Random(0)
        self.n = n
        self.p: List[List[float]] = [[pos[0] + self.rng.gauss(0, sigma), pos[1] + self.rng.gauss(0, sigma)]
                                     for _ in range(n)]
        self.w: List[float] = [1.0 / n] * n

    def predict(self, dx: float, dy: float, noise: float):
        """IMU step: move every particle by the measured displacement plus drift noise."""
        for q in self.p:
            q[0] += dx + self.rng.gauss(0, noise)
            q[1] += dy + self.rng.gauss(0, noise)

    def _reweight(self, lik: Callable[[List[float]], float]):
        w = [wi * max(lik(q), 1e-12) for wi, q in zip(self.w, self.p)]
        s = sum(w)
        self.w = [x / s for x in w]
        if 1.0 / sum(x * x for x in self.w) < self.n / 2:      # effective sample size
            self._resample()

    def _resample(self):
        """Systematic resampling plus 1 m roughening so particles do not collapse."""
        u, c, i, new = self.rng.random() / self.n, self.w[0], 0, []
        for k in range(self.n):
            t = u + k / self.n
            while t > c and i < self.n - 1:
                i += 1
                c += self.w[i]
            new.append([self.p[i][0] + self.rng.gauss(0, 1.0), self.p[i][1] + self.rng.gauss(0, 1.0)])
        self.p, self.w = new, [1.0 / self.n] * self.n

    def update_gps(self, pos: Pos, sigma: float):
        self._reweight(lambda q: math.exp(-((q[0] - pos[0]) ** 2 + (q[1] - pos[1]) ** 2) / (2 * sigma ** 2)))

    def update_range(self, anchor: Pos, dist: float, sigma: float):
        """BLE/UWB range to a neighbour whose own position is known."""
        self._reweight(lambda q: math.exp(-(math.dist(q, anchor) - dist) ** 2 / (2 * sigma ** 2)))

    def update_altitude(self, dem: Callable[[Pos], float], alt: float, sigma: float):
        """Barometric altitude against the preloaded elevation model."""
        self._reweight(lambda q: math.exp(-(dem((q[0], q[1])) - alt) ** 2 / (2 * sigma ** 2)))

    def estimate(self, q: float = 0.9) -> Tuple[Pos, float]:
        mx = sum(w * p[0] for w, p in zip(self.w, self.p))
        my = sum(w * p[1] for w, p in zip(self.w, self.p))
        ds = sorted((math.dist(p, (mx, my)), w) for p, w in zip(self.p, self.w))
        acc = 0.0
        for d, w in ds:
            acc += w
            if acc >= q:
                return (mx, my), d
        return (mx, my), ds[-1][0]
