"""Watchdog and hop acknowledgement engine for M0 transport.

Defends against blackhole and greyhole attacks by:
  1. Expecting next-hop relay transmissions within a configurable timeout.
  2. Promiscuously observing the RF medium to verify whether a relay actually forwards messages.
  3. Feeding verified forwards ('forward_success') and dropped frames ('forward_failure') into M5 BetaRep.
  4. Triggering multipath retransmissions along disjoint paths when a relay fails to forward.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional, Set, Tuple


@dataclass
class PendingForward:
    relay: str
    mid: str
    sent_at: float
    deadline: float
    is_critical: bool


class Watchdog:
    def __init__(self, rep: Optional[Any] = None, *,
                 ack_timeout_s: float = 8.0,
                 trust_threshold: float = 0.35):
        """rep: Optional M5 BetaRep or TrustEngine to record evidence."""
        self.rep = rep
        self.ack_timeout_s = ack_timeout_s
        self.trust_threshold = trust_threshold
        self._pending: Dict[Tuple[str, str], PendingForward] = {}  # (relay, mid) -> PendingForward
        self._penalized: Set[str] = set()

    def track(self, relay: str, mid: str, now: float, is_critical: bool = False):
        """Track an outgoing transmission sent to `relay` expecting a forward."""
        key = (relay, mid)
        self._pending[key] = PendingForward(
            relay=relay,
            mid=mid,
            sent_at=now,
            deadline=now + self.ack_timeout_s,
            is_critical=is_critical,
        )

    def observe_forward(self, relay: str, mid: str, now: float) -> bool:
        """Called when a forward or hop ack from `relay` for message `mid` is observed."""
        key = (relay, mid)
        if key in self._pending:
            del self._pending[key]
            if self.rep is not None and hasattr(self.rep, "record"):
                self.rep.record(relay, "forward_success", now)
            return True
        return False

    def tick(self, now: float) -> List[Tuple[str, str, bool]]:
        """Check for expired forward deadlines.
        Returns a list of failed (relay, mid, is_critical) entries for multipath rerouting."""
        failed: List[Tuple[str, str, bool]] = []
        expired_keys = [k for k, p in self._pending.items() if now >= p.deadline]

        for k in expired_keys:
            p = self._pending.pop(k)
            failed.append((p.relay, p.mid, p.is_critical))
            self._penalized.add(p.relay)
            if self.rep is not None and hasattr(self.rep, "record"):
                self.rep.record(p.relay, "forward_failure", now)

        return failed

    def is_trusted(self, relay: str, now: float) -> bool:
        """Returns False if relay has been penalized by forward failures."""
        if relay in self._penalized:
            if self.rep is not None and hasattr(self.rep, "mean"):
                return self.rep.mean(relay, now) >= self.trust_threshold
            return False
        return True
