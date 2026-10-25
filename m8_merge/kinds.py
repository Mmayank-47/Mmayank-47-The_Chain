"""Extra M8 event kinds (hazard, vouch, revoke, challenge, reputation) and the Projector.

Everything shared across the mesh rides the same signed, hash-chained logs, so it converges by the
same anti-entropy as dispatches. The DispatchBook ignores these kinds; a Projector attached to a
Replica (`replica.on_apply`) feeds each newly appended event into the local M4 / M5 / M1 state.

An event is evidence that its AUTHOR said something. The projector never trusts numbers inside the
event: report and reputation weights come from `weight_fn(author)` (M5 trust x M1 co-presence), the
reporter is always the signing node, and anything malformed is counted in `rejected` and dropped.
"""
from __future__ import annotations

import math
from collections import Counter
from typing import Callable, Dict, FrozenSet, Optional, Sequence

from .event_log import Event

HAZARD, VOUCH, REVOKE, CHALLENGE, REPUTATION = "hazard", "vouch", "revoke", "challenge", "reputation"
NEW_KINDS: FrozenSet[str] = frozenset({HAZARD, VOUCH, REVOKE, CHALLENGE, REPUTATION})

MAX_STR, MAX_COORD = 64, 1e7
HAZARD_KINDS = ("hazard", "safe")
DEFAULT_REPUTATION_EVENTS = frozenset({"verified_arrival", "confirmed_hazard", "corroborated_claim",
                                       "failed_claim", "failed_challenge", "false_hazard"})


def _str(x, name: str) -> str:
    if not isinstance(x, str) or not 0 < len(x) <= MAX_STR:
        raise ValueError(f"bad {name}")
    return x


def _unit(x, name: str = "w") -> float:
    if isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x):
        raise ValueError(f"bad {name}")
    return max(0.0, min(1.0, float(x)))


def _pos(p) -> tuple:
    if not (isinstance(p, (list, tuple)) and len(p) == 2):
        raise ValueError("bad pos")
    out = []
    for c in p:
        if isinstance(c, bool) or not isinstance(c, (int, float)) or not math.isfinite(c) or abs(c) > MAX_COORD:
            raise ValueError("bad pos")
        out.append(float(c))
    return tuple(out)


# ---- builders: validated `data` dicts for Replica.emit(kind, data) -----------------------------
def hazard_data(pos, kind: str) -> dict:
    if kind not in HAZARD_KINDS:
        raise ValueError("kind must be 'hazard' or 'safe'")
    return {"pos": list(_pos(pos)), "kind": kind}


def vouch_data(to: str, w: float = 1.0) -> dict:
    return {"to": _str(to, "to"), "w": _unit(w)}


def revoke_data(revocation_json: dict) -> dict:
    """m1_identity.revocation.Revocation.to_json(): self-authenticating (authority-signed)."""
    return dict(revocation_json)


def challenge_data(claim_id: str, claimant: str, cid: str, deadline: float) -> dict:
    return {"claim": _str(claim_id, "claim"), "claimant": _str(claimant, "claimant"),
            "cid": _str(cid, "cid"), "deadline": float(deadline)}


def reputation_data(node: str, event: str, w: float = 1.0) -> dict:
    return {"node": _str(node, "node"), "event": _str(event, "event"), "w": _unit(w)}


class Projector:
    """Applies new-kind events to local state. Any target may be None (then that kind is a no-op).

    hazards: m4 HazardGrid (.report)      graph: m5 VouchGraph (.vouch)
    revocations: m1 RevocationList (.ingest)      reputation: m5 BetaRep (.record)
    weight_fn(node) -> 0..1: how much this phone believes `node` (M5 trust x M1 co-presence).
    """

    def __init__(self, *, hazards=None, graph=None, revocations=None, reputation=None,
                 weight_fn: Callable[[str], float] = lambda node: 0.5,
                 reputation_events: FrozenSet[str] = DEFAULT_REPUTATION_EVENTS,
                 max_challenges: int = 1024):
        self.hazards, self.graph, self.revocations, self.reputation = hazards, graph, revocations, reputation
        self.weight_fn, self.reputation_events, self.max_challenges = weight_fn, reputation_events, max_challenges
        self.challenges: Dict[str, dict] = {}       # cid -> {verifier, claim, claimant, deadline}
        self.rejected: Counter = Counter()
        self._h = {HAZARD: self._hazard, VOUCH: self._vouch, REVOKE: self._revoke,
                   CHALLENGE: self._challenge, REPUTATION: self._reputation}

    def attach(self, replica) -> "Projector":
        replica.on_apply.append(self.apply)
        return self

    def apply(self, ev: Event) -> None:
        h = self._h.get(ev.kind)
        if h is None:
            return
        try:
            ok = h(ev)
        except (KeyError, TypeError, ValueError, AttributeError):
            ok = False
        if not ok:
            self.rejected[ev.kind] += 1

    def _w(self, node: str) -> float:
        return _unit(self.weight_fn(node))

    def _hazard(self, ev: Event) -> bool:
        d = ev.data
        pos, kind = _pos(d["pos"]), d["kind"]
        w = self._w(ev.node)
        if kind not in HAZARD_KINDS or w <= 0:
            return False
        if self.hazards is not None:
            self.hazards.report(ev.node, pos, kind, ev.hlc.wall / 1000.0, w)
        return True

    def _vouch(self, ev: Event) -> bool:
        to, w = _str(ev.data["to"], "to"), _unit(ev.data.get("w", 1.0))
        if to == ev.node or w <= 0:
            return False
        if self.graph is not None:
            self.graph.vouch(ev.node, to, w)
        return True

    def _revoke(self, ev: Event) -> bool:
        if self.revocations is None:
            return True
        return self.revocations.ingest([ev.data]) > 0     # forged / out-of-scope / duplicate -> 0

    def _challenge(self, ev: Event) -> bool:
        d = ev.data
        cid = _str(d["cid"], "cid")
        deadline = d["deadline"]
        if isinstance(deadline, bool) or not isinstance(deadline, (int, float)) or not math.isfinite(deadline):
            return False
        if cid in self.challenges or len(self.challenges) >= self.max_challenges:
            return False
        self.challenges[cid] = {"verifier": ev.node, "claim": _str(d["claim"], "claim"),
                                "claimant": _str(d["claimant"], "claimant"), "deadline": float(deadline)}
        return True

    def _reputation(self, ev: Event) -> bool:
        d = ev.data
        subject, event = _str(d["node"], "node"), _str(d["event"], "event")
        if subject == ev.node or event not in self.reputation_events:
            return False                                   # no self-praise, no invented events
        w = self._w(ev.node) * _unit(d.get("w", 1.0))
        if w <= 0:
            return False
        if self.reputation is not None:
            self.reputation.record(subject, event, ev.hlc.wall / 1000.0, w)
        return True
