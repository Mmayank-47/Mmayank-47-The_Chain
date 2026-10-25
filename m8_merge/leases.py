"""Dispatch leases: a dispatch is alive from its creation or last heartbeat for `lease_ms`.

Pure functions over HLC wall milliseconds. Replica.ingest already drops future-skewed stamps, so a
hostile heartbeat cannot stretch a lease. The book (conflict_rules) uses these to decide which
dispatches are live; M7 uses `expired` to learn which responders to `forget` in the auction.
"""
from __future__ import annotations

from typing import Iterable, List

from core.types import DispatchState


def alive_ms(created_wall_ms: int, beat_ms: int) -> int:
    """Last moment the responder was heard from."""
    return max(created_wall_ms, beat_ms)


def expires_at(alive: int, lease_ms: int) -> int:
    return alive + lease_ms


def is_live(alive: int, now_ms: int, lease_ms: int) -> bool:
    return now_ms - alive <= lease_ms


def expired(recs: Iterable, now_ms: int, lease_ms: int) -> List:
    """Orphans: lease run out and not RESOLVED. Deterministic order (by dispatch id)."""
    out = [r for r in recs
           if r.state != DispatchState.RESOLVED and not is_live(r.alive_ms(), now_ms, lease_ms)]
    return sorted(out, key=lambda r: r.did)
