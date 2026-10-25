"""Hazard map as a CRDT grid with asymmetric acceptance.

State = last report per (cell, reporter), merged last-writer-wins, so merging in any order converges.
- 'hazard' reports are accepted on weak evidence (one light report already raises risk).
- 'safe' reports lift risk only when >= min_safe_reporters distinct reporters with enough combined
  weight agree, and only if no newer hazard report exists. Physics (flood model) is never overridden.
Report weight (0..1) is supplied by the caller as trust (M5) x co-presence (M1).
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Tuple

Cell = Tuple[int, int]


@dataclass(frozen=True)
class Report:
    reporter: str
    cell: Cell
    kind: str          # "hazard" | "safe"
    t: float
    weight: float

    def order(self):
        return (self.t, self.kind == "hazard", self.weight)      # total order -> deterministic merge


class HazardGrid:
    def __init__(self, cell_m: float = 50.0, hazard_half_life: float = 1800.0,
                 safe_half_life: float = 900.0, hazard_gain: float = 0.8, safe_gain: float = 0.5,
                 min_safe_reporters: int = 2, min_safe_weight: float = 1.0, prior: float = 0.1,
                 flood=None, dem: Optional[Callable[[Tuple[float, float]], float]] = None):
        self.cell_m, self.prior = cell_m, prior
        self.hl, self.sl = hazard_half_life, safe_half_life
        self.hg, self.sg = hazard_gain, safe_gain
        self.min_rep, self.min_w = min_safe_reporters, min_safe_weight
        self.flood, self.dem = flood, dem
        self._r: Dict[Tuple[Cell, str], Report] = {}

    def cell_of(self, pos) -> Cell:
        return (math.floor(pos[0] / self.cell_m), math.floor(pos[1] / self.cell_m))

    def center(self, cell: Cell) -> Tuple[float, float]:
        return ((cell[0] + 0.5) * self.cell_m, (cell[1] + 0.5) * self.cell_m)

    def report(self, reporter: str, pos, kind: str, t: float, weight: float) -> bool:
        if kind not in ("hazard", "safe"):
            raise ValueError(kind)
        return self._put(Report(reporter, self.cell_of(pos), kind, t, max(0.0, min(1.0, weight))))

    def _put(self, r: Report) -> bool:
        k = (r.cell, r.reporter)
        cur = self._r.get(k)
        if cur is None or r.order() > cur.order():
            self._r[k] = r
            return True
        return False

    def merge(self, other: "HazardGrid"):
        for r in other._r.values():
            self._put(r)

    def _live(self, cell: Cell, kind: str, now: float):
        hl = self.hl if kind == "hazard" else self.sl
        for (c, _), r in self._r.items():
            if c == cell and r.kind == kind and r.t <= now:
                w = r.weight * 0.5 ** ((now - r.t) / hl)
                if w >= 0.01:
                    yield r, w

    def hazard_p(self, cell: Cell, now: float) -> float:
        q = 1.0
        for _, w in self._live(cell, "hazard", now):
            q *= 1 - self.hg * w
        return 1 - q

    def safe_strength(self, cell: Cell, now: float) -> float:
        newest_hazard = max((r.t for r, _ in self._live(cell, "hazard", now)), default=-math.inf)
        rs = [(r, w) for r, w in self._live(cell, "safe", now) if r.t > newest_hazard]
        if len({r.reporter for r, _ in rs}) < self.min_rep or sum(w for _, w in rs) < self.min_w:
            return 0.0
        q = 1.0
        for _, w in rs:
            q *= 1 - self.sg * w
        return 1 - q

    def risk(self, cell: Cell, now: float) -> float:
        s = self.safe_strength(cell, now)
        keep = (1 - self.prior * (1 - s)) * (1 - self.hazard_p(cell, now))
        if self.flood is not None and self.dem is not None:
            keep *= 1 - self.flood.p_flooded(self.dem(self.center(cell)), now)
        return 1 - keep

    def risk_fn(self) -> Callable[[Tuple[float, float], float], float]:
        return lambda pos, t: self.risk(self.cell_of(pos), t)
