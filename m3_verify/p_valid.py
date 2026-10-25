"""p_valid(claim): combine M3 signals in log-odds space and keep the decomposition for M10."""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple

from . import signals as S

_CLAMP = 12.0


def _logit(p: float) -> float:
    return math.log(p / (1 - p))


def _sigmoid(x: float) -> float:
    return 1 / (1 + math.exp(-max(-_CLAMP, min(_CLAMP, x))))


@dataclass(frozen=True)
class Verdict:
    p: float
    parts: Dict[str, float]      # signal name -> LLR; M10 builds its justification from this

    def top_reasons(self, n: int = 3) -> List[Tuple[str, float]]:
        return sorted(((k, v) for k, v in self.parts.items() if v), key=lambda kv: -abs(kv[1]))[:n]


class Verifier:
    def __init__(self, prior: float = 0.7, stale_tau: float = 1800.0,
                 credential: Optional[Callable[[object], Optional[bool]]] = None):
        if not 0 < prior < 1:
            raise ValueError("prior must be in (0, 1)")
        self.prior, self.stale_tau, self.credential = prior, stale_tau, credential
        self.ledger = S.Ledger()
        self._challenge: Dict[int, float] = {}

    def observe(self, c) -> bool:
        return self.ledger.add(c)

    def record_challenge(self, claim_id: int, llr: float):
        """Feed in challenge.check_response(...). Replaces any earlier result for that claim."""
        self._challenge[claim_id] = llr

    def score(self, c, now: Optional[float] = None) -> Verdict:
        self.ledger.add(c)
        now = max(self.ledger.latest, c.t) if now is None else now
        L = self.ledger
        parts = {
            "identity": S.identity_binding(c),
            "node_spread": S.node_spread(L, c),
            "id_fanout": S.identity_fanout(L, c),
            "dup_spot": S.duplicate_spot(L, c),
            "corroboration": S.corroboration(L, c),
            "staleness": S.staleness(c, now, self.stale_tau),
            "credential": S.credential(c, self.credential),
            "challenge": self._challenge.get(c.id, 0.0),
        }
        return Verdict(_sigmoid(_logit(self.prior) + sum(parts.values())), parts)

    def p_valid(self, c, now: Optional[float] = None) -> float:
        return self.score(c, now).p
