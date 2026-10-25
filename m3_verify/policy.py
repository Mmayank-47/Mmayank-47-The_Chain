"""Sim adapter: NaivePolicy plus an M3 gate. Claims wait in a pending pool and are released to
dispatch only when p_valid clears `release`; corroboration arriving later can lift a held claim."""
from __future__ import annotations

from sim.baseline import NaivePolicy

from .p_valid import Verifier


class VerifiedPolicy(NaivePolicy):
    def __init__(self, w, release: float = 0.6, ttl: float = 1800.0, verifier: Verifier = None):
        super().__init__(w)
        self.v = verifier or Verifier()
        self.release, self.ttl = release, ttl
        self.pending, self.scores = {}, {}

    def p_valid(self, c):
        return self.scores.get(c.id, 0.0)

    def observe(self, c):
        self.v.observe(c)
        self.pending[c.id] = c

    def tick(self, w):
        for cid, c in list(self.pending.items()):
            p = self.v.p_valid(c, w.t)
            if p >= self.release:
                del self.pending[cid]
                self.scores[cid] = p
                super().observe(c)
            elif w.t - c.t > self.ttl:
                del self.pending[cid]
        return super().tick(w)
