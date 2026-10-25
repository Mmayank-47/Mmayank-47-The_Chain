"""Vouch graph trust: personalised PageRank seeded from credentialed roots (EigenTrust / SybilRank style).

Each voucher splits one unit of trust over everyone they vouch for, so vouching for many dilutes it.
Trust leaks back to the seeds every step, so a ring of fake identities only holds what flows in
through its few 'attack edges' and shares it across the whole ring.
"""
from __future__ import annotations

from collections import defaultdict
from typing import Dict, Iterable, Set


class VouchGraph:
    def __init__(self, seeds: Iterable[str] = (), alpha: float = 0.15, iters: int = 50):
        self.seeds: Set[str] = set(seeds)
        self.alpha, self.iters = alpha, iters
        self.out: Dict[str, Dict[str, float]] = defaultdict(dict)
        self._cache = None

    def add_seed(self, node: str):
        self.seeds.add(node)
        self._cache = None

    def vouch(self, u: str, v: str, w: float = 1.0):
        if u == v or w <= 0:
            return                                   # no self-vouching
        self.out[u][v] = w                           # re-vouching replaces, never stacks
        self._cache = None

    def nodes(self) -> Set[str]:
        n = set(self.seeds) | set(self.out)
        for vs in self.out.values():
            n |= set(vs)
        return n

    def rank(self) -> Dict[str, float]:
        """Stationary trust mass; sums to 1 (all zeros without seeds)."""
        nodes = self.nodes()
        if not self.seeds:
            return {n: 0.0 for n in nodes}
        tele = {s: 1.0 / len(self.seeds) for s in self.seeds}
        pr = {n: tele.get(n, 0.0) for n in nodes}
        for _ in range(self.iters):
            nxt = {n: self.alpha * tele.get(n, 0.0) for n in nodes}
            for u in sorted(nodes):
                mass = pr[u] * (1 - self.alpha)
                outs = self.out.get(u)
                if outs:
                    tot = sum(outs.values())
                    for v, w in outs.items():
                        nxt[v] += mass * w / tot
                else:                                # dangling: mass returns to the roots
                    for s in tele:
                        nxt[s] += mass * tele[s]
            pr = nxt
        return pr

    def scores(self) -> Dict[str, float]:
        """0..1. Roots are 1.0; others are measured against the root teleport floor."""
        if self._cache is None:
            pr = self.rank()
            ref = (self.alpha / len(self.seeds)) if self.seeds else 1.0
            self._cache = {n: min(1.0, p / ref) for n, p in pr.items()}
        return self._cache

    def score(self, node: str, default: float = 0.5) -> float:
        """Strangers (not in the graph) get `default`."""
        return self.scores().get(node, default)
