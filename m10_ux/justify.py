"""Template-grounded justification: built only from the real score decomposition, so it cannot invent facts.

Persuade with facts, never urgency tricks: the output is linted for pressure words, always states
route/battery risk honestly (safety numbers are floored, not rounded up) and always ends with an
abort criterion. An optional LLM polish is accepted only if it adds no new numbers and drops none.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple

from .templates import LANGS, T, fmt_dist, label

CORROB_LLR = 1.5                      # m3_verify.signals.corroboration: LLR per independent node
_PLACE = re.compile(r"[^\w\s.\-ऀ-ॿ]", re.UNICODE)
MANIPULATIVE = re.compile(r"urgent|hurry|immediately|right now|asap|last chance|only you|must|!|"
                          r"जल्दी|तुरंत|लवकर|ताबडतोब", re.I)
_NUM = re.compile(r"\d+(?:\.\d+)?")


@dataclass(frozen=True)
class Facts:
    tags: Tuple[str, ...]
    dist_m: float
    p_safe: float
    p_reach: float
    battery: float
    route_via: str = ""
    flooded: Tuple[str, ...] = ()
    worst_risk: float = 0.0
    quantity: Optional[int] = None
    place: str = ""
    radius_m: Optional[float] = None
    verifiers: int = 0
    skill: Optional[str] = None            # set when the responder's skill is what the case needs
    nearest: bool = False
    gate: str = "dispatch"                 # M7 gate: dispatch | scout | probe | hold | no_route
    abort_water_cm: int = 15               # m11 SensorVeto.max_water_m
    abort_battery_pct: int = 15            # m6 reserve


@dataclass(frozen=True)
class Justification:
    kind: str                              # dispatch | scout
    lang: str
    text: str
    lines: Tuple[str, ...]
    risk: str                              # low | moderate | high
    abort: str
    grounding: Dict[str, object]


def verifiers_from_parts(parts: Dict[str, float]) -> int:
    """M3 Verdict.parts -> number of independent corroborating nodes behind the score."""
    return max(0, int(round(parts.get("corroboration", 0.0) / CORROB_LLR)))


def clean_place(s: str, cap: int = 40) -> str:
    """Claim text is untrusted data: strip everything but letters, digits, spaces, dots, hyphens."""
    return " ".join(_PLACE.sub(" ", str(s)).split())[:cap]


def lint(text: str) -> List[str]:
    return sorted({m.group(0).lower() for m in MANIPULATIVE.finditer(text)})


def risk_level(f: Facts) -> str:
    if f.p_safe < 0.7 or f.worst_risk >= 0.5 or f.p_reach < 0.9:
        return "high"
    if f.p_safe < 0.9 or f.worst_risk >= 0.2 or f.battery - f.abort_battery_pct / 100.0 < 0.2:
        return "moderate"
    return "low"


def _pct(x: float) -> int:
    return int(max(0.0, min(1.0, x)) * 100)             # floor: never overstate safety or charge


def justify(f: Facts, lang: str = "en") -> Optional[Justification]:
    """None when the gate says no prompt (probe / hold / no_route): those are not shown to a human."""
    if f.gate not in ("dispatch", "scout"):
        return None
    lang = lang if lang in LANGS else "en"
    t = T[lang]
    what = ", ".join(label(lang, x) for x in f.tags[:2]) or label(lang, "help")
    grounding: Dict[str, object] = {"dist_m": f.dist_m, "tags": f.tags}
    lines = [t["need"].format(what=what, dist=fmt_dist(lang, f.dist_m))]
    place = clean_place(f.place)
    if place:
        lines.append(t["near"].format(place=place))
        grounding["place"] = place
    if f.gate == "dispatch":
        if f.verifiers >= 1:
            lines.append(t["verified"].format(n=f.verifiers))
        else:
            lines.append(t["unverified"])
        grounding["verifiers"] = f.verifiers
        if f.skill and f.nearest:
            lines.append(t["skill"].format(skill=label(lang, f.skill)))
    else:
        lines.append(t["scout"])
    risky = f.p_safe < 0.9 or f.worst_risk >= 0.2
    lines.append(t["route_risk" if risky else "route_ok"].format(via=clean_place(f.route_via) or "?", p=_pct(f.p_safe)))
    grounding.update(route_via=f.route_via, p_safe=_pct(f.p_safe))
    names = ", ".join(clean_place(x) for x in f.flooded if clean_place(x))
    if names:
        lines.append(t["avoid"].format(names=names))
    if f.gate == "dispatch":
        lines.append(t["bat_ok" if f.p_reach >= 0.9 else "bat_low"].format(pct=_pct(f.battery)))
        grounding["battery"] = _pct(f.battery)
    if f.radius_m is not None:
        lines.append(t["radius"].format(r=int(round(f.radius_m / 10.0)) * 10 or 10))
    cond = t["cond"].format(cm=f.abort_water_cm, pct=f.abort_battery_pct)
    abort = t["abort"].format(cond=cond)
    lines.append(abort)
    text = " ".join(lines)
    bad = lint(text)
    if bad:
        raise ValueError(f"manipulative wording in template output: {bad}")
    return Justification(f.gate if f.gate == "scout" else "dispatch", lang, text, tuple(lines),
                         risk_level(f), abort, grounding)


def constrained_polish(j: Justification, generate: Optional[Callable[[str], str]], max_len: int = 400) -> Justification:
    """Accept the model's rewrite only if it keeps exactly the same numbers, stays short and calm."""
    if generate is None:
        return j
    prompt = ("Rewrite as one short, calm paragraph. Keep every number and place name exactly. "
              "Add no facts, no pressure words.\n" + j.text)
    try:
        out = " ".join(str(generate(prompt)).split())
    except Exception:
        return j
    if (not out or len(out) > max_len or lint(out) or sorted(_NUM.findall(out)) != sorted(_NUM.findall(j.text))
            or j.abort.rstrip(".।") not in out):
        return j
    return Justification(j.kind, j.lang, out, j.lines, j.risk, j.abort, j.grounding)
