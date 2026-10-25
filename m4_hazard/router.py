"""A* over an offline road graph with risk-averse cost.

Edge cost = length * (1 + a*risk + b*risk^3). The cubic term is convex, so a few very risky edges
cost far more than many mildly risky ones (penalises tail risk, not just the mean). Edges at or
above `block_above` are impassable. Risk is evaluated at the time we would reach each point, and since
hazards only grow over time, later arrival never looks safer. p_safe = prod(1 - edge risk).
"""
from __future__ import annotations

import heapq
import math
from collections import defaultdict
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple

Pos = Tuple[float, float]
RiskFn = Callable[[Pos, float], float]


class RoadGraph:
    def __init__(self):
        self.pos: Dict[str, Pos] = {}
        self.adj: Dict[str, Dict[str, float]] = defaultdict(dict)

    def add_node(self, n: str, pos: Pos):
        self.pos[n] = pos

    def add_edge(self, a: str, b: str):
        d = math.dist(self.pos[a], self.pos[b])
        self.adj[a][b] = d
        self.adj[b][a] = d


@dataclass(frozen=True)
class Route:
    path: List[str]
    length: float
    cost: float
    p_safe: float
    worst_risk: float


def edge_risk(g: RoadGraph, a: str, b: str, risk: RiskFn, t: float, speed: float = 1.5,
              step: float = 25.0) -> float:
    """Worst risk along the edge, each sample judged at the moment we would be standing there."""
    (x0, y0), (x1, y1) = g.pos[a], g.pos[b]
    d = g.adj[a][b]
    n = max(1, int(d / step))
    return max(risk((x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n), t + d * i / n / speed)
               for i in range(n + 1))


def find_route(g: RoadGraph, src: str, dst: str, risk: RiskFn, t0: float = 0.0, speed: float = 1.5,
               a: float = 2.0, b: float = 20.0, block_above: float = 0.8) -> Optional[Route]:
    best: Dict[str, float] = {src: 0.0}
    info: Dict[str, Tuple[float, float, float]] = {src: (0.0, 1.0, 0.0)}   # length, p_safe, worst
    prev: Dict[str, str] = {}
    heap = [(math.dist(g.pos[src], g.pos[dst]), 0, src)]
    tie, done = 0, set()
    while heap:
        _, _, n = heapq.heappop(heap)
        if n in done:
            continue
        done.add(n)
        if n == dst:
            path = [n]
            while path[-1] in prev:
                path.append(prev[path[-1]])
            ln, ps, worst = info[n]
            return Route(path[::-1], ln, best[n], ps, worst)
        ln, ps, worst = info[n]
        for m, d in g.adj[n].items():
            if m in done:
                continue
            e = edge_risk(g, n, m, risk, t0 + ln / speed, speed)
            if e >= block_above:
                continue
            cost = best[n] + d * (1 + a * e + b * e ** 3)
            if cost < best.get(m, math.inf):
                best[m], prev[m] = cost, n
                info[m] = (ln + d, ps * (1 - e), max(worst, e))
                tie += 1
                heapq.heappush(heap, (cost + math.dist(g.pos[m], g.pos[dst]), tie, m))
    return None
