"""Signed, hash-chained per-node event logs + a store that accepts events from everyone.

Events from one node must arrive in seq order; early ones are buffered until the gap fills
(anti-entropy fills gaps). A node showing two validly signed, conflicting events (same seq, or a
`prev` that breaks its own chain) has equivocated: it is recorded in `forks` with the evidence,
quarantined (nothing more is accepted from it) and `on_fork` listeners (M5) are told once.
A peer's summary is unsigned, so a mismatching one only makes the node a `suspect`, never a fork.

Signatures are checked by a core.crypto.Signer, in practice M1's Registry: an event only counts if
its author is an enrolled hardware key. There is deliberately no default signer.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable, Dict, List, Set, Tuple

from core.crypto import Signer
from core.types import HLC, canonical, sha256_hex


def _body(node, seq, prev, hlc, kind, data) -> dict:
    return {"n": node, "s": seq, "p": prev, "h": hlc.to_json(), "k": kind, "d": data}


@dataclass(frozen=True)
class Event:
    node: str
    seq: int
    prev: str          # hash of this node's previous event ("" for the first)
    hlc: HLC
    kind: str
    data: dict
    hash: str
    sig: str

    @staticmethod
    def make(node, seq, prev, hlc, kind, data, signer) -> "Event":
        h = sha256_hex(canonical(_body(node, seq, prev, hlc, kind, data)))
        return Event(node, seq, prev, hlc, kind, data, h, signer.sign(node, h.encode()))

    def to_json(self) -> dict:
        return {**_body(self.node, self.seq, self.prev, self.hlc, self.kind, self.data),
                "x": self.hash, "g": self.sig}

    @staticmethod
    def from_json(j: dict) -> "Event":
        return Event(j["n"], int(j["s"]), j["p"], HLC.from_json(j["h"]), j["k"], j["d"], j["x"], j["g"])


class EventStore:
    def __init__(self, signer: Signer, max_pending: int = 256):
        self.signer = signer
        self.max_pending = max_pending          # caps memory a hostile peer can make us buffer
        self.logs: Dict[str, List[Event]] = {}
        self.pending: Dict[str, Dict[int, Event]] = {}
        self.forks: Set[str] = set()
        self.fork_evidence: Dict[str, Tuple[Event, Event]] = {}   # node -> first conflicting pair
        self.suspects: Set[str] = set()          # a peer's (unsigned) summary disagreed with our log
        self.on_fork: List[Callable[[str], None]] = []   # called once per newly forked node (M5 hooks in)
        self.dropped_quarantined = 0

    def _valid(self, ev: Event) -> bool:
        body = _body(ev.node, ev.seq, ev.prev, ev.hlc, ev.kind, ev.data)
        return (bool(ev.node) and ev.seq >= 0 and sha256_hex(canonical(body)) == ev.hash
                and self.signer.verify(ev.node, ev.hash.encode(), ev.sig))

    def _fork(self, node: str, ours: Event, theirs: Event) -> None:
        """Two validly signed events that cannot both be true: `node` equivocated. Quarantine it
        and tell the listeners, once."""
        if node in self.forks:
            return
        self.forks.add(node)
        self.fork_evidence[node] = (ours, theirs)
        self.pending.pop(node, None)              # buffered events of a forker never land
        for cb in list(self.on_fork):
            try:
                cb(node)
            except Exception:                     # a listener bug must not break ingestion
                pass

    def add(self, ev: Event) -> List[Event]:
        """Returns events newly appended in order (the given one plus any it unblocked)."""
        if not self._valid(ev):
            return []
        if ev.node in self.forks:                 # quarantined: validly signed, no longer believed
            self.dropped_quarantined += 1
            return []
        log = self.logs.setdefault(ev.node, [])
        if ev.seq < len(log):
            if log[ev.seq].hash != ev.hash:
                self._fork(ev.node, log[ev.seq], ev)
            return []
        if ev.seq > len(log):
            p = self.pending.setdefault(ev.node, {})
            if len(p) < self.max_pending:
                p.setdefault(ev.seq, ev)
            return []
        out, cur = [], ev
        while cur is not None:
            if cur.prev != (log[-1].hash if log else ""):
                self._fork(ev.node, log[-1] if log else cur, cur)
                break
            log.append(cur)
            out.append(cur)
            cur = self.pending.get(ev.node, {}).pop(len(log), None)
        return out

    def summary(self) -> Dict[str, list]:
        """Version vector with head hashes: {node: [count, head_hash]}."""
        return {n: [len(l), l[-1].hash] for n, l in self.logs.items() if l}

    def missing(self, remote: Dict[str, list]) -> List[Event]:
        """Events the remote summary lacks. A head hash that differs at the same count makes the
        node a suspect only: a summary is the peer's say-so, not a signed event, so it can never
        quarantine anyone (that would let one hostile phone silence an honest node)."""
        out = []
        for node, log in self.logs.items():
            rc, rh = remote.get(node, [0, ""])
            if rc > 0 and rc <= len(log) and log[rc - 1].hash != rh:
                self.suspects.add(node)
            if rc < len(log):
                out += log[rc:]
        return out
