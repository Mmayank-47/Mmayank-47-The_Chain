"""Collusion detection: tight clusters that only vouch for each other get discounted.

A cluster is a strongly connected group of >= min_size nodes with no credentialed root inside and
almost no vouches coming in from outside. Discounted, not banned: a genuine friend group nobody
else has vouched for looks the same, and behaviour (BetaRep) can still earn it trust.
"""
from __future__ import annotations

from typing import Dict, List, Set

from .vouch_graph import VouchGraph


def _sccs(nodes: List[str], out: Dict[str, Dict[str, float]]) -> List[Set[str]]:
    """Kosaraju, iterative, deterministic order."""
    seen, order = set(), []
    for s in nodes:
        if s in seen:
            continue
        seen.add(s)
        stack = [(s, iter(sorted(out.get(s, {}))))]
        while stack:
            n, it = stack[-1]
            for m in it:
                if m not in seen:
                    seen.add(m)
                    stack.append((m, iter(sorted(out.get(m, {})))))
                    break
            else:
                order.append(n)
                stack.pop()
    rev: Dict[str, List[str]] = {n: [] for n in nodes}
    for u, vs in out.items():
        for v in vs:
            rev[v].append(u)
    comps, done = [], set()
    for s in reversed(order):
        if s in done:
            continue
        comp, stack = set(), [s]
        done.add(s)
        while stack:
            n = stack.pop()
            comp.add(n)
            for m in rev[n]:
                if m not in done:
                    done.add(m)
                    stack.append(m)
        comps.append(comp)
    return comps


def find_clusters(g: VouchGraph, min_size: int = 3, max_external_in: float = 0.2) -> List[Set[str]]:
    nodes = sorted(g.nodes())
    flagged = []
    for comp in _sccs(nodes, g.out):
        if len(comp) < min_size or comp & g.seeds:
            continue
        inside = outside = 0
        for u, vs in g.out.items():
            for v in vs:
                if v in comp:
                    if u in comp:
                        inside += 1
                    else:
                        outside += 1
        if inside and outside / (inside + outside) <= max_external_in:
            flagged.append(comp)
    return flagged


def discounts(g: VouchGraph, factor: float = 0.2, **kw) -> Dict[str, float]:
    """node -> multiplier (1.0 = no discount)."""
    d: Dict[str, float] = {}
    for comp in find_clusters(g, **kw):
        for n in comp:
            d[n] = factor
    return d
