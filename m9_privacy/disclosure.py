"""Two-stage disclosure.

Stage 1 (mesh): a coarse cell, a coarse category and the skill/item needed. Never exact location,
health features, free text or quantity; condition-revealing tags (insulin, oxygen, dialysis) are
generalised to "medicine".
Stage 2: exact position, confidence radius, features and the current pseudonym are sealed to ONE
responder's box key, and only after that responder accepted. The number of responders who can ever
receive details is capped, so a fake "accept" cannot harvest locations.
"""
from __future__ import annotations

import math
from typing import Callable, Dict, Optional, Sequence, Set, Tuple

from core.types import HLC, Message, Priority, canonical

from .pseudonyms import PseudonymChain
from .sealed_envelope import seal

KIND_CASE, KIND_SEALED = "m9.case", "m9.sealed"
CRITICAL = {"not_breathing", "severe_bleeding", "unconscious"}
GENERALISE = {"insulin": "medicine", "oxygen": "medicine", "dialysis": "medicine"}
PUBLIC_TAGS = {"medic", "medicine", "blood", "boat", "lifejacket", "rope", "stretcher", "water", "food",
               "shelter", "vehicle", "fuel", "power"}
SKILL_TAGS = {"medic", "boat"}
CAT_OF = {"medic": "medical", "medicine": "medical", "blood": "medical", "boat": "rescue",
          "lifejacket": "rescue", "rope": "rescue", "stretcher": "rescue", "water": "supply",
          "food": "supply", "fuel": "supply", "power": "supply", "vehicle": "supply", "shelter": "shelter"}
_RANK = ("medical", "rescue", "supply", "shelter", "other")


def cell_of(pos: Tuple[float, float], cell_m: float = 500.0) -> str:
    return f"{math.floor(pos[0] / cell_m)}:{math.floor(pos[1] / cell_m)}"


def public_tags(tags: Sequence[str]) -> Tuple[str, ...]:
    out = []
    for t in tags:
        t = GENERALISE.get(t, t)
        if t in PUBLIC_TAGS and t not in out:
            out.append(t)
    return tuple(out)


def category_of(tags: Sequence[str], features: Sequence[str]) -> str:
    cats = {CAT_OF[t] for t in public_tags(tags)}
    if set(features) & (CRITICAL | {"medication_dependent", "pregnant"}):
        cats.add("medical")
    if "trapped" in features:
        cats.add("rescue")
    return next((c for c in _RANK if c in cats), "other")


class Case:
    def __init__(self, case_id: str, pos: Tuple[float, float], radius_m: float, tags: Sequence[str] = (),
                 features: Sequence[str] = (), location_text: str = "", quantity: Optional[int] = None,
                 chain: Optional[PseudonymChain] = None, cell_m: float = 500.0):
        self.id, self.pos, self.radius_m = case_id, (float(pos[0]), float(pos[1])), float(radius_m)
        self.tags, self.features = tuple(tags), tuple(features)
        self.location_text, self.quantity, self.chain, self.cell_m = location_text, quantity, chain, cell_m

    @property
    def cell(self) -> str:
        return cell_of(self.pos, self.cell_m)

    def public(self) -> dict:
        pub = public_tags(self.tags)
        need = "medic" if (set(self.features) & CRITICAL or "medic" in pub) else next(
            (t for t in pub if t in SKILL_TAGS), None)
        return {"c": self.id, "cell": self.cell, "cat": category_of(self.tags, self.features),
                "need": need, "tag": pub[0] if pub else None}

    def private(self, now: float) -> dict:
        return {"c": self.id, "pos": [round(self.pos[0], 1), round(self.pos[1], 1)],
                "r": round(self.radius_m, 1), "feat": list(self.features), "loc": self.location_text,
                "qty": self.quantity, "pid": self.chain.pid(now) if self.chain else None}


def public_message(case: Case, pseudonym: str, hlc: HLC, now: float, ttl_s: float = 3600.0) -> Message:
    pub = case.public()
    return Message(KIND_CASE, Priority.DISTRESS, pseudonym, pub, hlc, dest_cell=pub["cell"],
                   ttl_s=ttl_s, created=now)


def sealed_message(blob: bytes, pseudonym: str, cell: str, hlc: HLC, now: float) -> Message:
    return Message(KIND_SEALED, Priority.DISPATCH, pseudonym, {"b": blob.hex()}, hlc, dest_cell=cell, created=now)


class Discloser:
    def __init__(self, case: Case, directory: Dict[str, bytes], max_responders: int = 2):
        self.case, self.dir, self.max = case, directory, max_responders
        self.accepted: Set[str] = set()
        self._sent: Dict[str, bytes] = {}

    def accept(self, responder: str) -> bool:
        if responder not in self.dir:
            return False
        if responder not in self.accepted and len(self.accepted) >= self.max:
            return False
        self.accepted.add(responder)
        return True

    def release(self, responder: str, now: float, sign: Optional[Callable[[bytes], str]] = None,
                nonce: Optional[bytes] = None) -> bytes:
        """Sealed details for an accepted responder. Same blob if asked again (no extra exposure)."""
        if responder not in self.accepted:
            raise PermissionError("details are released only after acceptance")
        if responder not in self._sent:
            payload = self.case.private(now)
            sig = sign(canonical([self.case.id, payload])) if sign else None
            self._sent[responder] = seal(self.dir[responder], self.case.chain.pid(now) if self.case.chain
                                         else self.case.id, payload, nonce=nonce, sig=sig)
        return self._sent[responder]
