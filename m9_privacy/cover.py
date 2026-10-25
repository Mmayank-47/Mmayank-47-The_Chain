"""Dummy-traffic budget: random PACKET-byte blobs that look like sealed envelopes and fail every tag check.

Real traffic already counts as cover, so it is subtracted from the budget. This blunts local passive
observers only; a global passive observer can still correlate timing, and bandwidth is finite.
"""
from __future__ import annotations

import random
from typing import List, Optional

from .sealed_envelope import PACKET


class CoverTraffic:
    def __init__(self, rate_Bps: float = 256.0, burst: Optional[float] = None, max_per_step: int = 4,
                 rng: Optional[random.Random] = None):
        self.rate, self.burst = rate_Bps, burst if burst is not None else 4.0 * PACKET
        self.max_per_step, self.rng = max_per_step, rng or random.Random()
        self.tokens, self.t = 0.0, None

    def step(self, now: float, real_sent_bytes: int = 0) -> List[bytes]:
        if self.t is not None:
            self.tokens = min(self.burst, self.tokens + self.rate * max(0.0, now - self.t))
        self.t = now
        self.tokens = max(0.0, self.tokens - real_sent_bytes)
        n = min(self.max_per_step, int(self.tokens // PACKET))
        self.tokens -= n * PACKET
        return [self.rng.randbytes(PACKET) for _ in range(n)]
