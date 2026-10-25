"""Proof of co-presence: mutual BLE/UWB encounter attestations from distinct real devices.

An Encounter is signed by BOTH devices over (pair, epoch). A node's weight is the share of the
needed distinct partners it met inside the window. Both parties must be enrolled on different
hardware, so "one node pretending to be 40 nurses" gets no partners: its 40 identities cannot
exist as 40 enrolled keys, and a real phone can only be near so many others per epoch.
A ring of genuinely colluding phones can still raise weight; M5 (collusion) covers that.
"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from typing import Dict, Set, Tuple

from core.types import canonical

from .keys import Registry


def encounter_body(a: str, b: str, epoch: int) -> bytes:
    lo, hi = sorted((a, b))
    return canonical(["enc", lo, hi, epoch])


@dataclass(frozen=True)
class Encounter:
    a: str
    b: str
    epoch: int
    sig_a: str
    sig_b: str


class CoPresence:
    def __init__(self, registry: Registry, epoch_s: float = 300.0, window_epochs: int = 6,
                 max_contacts: int = 12, k_full: int = 5):
        self.reg, self.epoch_s, self.window = registry, epoch_s, window_epochs
        self.max_contacts, self.k_full = max_contacts, k_full
        self._seen: Set[Tuple[str, str, int]] = set()
        self._contacts: Dict[Tuple[str, int], Set[str]] = defaultdict(set)   # (node, epoch) -> partners

    def epoch_of(self, t: float) -> int:
        return int(t // self.epoch_s)

    def make(self, a: str, b: str, t: float) -> Encounter:
        """Sim helper: both phones sign when they hear each other."""
        e = self.epoch_of(t)
        body = encounter_body(a, b, e)
        return Encounter(a, b, e, self.reg.sign(a, body), self.reg.sign(b, body))

    def add(self, enc: Encounter) -> bool:
        if enc.a == enc.b or not (self.reg.enrolled(enc.a) and self.reg.enrolled(enc.b)):
            return False
        if self.reg.key_id_of(enc.a) == self.reg.key_id_of(enc.b):
            return False
        body = encounter_body(enc.a, enc.b, enc.epoch)
        if not (self.reg.verify(enc.a, body, enc.sig_a) and self.reg.verify(enc.b, body, enc.sig_b)):
            return False
        key = (*sorted((enc.a, enc.b)), enc.epoch)
        if key in self._seen:
            return False
        for x, y in ((enc.a, enc.b), (enc.b, enc.a)):
            s = self._contacts[(x, enc.epoch)]
            if y not in s and len(s) >= self.max_contacts:
                return False                              # nobody is near that many phones at once
        self._seen.add(key)
        self._contacts[(enc.a, enc.epoch)].add(enc.b)
        self._contacts[(enc.b, enc.epoch)].add(enc.a)
        return True

    def partners(self, node: str, now: float) -> Set[str]:
        cur = self.epoch_of(now)
        out: Set[str] = set()
        for e in range(cur - self.window + 1, cur + 1):
            out |= self._contacts.get((node, e), set())
        return out

    def weight(self, node: str, now: float) -> float:
        return min(1.0, len(self.partners(node, now)) / self.k_full)
