"""M3 signals. Each returns a log-likelihood ratio (LLR): >0 favours 'claim is real', <0 'fake'.

Pure functions over a Ledger of claims seen so far. Only what a phone can observe is used
(node, src, pos, t); never simulator ground truth. `node` = physical device (M1 binds it to a
hardware key), `src` = identity the claim asserts.
"""
from __future__ import annotations

import math
from collections import defaultdict
from typing import Callable, Dict, List, Optional

SAME_PLACE_M = 100.0     # > GPS noise: two claims this close describe one place
SAME_SPOT_M = 1.0        # copy-pasted coordinates
CORROB_M = 150.0
CORROB_WINDOW_S = 600.0
FANOUT_WINDOW_S = 600.0


class Ledger:
    """Every claim this phone has seen, indexed by physical device."""

    def __init__(self):
        self.by_id: Dict[int, object] = {}
        self.by_node: Dict[int, List[object]] = defaultdict(list)
        self.latest = 0.0

    def add(self, c) -> bool:
        if c.id in self.by_id:
            return False
        self.by_id[c.id] = c
        self.by_node[c.node].append(c)
        self.latest = max(self.latest, c.t)
        return True


def _key(c):
    return (c.t, c.id)


def identity_binding(c) -> float:
    """The device that sent the claim must be the identity it speaks for (spoof / sybil ids)."""
    return 0.5 if c.src == c.node else -4.0


def node_spread(L: Ledger, c) -> float:
    """One phone cannot be in many places: penalise earlier claims from this device elsewhere."""
    k = sum(1 for o in L.by_node[c.node]
            if o.id != c.id and _key(o) < _key(c) and math.dist(o.pos, c.pos) > SAME_PLACE_M)
    return -1.5 * min(k, 3)


def identity_fanout(L: Ledger, c) -> float:
    """One device speaking as many identities at about the same time."""
    srcs = {o.src for o in L.by_node[c.node]
            if o.id != c.id and abs(o.t - c.t) <= FANOUT_WINDOW_S} - {c.src}
    return -0.5 * min(len(srcs), 6)


def duplicate_spot(L: Ledger, c) -> float:
    """Identical coordinates asserted by different identities (copy-paste crowd)."""
    srcs = {o.src for o in L.by_id.values()
            if o.id != c.id and o.src != c.src and math.dist(o.pos, c.pos) <= SAME_SPOT_M}
    return -1.0 * min(len(srcs), 3)


def corroboration(L: Ledger, c) -> float:
    """Independent devices (other nodes, speaking for themselves) reporting the same place/time.
    Same-device repeats never count. Real independence needs M1 co-presence proofs (M5 for collusion)."""
    nodes = {o.node for o in L.by_id.values()
             if o.node != c.node and o.src == o.node
             and abs(o.t - c.t) <= CORROB_WINDOW_S and math.dist(o.pos, c.pos) <= CORROB_M}
    return 1.5 * min(len(nodes), 3)


def staleness(c, now: float, tau: float = 1800.0) -> float:
    """Unrefreshed claims lose credibility, capped so old real victims are not written off."""
    return -min(max(0.0, now - c.t) / tau, 1.0)


def credential(c, check: Optional[Callable[[object], Optional[bool]]]) -> float:
    """M1 hook: True = valid signed credential matches the need, False = forged/revoked, None = none."""
    if check is None:
        return 0.0
    r = check(c)
    return 0.0 if r is None else (3.0 if r else -4.0)
