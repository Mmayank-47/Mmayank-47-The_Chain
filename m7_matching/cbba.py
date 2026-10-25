"""CBBA-style decentralised auction, bundle size 1 (a responder works one case at a time).

Each phone holds a table {agent: Entry(epoch, task, bid)}. Merge keeps, per agent, the entry with
the higher epoch (only an agent writes its own entry), so tables converge in any gossip order and
survive partitions. A task's owner is the highest bid (ties: lower agent id). An agent bids only
on tasks it would win, and drops its task only when outbid, so it never returns to a task it lost
and the auction terminates for fixed utilities. Islands converge independently; when they merge,
duplicate claims on a task resolve by the same rule and the loser re-bids elsewhere.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Dict, Iterable, List, Optional, Tuple


@dataclass(frozen=True)
class Entry:
    epoch: int
    task: Optional[str]
    bid: float


def _beats(bid: float, who: str, obid: float, owho: str) -> bool:
    return bid > obid + 1e-12 or (abs(bid - obid) <= 1e-12 and who < owho)


class Bidder:
    def __init__(self, agent_id: str, utilities: Dict[str, float]):
        self.id = agent_id
        self.u = dict(utilities)
        self.table: Dict[str, Entry] = {agent_id: Entry(0, None, 0.0)}

    def set_utilities(self, u: Dict[str, float]):
        self.u = dict(u)

    def forget(self, agent: str):
        """M8 lease expired / agent gone: free whatever it was holding."""
        if agent != self.id:
            self.table.pop(agent, None)

    def _best_other(self, task: str) -> Optional[Tuple[float, str]]:
        best = None
        for a, e in self.table.items():
            if a != self.id and e.task == task and (best is None or _beats(e.bid, a, best[0], best[1])):
                best = (e.bid, a)
        return best

    def merge(self, table: Dict[str, Entry]) -> bool:
        changed = False
        for a, e in table.items():
            if a == self.id:
                continue
            cur = self.table.get(a)
            if cur is None or e.epoch > cur.epoch:
                self.table[a] = e
                changed = True
        return changed

    def step(self) -> bool:
        own = self.table[self.id]
        if own.task is not None:
            bid, other = self.u.get(own.task, -math.inf), self._best_other(own.task)
            if bid > 0 and (other is None or _beats(bid, self.id, other[0], other[1])):
                if abs(bid - own.bid) > 1e-12:
                    self.table[self.id] = Entry(own.epoch + 1, own.task, bid)
                    return True
                return False
        best: Optional[Tuple[float, str]] = None
        for t, bid in self.u.items():
            if bid <= 0:
                continue
            other = self._best_other(t)
            if other is not None and not _beats(bid, self.id, other[0], other[1]):
                continue
            if best is None or bid > best[0] + 1e-12 or (abs(bid - best[0]) <= 1e-12 and t < best[1]):
                best = (bid, t)
        target = Entry(own.epoch + 1, best[1], best[0]) if best else Entry(own.epoch + 1, None, 0.0)
        if (target.task, target.bid) == (own.task, own.bid):
            return False
        self.table[self.id] = target
        return True

    def assignment(self) -> Dict[str, str]:
        """task -> owning agent, as far as this phone knows."""
        own: Dict[str, Tuple[float, str]] = {}
        for a, e in self.table.items():
            if e.task is not None and (e.task not in own or _beats(e.bid, a, own[e.task][0], own[e.task][1])):
                own[e.task] = (e.bid, a)
        return {t: a for t, (_, a) in own.items()}

    def my_task(self) -> Optional[str]:
        t = self.table[self.id].task
        return t if t is not None and self.assignment().get(t) == self.id else None


def gossip(bidders: Dict[str, Bidder], links: Iterable[Tuple[str, str]], max_rounds: int = 100) -> int:
    """Synchronous rounds over the given links until nothing changes. Returns rounds used."""
    links = list(links)
    for rnd in range(1, max_rounds + 1):
        changed = False
        snap = {i: dict(b.table) for i, b in bidders.items()}
        for a, b in links:
            changed |= bidders[a].merge(snap[b])
            changed |= bidders[b].merge(snap[a])
        for b in bidders.values():
            changed |= b.step()
        if not changed:
            return rnd
    raise RuntimeError("auction did not converge")
