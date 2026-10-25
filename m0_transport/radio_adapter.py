"""Radio abstraction (BLE / Wi-Fi Aware / LoRa plug in here) + in-memory simulated medium."""
from __future__ import annotations

import random
from abc import ABC, abstractmethod
from collections import defaultdict
from typing import Dict, List, Optional, Set, Tuple


class Radio(ABC):
    @abstractmethod
    def broadcast(self, data: bytes, now: float): ...
    @abstractmethod
    def send(self, to: str, data: bytes, now: float) -> bool: ...
    @abstractmethod
    def poll(self, now: float) -> List[Tuple[str, bytes]]: ...


class SimMedium:
    """Shared air. Links are undirected; loss and latency are per frame."""

    def __init__(self, loss: float = 0.0, latency_s: float = 0.0,
                 rng: Optional[random.Random] = None):
        self.loss, self.latency = loss, latency_s
        self.rng = rng or random.Random(0)
        self.links: Dict[str, Set[str]] = defaultdict(set)
        self._q: List[Tuple[float, str, str, bytes]] = []
        self.frames_sent = 0
        self.bytes_sent = 0

    def link(self, a: str, b: str):
        self.links[a].add(b)
        self.links[b].add(a)

    def unlink(self, a: str, b: str):
        self.links[a].discard(b)
        self.links[b].discard(a)

    def isolate(self, a: str):
        for b in list(self.links[a]):
            self.unlink(a, b)

    def transmit(self, frm: str, to: str, data: bytes, now: float) -> bool:
        if to not in self.links[frm]:
            return False
        self.frames_sent += 1
        self.bytes_sent += len(data)
        if self.rng.random() < self.loss:
            return True            # sent, lost in the air
        self._q.append((now + self.latency, to, frm, data))
        return True

    def pop_for(self, node: str, now: float) -> List[Tuple[str, bytes]]:
        ready = [(t, to, frm, d) for (t, to, frm, d) in self._q if to == node and t <= now]
        self._q = [e for e in self._q if e not in ready]
        return [(frm, d) for (_, _, frm, d) in sorted(ready, key=lambda e: e[0])]


class SimRadio(Radio):
    def __init__(self, node_id: str, medium: SimMedium):
        self.id, self.m = node_id, medium

    def broadcast(self, data: bytes, now: float):
        for peer in list(self.m.links[self.id]):
            self.m.transmit(self.id, peer, data, now)

    def send(self, to: str, data: bytes, now: float) -> bool:
        return self.m.transmit(self.id, to, data, now)

    def poll(self, now: float) -> List[Tuple[str, bytes]]:
        return self.m.pop_for(self.id, now)
