"""Replica: one phone's M8 state. Local actions append signed events; ingest merges remote ones."""
from __future__ import annotations

import time
from typing import Callable, Dict, List, Optional

from core.types import DispatchState, HLClock
from .conflict_rules import DispatchBook, Plan, make_did, split_did
from .event_log import DevSigner, Event, EventStore


class Replica:
    def __init__(self, node_id: str, signer=None, now_ms: Optional[Callable[[], int]] = None,
                 lease_ms: int = 60_000):
        self.id = node_id
        self.now_ms = now_ms or (lambda: int(time.time() * 1000))
        self.clock = HLClock(node_id, self.now_ms)
        self.signer = signer or DevSigner()
        self.store = EventStore(self.signer)
        self.book = DispatchBook(lease_ms)
        self.on_emit: List[Callable[[Event], None]] = []   # M0 bridge hooks in here

    # ---- local actions
    def _emit(self, kind: str, data: dict) -> Event:
        log = self.store.logs.get(self.id, [])
        ev = Event.make(self.id, len(log), log[-1].hash if log else "", self.clock.now(),
                        kind, data, self.signer)
        for e in self.store.add(ev):
            self.book.apply(e)
        for cb in self.on_emit:
            cb(ev)
        return ev

    def open_claim(self, claim: str, cell: str, urgency: float) -> Event:
        return self._emit("claim", {"claim": claim, "cell": cell, "urgency": urgency})

    def dispatch(self, claim: str, responder: str) -> str:
        did = make_did(claim, responder)
        self._emit("dispatch", {"did": did})
        return did

    def set_state(self, did: str, state: DispatchState) -> Event:
        self._own(did)
        return self._emit("state", {"did": did, "state": int(state)})

    def heartbeat(self, did: str) -> Event:
        self._own(did)
        return self._emit("beat", {"did": did})

    def _own(self, did: str):
        if split_did(did)[1] != self.id:
            raise ValueError("only the dispatched responder reports its own progress")

    # ---- sync
    def ingest(self, events: List[Event]) -> int:
        n = 0
        for ev in events:
            for e in self.store.add(ev):
                if e.node != self.id:
                    self.clock.update(e.hlc)
                self.book.apply(e)
                n += 1
        return n

    def summary(self) -> Dict[str, list]:
        return self.store.summary()

    def events_for(self, remote_summary: Dict[str, list]) -> List[Event]:
        return self.store.missing(remote_summary)

    def plan(self, now_ms: Optional[int] = None) -> Plan:
        return self.book.plan(self.now_ms() if now_ms is None else now_ms)
