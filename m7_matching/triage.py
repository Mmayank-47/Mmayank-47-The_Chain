"""START-style triage from features M2 extracts. Unknown features are None, never guessed.

Category -> (urgency, survival_gain). EXPECTANT is deprioritised, not forgotten: it keeps a small
positive urgency so it is still served when responders are free. A human makes the final call (M10).
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Optional


class Category(Enum):
    IMMEDIATE = "immediate"
    DELAYED = "delayed"
    MINOR = "minor"
    EXPECTANT = "expectant"
    UNKNOWN = "unknown"


SCORES = {
    Category.IMMEDIATE: (1.0, 0.8),
    Category.DELAYED: (0.5, 0.5),
    Category.MINOR: (0.15, 0.2),
    Category.EXPECTANT: (0.3, 0.05),
    Category.UNKNOWN: (0.5, 0.5),       # cautious middle: not minor, not assumed critical
}


@dataclass(frozen=True)
class Features:
    can_walk: Optional[bool] = None
    breathing: Optional[bool] = None
    resp_rate: Optional[float] = None          # breaths/min
    perfusion_ok: Optional[bool] = None        # radial pulse / cap refill < 2 s
    obeys_commands: Optional[bool] = None


def classify(f: Features) -> Category:
    if f.can_walk:
        return Category.MINOR
    if f.breathing is False:
        return Category.EXPECTANT
    if f.resp_rate is not None and f.resp_rate > 30:
        return Category.IMMEDIATE
    if f.perfusion_ok is False or f.obeys_commands is False:
        return Category.IMMEDIATE
    known = [f.can_walk, f.breathing, f.resp_rate, f.perfusion_ok, f.obeys_commands]
    if sum(x is not None for x in known) < 3:
        return Category.UNKNOWN
    return Category.DELAYED


def scores(f: Features):
    """-> (category, urgency, survival_gain)"""
    c = classify(f)
    return (c, *SCORES[c])
