"""trust(node): the M5 output that feeds the dispatch gate.

Blend of graph trust and behavioural reputation. Strangers lean on the graph; as evidence builds
the blend moves to observed behaviour. Collusion discount multiplies the result. `llr()` gives the
same number as a log-likelihood ratio so M3 can add it as a signal.
"""
from __future__ import annotations

import math
from typing import Dict

from .beta_rep import BetaRep
from .collusion import discounts
from .vouch_graph import VouchGraph


class TrustEngine:
    def __init__(self, graph: VouchGraph = None, rep: BetaRep = None,
                 evidence_k: float = 4.0, cluster_factor: float = 0.2):
        self.graph = graph or VouchGraph()
        self.rep = rep or BetaRep()
        self.k, self.cluster_factor = evidence_k, cluster_factor
        self._disc = None
        self._disc_edges = -1

    def _discounts(self) -> Dict[str, float]:
        n_edges = sum(len(v) for v in self.graph.out.values()) + len(self.graph.seeds)
        if self._disc is None or n_edges != self._disc_edges:
            self._disc = discounts(self.graph, self.cluster_factor)
            self._disc_edges = n_edges
        return self._disc

    def trust(self, node: str, now: float) -> float:
        g = self.graph.score(node)
        n = self.rep.evidence(node, now)
        w = n / (n + self.k)
        t = (1 - w) * g + w * self.rep.mean(node, now)
        return max(0.0, min(1.0, t * self._discounts().get(node, 1.0)))

    def llr(self, node: str, now: float, limit: float = 3.0) -> float:
        t = min(max(self.trust(node, now), 1e-6), 1 - 1e-6)
        return max(-limit, min(limit, math.log(t / (1 - t))))
