"""M2 -> M7 feature adapter and the M6 x triage urgency combiner.

M2 emits text-derived flags; M7's START triage wants clinical features. Only flags that say
something definite are mapped (never guessed), the rest stay None:
  not_walking -> can_walk=False      not_breathing -> breathing=False
  unconscious -> obeys_commands=False    severe_bleeding -> perfusion_ok=False
Vulnerability flags (elderly, child, pregnant, medication_dependent) are not START inputs. They
already raise M2's own urgency, which is what an UNKNOWN category falls back to.

Safety note: START puts "not breathing" in EXPECTANT only after a responder has cleared the airway.
A remote text like "can't breathe" cannot tell apnoea from distress, so claim_scores promotes a
text-only EXPECTANT to IMMEDIATE (a human at the scene makes the real call; M10 says so).
"""
from __future__ import annotations

from typing import Iterable, Optional, Tuple

from m6_battery.urgency import urgency as battery_urgency

from .triage import SCORES, Category, Features, scores

_TRIAGE = {"not_walking": ("can_walk", False), "not_breathing": ("breathing", False),
           "unconscious": ("obeys_commands", False), "severe_bleeding": ("perfusion_ok", False)}


def to_triage(features: Iterable[str]) -> Features:
    kw = {}
    for f in features:
        m = _TRIAGE.get(f)
        if m:
            kw[m[0]] = m[1]
    return Features(**kw)


def combine_urgency(triage_u: float, tts_s: Optional[float], weight: float = 0.3) -> float:
    """Noisy-OR of case urgency and a capped battery term. Time-to-silence can lift a case that is
    about to go dark, but at most `weight` of the way, so a minor case never becomes critical."""
    u = max(0.0, min(1.0, triage_u))
    if tts_s is None or u <= 0.0:
        return u
    return min(1.0, 1.0 - (1.0 - u) * (1.0 - weight * battery_urgency(tts_s)))


def claim_scores(features: Iterable[str], parsed_urgency: float = 0.3, kind: str = "need",
                 tts_s: Optional[float] = None) -> Tuple[Category, float, float]:
    """-> (category, urgency, survival_gain) for M7's Claim."""
    if kind == "offer":
        return Category.UNKNOWN, 0.0, 0.0
    feats = list(features)
    cat, u, g = scores(to_triage(feats))
    if cat is Category.EXPECTANT:
        cat = Category.IMMEDIATE
        u, g = SCORES[cat]
    if cat is Category.UNKNOWN:
        u = max(0.0, min(1.0, parsed_urgency))              # M2's own estimate when START has too little
    return cat, round(combine_urgency(u, tts_s), 4), g
