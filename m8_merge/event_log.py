"""Signed, hash-chained per-node event logs + a store that accepts events from everyone.

Events from one node must arrive in seq order; early ones are buffered until the gap fills
(anti-entropy fills gaps). A node showing two different events for one seq is flagged as a fork.

Signatures are checked by a core.crypto.Signer, in practice M1's Registry: an event only counts if
its author is an enrolled hardware key. There is deliberately no default signer.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, List, Set

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

    def _valid(self, ev: Event) -> bool:
        body = _body(ev.node, ev.seq, ev.prev, ev.hlc, ev.kind, ev.data)
        return (bool(ev.node) and ev.seq >= 0 and sha256_hex(canonical(body)) == ev.hash
                and self.signer.verify(ev.node, ev.hash.encode(), ev.sig))

    def add(self, ev: Event) -> List[Event]:
        """Returns events newly appended in order (the given one plus any it unblocked)."""
        if not self._valid(ev):
            return []
        log = self.logs.setdefault(ev.node, [])
        if ev.seq < len(log):
            if log[ev.seq].hash != ev.hash:
                self.forks.add(ev.node)
            return []
        if ev.seq > len(log):
            p = self.pending.setdefault(ev.node, {})
            if len(p) < self.max_pending:
                p.setdefault(ev.seq, ev)
            return []
        out, cur = [], ev
        while cur is not None:
            if cur.prev != (log[-1].hash if log else ""):
                self.forks.add(ev.node)
                break
            log.append(cur)
            out.append(cur)
            cur = self.pending.get(ev.node, {}).pop(len(log), None)
        return out

    def summary(self) -> Dict[str, list]:
        """Version vector with head hashes: {node: [count, head_hash]}."""
        return {n: [len(l), l[-1].hash] for n, l in self.logs.items() if l}

    def missing(self, remote: Dict[str, list]) -> List[Event]:
        """Events the remote summary lacks. Also spots forks (same count, different head)."""
        out = []
        for node, log in self.logs.items():
            rc, rh = remote.get(node, [0, ""])
            if rc > 0 and rc <= len(log) and log[rc - 1].hash != rh:
                self.forks.add(node)
            if rc < len(log):
                out += log[rc:]
        return out
