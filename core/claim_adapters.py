"""WireClaim <-> the claim shapes of M3, M7, M8 and sim. Pure conversions, no policy.

Imports of upper layers are lazy so `core` stays importable on its own.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional, Tuple

from .claim import WireClaim

CRITICAL = frozenset({"not_breathing", "severe_bleeding", "unconscious"})   # same set as m9.disclosure


def _unit(x, default: float = 0.5) -> float:
    try:
        x = float(x)
    except (TypeError, ValueError):
        return default
    return max(0.0, min(1.0, x)) if math.isfinite(x) else default


def need_of(parsed: dict) -> Optional[str]:
    """Skill the claim needs, mirroring m9.disclosure.Case.public(): critical features or a medic
    request need a medic; a boat request needs a boat; otherwise anyone."""
    tags, feats = set(parsed.get("capability_tags") or ()), set(parsed.get("features") or ())
    if feats & CRITICAL or "medic" in tags:
        return "medic"
    return "boat" if "boat" in tags else None


# ---- M3: Ledger / Verifier ---------------------------------------------------------------------
@dataclass(frozen=True)
class M3Claim:
    """What m3_verify.signals reads: id, node, src, pos, t (+ urgency / need / battery for hooks)."""
    id: str
    node: str
    src: str
    pos: Tuple[float, float]
    t: float
    urgency: float = 0.5
    need: Optional[str] = None
    battery: float = 0.5


def to_m3(wc: WireClaim, battery: float = 0.5) -> M3Claim:
    return M3Claim(wc.id, wc.node, wc.src, wc.pos, wc.t, _unit(wc.parsed.get("urgency")),
                   need_of(wc.parsed), _unit(battery))


# ---- M7: matching Claim ------------------------------------------------------------------------
def to_m7(wc: WireClaim, p_valid: float, trust: float = 0.5, battery_tts_s: Optional[float] = None):
    """m7_matching.utility.Claim with urgency = START triage x M6 time-to-silence (features.claim_scores)."""
    from m7_matching.features import claim_scores
    from m7_matching.utility import Claim
    p = wc.parsed
    _, urgency, gain = claim_scores(p.get("features") or (), _unit(p.get("urgency"), 0.3),
                                    p.get("kind", "need"), battery_tts_s)
    return Claim(wc.id, wc.pos, _unit(p_valid), urgency, gain, need_of(p), _unit(trust))


# ---- M8: event ---------------------------------------------------------------------------------
def cell_of(pos, cell_m: float = 500.0) -> str:
    """Same grid as m9_privacy.disclosure.cell_of (kept here so core needs no upper layer)."""
    return f"{math.floor(pos[0] / cell_m)}:{math.floor(pos[1] / cell_m)}"


def to_m8_args(wc: WireClaim, cell_m: float = 500.0) -> Tuple[str, str, float]:
    """Arguments for Replica.open_claim(claim, cell, urgency): never the exact position."""
    return wc.id, cell_of(wc.pos, cell_m), _unit(wc.parsed.get("urgency"), 0.3)


def open_in(replica, wc: WireClaim, cell_m: float = 500.0):
    return replica.open_claim(*to_m8_args(wc, cell_m))


# ---- sim.world.Claim ---------------------------------------------------------------------------
def from_sim(c, signer, radius: float = 15.0) -> WireClaim:
    """Sign a simulator claim as the physical phone that emitted it (ids become strings).
    Only observable fields cross: ground truth (kind, victim) is dropped."""
    parsed = {"kind": "need", "capability_tags": ["medic"] if c.need == "medic" else [],
              "features": [], "quantity": None, "urgency": round(_unit(c.urgency), 4),
              "language": "en", "confidence": 1.0, "source": "sim"}
    return WireClaim.make(str(c.node), c.pos, radius, c.t, parsed, signer, src=str(c.src))
