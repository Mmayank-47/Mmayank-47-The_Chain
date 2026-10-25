"""Bystander-effect fix: one deterministic primary, then automatic escalation.

Everyone computes the same ranking, so exactly one phone is prompted first. No ack within the
timeout, or a decline, moves to the next responder. "Can't, but I know X" puts X next.
"""
from __future__ import annotations

from typing import List, Optional

PROMPTED, ACCEPTED, EXHAUSTED = "prompted", "accepted", "exhausted"


class Escalation:
    def __init__(self, claim_id: str, ranked: List[str], now: float, ack_timeout: float = 120.0):
        self.claim_id, self.ranked, self.timeout = claim_id, list(ranked), ack_timeout
        self.i, self.t_prompt = 0, now
        self.state = PROMPTED if self.ranked else EXHAUSTED

    @property
    def current(self) -> Optional[str]:
        return self.ranked[self.i] if self.state in (PROMPTED, ACCEPTED) else None

    def _advance(self, now: float) -> Optional[str]:
        self.i += 1
        if self.i >= len(self.ranked):
            self.state = EXHAUSTED          # caller re-opens the claim (M8 lease rules)
            return None
        self.t_prompt = now
        return self.ranked[self.i]

    def ack(self, responder: str, now: float) -> bool:
        if self.state == PROMPTED and responder == self.current:
            self.state = ACCEPTED
            return True
        return False

    def decline(self, responder: str, now: float) -> Optional[str]:
        if self.state == PROMPTED and responder == self.current:
            return self._advance(now)
        return self.current if self.state == PROMPTED else None

    def refer(self, responder: str, to: str, now: float) -> Optional[str]:
        if self.state == PROMPTED and responder == self.current:
            self.ranked = [r for k, r in enumerate(self.ranked) if k <= self.i or r != to]
            self.ranked.insert(self.i + 1, to)
            return self._advance(now)
        return self.current if self.state == PROMPTED else None

    def tick(self, now: float) -> Optional[str]:
        """Returns the responder newly prompted because of a timeout, else None."""
        if self.state == PROMPTED and now - self.t_prompt >= self.timeout:
            return self._advance(now)
        return None
