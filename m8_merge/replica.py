"""Replica: one phone's M8 state. Local actions append signed events; ingest merges remote ones."""
from __future__ import annotations

import time
from typing import Callable, Dict, List, Optional

from core.crypto import Signer
from core.types import DispatchState, HLClock, MAX_DRIFT_MS
from .conflict_rules import DispatchBook, Plan, make_did, split_did
from .event_log import Event, EventStore


class Replica:
    def __init__(self, node_id: str, signer: Signer, now_ms: Optional[Callable[[], int]] = None,
                 lease_ms: int = 60_000, max_drift_ms: Optional[int] = MAX_DRIFT_MS):
        """signer: normally the shared M1 Registry (see m1_identity.keys.dev_fleet in the sim)."""
        self.id = node_id
        self.now_ms = now_ms or (lambda: int(time.time() * 1000))
        self.clock = HLClock(node_id, self.now_ms, max_drift_ms)
        self.signer = signer
        self.rejected_drift = 0                             # events dropped for future-skewed HLCs
        self.store = EventStore(self.signer)
        self.book = DispatchBook(lease_ms)
        self.on_emit: List[Callable[[Event], None]] = []   # M0 bridge hooks in here
        self.on_apply: List[Callable[[Event], None]] = []  # every newly appended event, local or remote
                                                           # (m8_merge.kinds.Projector hooks in here)

    # ---- local actions
    def _emit(self, kind: str, data: dict) -> Event:
        log = self.store.logs.get(self.id, [])
        ev = Event.make(self.id, len(log), log[-1].hash if log else "", self.clock.now(),
                        kind, data, self.signer)
        for e in self.store.add(ev):
            self._applied(e)
        for cb in self.on_emit:
            cb(ev)
        return ev

    def _applied(self, e: Event):
        self.book.apply(e)
        for cb in self.on_apply:
            cb(e)

    def emit(self, kind: str, data: dict) -> Event:
        """Append a signed event of any kind (see m8_merge.kinds for the builders)."""
        return self._emit(kind, data)

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
        """Merge remote events. An event stamped too far ahead of our physical clock is dropped
        before it touches the store: lease expiry is computed from HLC wall time, so one future
        stamp (a heartbeat dated next year) would otherwise keep a dead dispatch alive forever."""
        n = 0
        for ev in events:
            if not self.clock.drift_ok(ev.hlc):
                self.rejected_drift += 1
                continue
            for e in self.store.add(ev):
                if e.node != self.id and self.clock.drift_ok(e.hlc):
                    self.clock.update(e.hlc)
                self._applied(e)
                n += 1
        return n

    def summary(self) -> Dict[str, list]:
        return self.store.summary()

    def events_for(self, remote_summary: Dict[str, list]) -> List[Event]:
        return self.store.missing(remote_summary)

    def plan(self, now_ms: Optional[int] = None) -> Plan:
        return self.book.plan(self.now_ms() if now_ms is None else now_ms)
