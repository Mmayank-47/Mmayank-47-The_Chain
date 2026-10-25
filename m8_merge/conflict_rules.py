"""Dispatch book (a CRDT built from events) + the conflict rule + lease expiry.

Every field merges with max or min, so replaying the same events in any order gives the same book.
Rule: for one victim the furthest-along responder keeps it; losers short of ARRIVED are
redirected, nobody at the door is cancelled. Lease expired + not RESOLVED = orphan; a claim with
no live dispatch re-opens.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

from core.crdt import LWWMap
from core.types import HLC, DispatchState
from .event_log import Event

_INF = HLC(2 ** 62, 0, "")


def make_did(claim: str, responder: str) -> str:
    return f"{claim}|{responder}"


def split_did(did: str):
    claim, responder = did.rsplit("|", 1)
    return claim, responder


@dataclass
class Rec:
    did: str
    claim: str
    responder: str
    state: DispatchState = DispatchState.PROPOSED
    created: Optional[HLC] = None
    beat_ms: int = 0

    def alive_ms(self) -> int:
        return max(self.created.wall if self.created else 0, self.beat_ms)


@dataclass
class Plan:
    winners: Dict[str, str] = field(default_factory=dict)   # claim -> did that keeps it
    redirect: List[str] = field(default_factory=list)       # dids that should stand down
    reopen: List[str] = field(default_factory=list)         # claims with no live dispatch


class DispatchBook:
    def __init__(self, lease_ms: int = 60_000):
        self.lease_ms = lease_ms
        self.claims = LWWMap()
        self.recs: Dict[str, Rec] = {}

    def apply(self, ev: Event):
        d, k = ev.data, ev.kind
        if k == "claim":
            self.claims.set(d["claim"], {"cell": d.get("cell"), "urgency": d.get("urgency")}, ev.hlc)
        elif k in ("dispatch", "state", "beat"):
            claim, resp = split_did(d["did"])
            if k != "dispatch" and ev.node != resp:
                return                                # only the responder speaks for its progress
            r = self.recs.setdefault(d["did"], Rec(d["did"], claim, resp))
            if k == "dispatch":
                r.created = ev.hlc if r.created is None else min(r.created, ev.hlc)
            else:
                r.beat_ms = max(r.beat_ms, ev.hlc.wall)
                if k == "state":
                    r.state = max(r.state, DispatchState(d["state"]))

    def snapshot(self):
        """Comparable view for convergence checks."""
        return (sorted(self.claims.items(), key=lambda kv: kv[0]),
                sorted((r.did, int(r.state), r.created, r.beat_ms) for r in self.recs.values()))

    def plan(self, now_ms: int) -> Plan:
        p, by_claim = Plan(), {}
        for r in self.recs.values():
            by_claim.setdefault(r.claim, []).append(r)
        for claim, rs in by_claim.items():
            if any(r.state == DispatchState.RESOLVED for r in rs):
                continue
            live = [r for r in rs if now_ms - r.alive_ms() <= self.lease_ms]
            if not live:
                p.reopen.append(claim)
                continue
            win = min(live, key=lambda r: (-int(r.state), r.created or _INF, r.responder))
            p.winners[claim] = win.did
            p.redirect += [r.did for r in live if r is not win and r.state < DispatchState.ARRIVED]
        return p
