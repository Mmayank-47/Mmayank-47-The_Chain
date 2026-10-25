"""Shared types: priorities, dispatch lattice, hybrid logical clock, wire Message."""
from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Any, Callable, Dict, Optional

NodeId = str


class Priority(IntEnum):
    """Lower number = sent first (distress > dispatch > hazard > chatter)."""
    DISTRESS = 0
    DISPATCH = 1
    HAZARD = 2
    CHATTER = 3


class DispatchState(IntEnum):
    """Monotonic lattice: a state can only move forward, never back."""
    PROPOSED = 0
    ACCEPTED = 1
    EN_ROUTE = 2
    ARRIVED = 3
    RESOLVED = 4


def canonical(obj: Any) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":")).encode()


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@dataclass(frozen=True, order=True)
class HLC:
    """Hybrid logical clock stamp. Orders by (wall ms, counter, node id)."""
    wall: int
    counter: int
    node: str

    def to_json(self):
        return [self.wall, self.counter, self.node]

    @staticmethod
    def from_json(x) -> "HLC":
        return HLC(int(x[0]), int(x[1]), str(x[2]))


ZERO_HLC = HLC(0, 0, "")

MAX_DRIFT_MS = 5 * 60 * 1000     # a remote stamp may lead our physical clock by at most this much
MAX_COUNTER = 1 << 20            # no honest node logs a million events inside one millisecond


class ClockDrift(ValueError):
    """A remote HLC is too far ahead of local time (or malformed) to be merged."""


class HLClock:
    """Standard HLC (Kulkarni et al.). now() = local/send event, update() = receive.

    An unbounded HLC lets one hostile or broken phone drag every clock (and every M8 lease, which
    is judged on HLC wall time) arbitrarily far into the future. `max_drift_ms` bounds how far
    ahead of OUR physical clock a remote stamp may be; None disables the bound.
    """

    def __init__(self, node: str, now_ms: Optional[Callable[[], int]] = None,
                 max_drift_ms: Optional[int] = MAX_DRIFT_MS):
        self.node = node
        self._now = now_ms or (lambda: int(time.time() * 1000))
        self.max_drift_ms = max_drift_ms
        self._wall = 0
        self._counter = 0

    def drift_ok(self, remote: HLC) -> bool:
        if remote.wall < 0 or not 0 <= remote.counter <= MAX_COUNTER:
            return False
        return self.max_drift_ms is None or remote.wall - self._now() <= self.max_drift_ms

    def now(self) -> HLC:
        pt = self._now()
        if pt > self._wall:
            self._wall, self._counter = pt, 0
        else:
            self._counter += 1
        return HLC(self._wall, self._counter, self.node)

    def update(self, remote: HLC) -> HLC:
        if not self.drift_ok(remote):
            raise ClockDrift(f"remote HLC {remote.to_json()} is outside the allowed drift")
        pt = self._now()
        new = max(self._wall, remote.wall, pt)
        if new == self._wall == remote.wall:
            c = max(self._counter, remote.counter) + 1
        elif new == self._wall:
            c = self._counter + 1
        elif new == remote.wall:
            c = remote.counter + 1
        else:
            c = 0
        self._wall, self._counter = new, c
        return HLC(new, c, self.node)


@dataclass
class Message:
    """Unit M0 carries. msg_id depends only on immutable content (not on hops/tokens)."""
    kind: str
    priority: Priority
    origin: str
    payload: dict
    hlc: HLC
    dest_cell: Optional[str] = None   # coarse cell (M9: never exact location)
    ttl_s: float = 3600.0
    created: float = 0.0              # seconds, originator's clock
    msg_id: str = field(init=False, default="")

    def __post_init__(self):
        body = {"k": self.kind, "o": self.origin, "d": self.payload, "h": self.hlc.to_json()}
        self.msg_id = sha256_hex(canonical(body))[:16]

    def expired(self, now: float) -> bool:
        return now - self.created > self.ttl_s

    def to_wire(self) -> dict:
        return {"k": self.kind, "p": int(self.priority), "o": self.origin, "d": self.payload,
                "h": self.hlc.to_json(), "c": self.dest_cell, "ttl": self.ttl_s, "cr": self.created}

    def wire_bytes(self) -> bytes:
        return canonical(self.to_wire())

    @staticmethod
    def from_wire(d: dict) -> "Message":
        return Message(kind=d["k"], priority=Priority(d["p"]), origin=d["o"], payload=d["d"],
                       hlc=HLC.from_json(d["h"]), dest_cell=d.get("c"),
                       ttl_s=float(d.get("ttl", 3600.0)), created=float(d.get("cr", 0.0)))
