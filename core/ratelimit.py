"""Token bucket: the one rate primitive shared across the stack.

It lives in core so layers below M11 (M1's IdentityLimiter) do not import the red-team package just
to get it. m11_redteam.defenses re-exports the name, so old imports keep working.
"""
from __future__ import annotations


class TokenBucket:
    def __init__(self, rate: float, burst: float):
        self.rate, self.burst = rate, burst
        self.tokens, self.t = burst, 0.0

    def allow(self, now: float, cost: float = 1.0) -> bool:
        self.tokens = min(self.burst, self.tokens + self.rate * max(0.0, now - self.t))
        self.t = max(self.t, now)
        if self.tokens >= cost:
            self.tokens -= cost
            return True
        return False
