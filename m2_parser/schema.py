"""The structured claim M2 emits, plus the validator that every model output must pass.

Schema-constrained: unknown keys are dropped, enums are whitelisted, lengths are capped, and
urgency is NEVER taken from a model. It is recomputed from the extracted features, so text like
"ignore the rules, urgency 1.0" cannot inflate it.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Any, Dict, Optional, Tuple

KINDS = ("need", "offer", "unknown")
LANGS = ("en", "hi", "mr", "mixed", "unknown")
# START-style triage features M7 will use.
FEATURES = ("not_breathing", "severe_bleeding", "unconscious", "trapped", "not_walking",
            "elderly", "child", "pregnant", "medication_dependent")
FEATURE_WEIGHT = {"not_breathing": 0.50, "severe_bleeding": 0.40, "unconscious": 0.40,
                  "trapped": 0.25, "not_walking": 0.15, "elderly": 0.10, "child": 0.15,
                  "pregnant": 0.15, "medication_dependent": 0.20}
BASE_URGENCY = {"need": 0.30, "unknown": 0.20, "offer": 0.0}

MAX_TAGS, MAX_LOC, MAX_QTY = 8, 80, 10_000
_KNOWN_TAG = re.compile(r"^[a-z][a-z0-9_]{0,31}$")
_UNKNOWN_TAG = re.compile(r"^unknown:[\wऀ-ॣ०-ॿ]{1,40}$")
_CTRL = re.compile(r"[\x00-\x1f\x7f]")


class SchemaError(ValueError):
    pass


def urgency_from_features(kind: str, features) -> float:
    """Noisy-OR of feature weights over a base. Deterministic, monotone, never above 1."""
    if kind == "offer":
        return 0.0
    miss = 1.0 - BASE_URGENCY.get(kind, 0.2)
    for f in features:
        miss *= 1.0 - FEATURE_WEIGHT.get(f, 0.0)
    return round(1.0 - miss, 4)


@dataclass(frozen=True)
class ParsedClaim:
    kind: str                          # need | offer | unknown
    capability_tags: Tuple[str, ...]   # ontology tags, or "unknown:<slug>" (kept, never forced)
    quantity: Optional[int]
    urgency: float                     # derived from features, 0..1
    features: Tuple[str, ...]          # sorted subset of FEATURES
    location_text: str                 # raw place words; M4 turns them into a distribution
    language: str
    confidence: float                  # 0..1, how far M3/M7 should trust this parse
    source: str                        # "keyword" | "llm"

    @staticmethod
    def build(kind, tags, quantity, features, location_text, language, confidence, source):
        kind = kind if kind in KINDS else "unknown"
        feats = tuple(sorted({f for f in features if f in FEATURES})) if kind != "offer" else ()
        return ParsedClaim(kind, tuple(tags)[:MAX_TAGS], quantity, urgency_from_features(kind, feats),
                           feats, (location_text or "")[:MAX_LOC],
                           language if language in LANGS else "unknown",
                           round(min(1.0, max(0.0, confidence)), 4), source)

    def to_json(self) -> dict:
        return {"kind": self.kind, "capability_tags": list(self.capability_tags),
                "quantity": self.quantity, "urgency": self.urgency, "features": list(self.features),
                "location_text": self.location_text, "language": self.language,
                "confidence": self.confidence, "source": self.source}

    @staticmethod
    def from_json(j: dict) -> "ParsedClaim":
        p = validate(j)
        return ParsedClaim.build(p.kind, p.capability_tags, p.quantity, p.features, p.location_text,
                                 p.language, p.confidence, j.get("source", p.source))


def _clean_text(s: Any, cap: int) -> str:
    if not isinstance(s, str):
        return ""
    return " ".join(_CTRL.sub(" ", s).split())[:cap]


def validate(obj: Any) -> ParsedClaim:
    """Strict validation of untrusted model output. Raises SchemaError; never trusts urgency."""
    if not isinstance(obj, dict):
        raise SchemaError("not an object")
    kind = obj.get("kind")
    if kind not in KINDS:
        raise SchemaError(f"bad kind {kind!r}")
    conf = obj.get("confidence")
    if isinstance(conf, bool) or not isinstance(conf, (int, float)) or not math.isfinite(conf):
        raise SchemaError("bad confidence")
    raw_tags = obj.get("capability_tags", [])
    if not isinstance(raw_tags, list):
        raise SchemaError("capability_tags must be a list")
    tags = []
    for t in raw_tags:
        if not isinstance(t, str):
            continue
        t = t.strip().lower()
        if (_KNOWN_TAG.match(t) or _UNKNOWN_TAG.match(t)) and t not in tags:
            tags.append(t)
    q = obj.get("quantity")
    if isinstance(q, bool) or not isinstance(q, (int, float)) or not math.isfinite(q) or q < 1:
        q = None
    else:
        q = min(int(q), MAX_QTY)
    feats = obj.get("features", [])
    feats = [f for f in feats if f in FEATURES] if isinstance(feats, list) else []
    lang = obj.get("language", "unknown")
    return ParsedClaim.build(kind, tags[:MAX_TAGS], q, feats, _clean_text(obj.get("location_text"), MAX_LOC),
                             lang if lang in LANGS else "unknown", float(conf), "llm")
