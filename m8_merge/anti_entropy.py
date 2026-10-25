"""Rate-limited summary exchange with batched backfill, transport-agnostic.

Flow between phones A and B (frames are plain dicts; the caller moves them over M0):
  A.request(B, now)            -> {"t": "ae.sum", "s": <A's version vector>}   (at most once per interval)
  B.on_summary(A, frame, now)  -> first {"t": "ae.ev", "e": [...], "more": bool} batch for A
  B.next_batch(A)              -> further batches, one per call, until None (caller paces airtime)
  A.on_events(B, frame, now)   -> number of events appended to A's logs

Per-node event order is preserved inside and across batches (the EventStore needs seq order).
Every size is bounded, because a summary or event batch off the air is hostile input: summaries
cap their node count, batches cap their length, and the peer tables cap how many peers we track.
"""
from __future__ import annotations

import math
from collections import Counter
from typing import Dict, List, Optional

from .event_log import Event
from .replica import Replica


class AntiEntropy:
    def __init__(self, replica: Replica, *, min_interval: float = 5.0, batch: int = 32,
                 max_nodes: int = 512, max_peers: int = 256, max_backlog: int = 4096):
        self.r = replica
        self.min_interval, self.batch = min_interval, batch
        self.max_nodes, self.max_peers, self.max_backlog = max_nodes, max_peers, max_backlog
        self._sent: Dict[str, float] = {}
        self._recv: Dict[str, float] = {}
        self._backlog: Dict[str, List[Event]] = {}
        self.dropped: Counter = Counter()

    # ---- bookkeeping -------------------------------------------------------------------------
    def _touch(self, table: dict, peer: str, value) -> None:
        if peer not in table and len(table) >= self.max_peers:
            del table[next(iter(table))]                  # oldest insertion
        table[peer] = value

    def _due(self, table: dict, peer: str, now: float) -> bool:
        last = table.get(peer)
        return last is None or now - last >= self.min_interval

    def _valid_summary(self, s) -> bool:
        if not isinstance(s, dict) or len(s) > self.max_nodes:
            return False
        for node, v in s.items():
            if not isinstance(node, str) or not 0 < len(node) <= 64:
                return False
            if not (isinstance(v, (list, tuple)) and len(v) == 2):
                return False
            n, h = v
            if isinstance(n, bool) or not isinstance(n, int) or n < 0 or not isinstance(h, str) or len(h) > 64:
                return False
        return True

    # ---- initiator ---------------------------------------------------------------------------
    def request(self, peer: str, now: float) -> Optional[dict]:
        """Summary frame for `peer`, or None if we asked them less than `min_interval` ago."""
        if not self._due(self._sent, peer, now):
            return None
        self._touch(self._sent, peer, now)
        return {"t": "ae.sum", "s": self.r.summary()}

    # ---- responder ---------------------------------------------------------------------------
    def on_summary(self, peer: str, frame, now: float) -> Optional[dict]:
        s = frame.get("s") if isinstance(frame, dict) else None
        if not self._valid_summary(s):
            self.dropped["bad_summary"] += 1
            return None
        if not self._due(self._recv, peer, now):
            self.dropped["summary_rate"] += 1
            return None
        self._touch(self._recv, peer, now)
        missing = self.r.events_for({n: list(v) for n, v in s.items()})
        # a fresh summary supersedes whatever was left from the last one
        self._touch(self._backlog, peer, missing[:self.max_backlog])
        return self.next_batch(peer)

    def next_batch(self, peer: str) -> Optional[dict]:
        q = self._backlog.get(peer)
        if not q:
            self._backlog.pop(peer, None)
            return None
        head, rest = q[:self.batch], q[self.batch:]
        if rest:
            self._backlog[peer] = rest
        else:
            del self._backlog[peer]
        return {"t": "ae.ev", "e": [e.to_json() for e in head], "more": bool(rest)}

    def pending_for(self, peer: str) -> int:
        return len(self._backlog.get(peer, ()))

    # ---- receiver of events ------------------------------------------------------------------
    def on_events(self, peer: str, frame, now: float = 0.0) -> int:
        raw = frame.get("e") if isinstance(frame, dict) else None
        if not isinstance(raw, list):
            self.dropped["bad_events"] += 1
            return 0
        if len(raw) > self.batch:
            self.dropped["oversize_batch"] += 1
            return 0
        evs: List[Event] = []
        for j in raw:
            try:
                evs.append(Event.from_json(j))
            except (KeyError, TypeError, ValueError, IndexError):
                self.dropped["bad_event"] += 1
        return self.r.ingest(evs)
