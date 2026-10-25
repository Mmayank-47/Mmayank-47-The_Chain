"""Baseline policy: trusts every claim, greedy nearest-idle responder, no reconciliation.
This is the 'before' every real module (M2-M11) must beat. Real stack implements the same
3-method interface: p_valid(claim), observe(claim), tick(world) -> [Dispatch]."""
import math

from .world import Dispatch


class NaivePolicy:
    def __init__(self, w):
        self.known = {}   # node id -> {"claims": {id: Claim}, "assigned": {claim id: responder id}}

    def _k(self, i):
        return self.known.setdefault(i, {"claims": {}, "assigned": {}})

    def p_valid(self, c):
        return 1.0

    def observe(self, c):
        self._k(c.node)["claims"][c.id] = c

    def tick(self, w):
        out = []
        for comp in w.components():
            claims, assigned = {}, {}
            for i in comp:   # shortcut: gossip is instant inside a component; upgrade with M0/M8
                k = self._k(i)
                claims.update(k["claims"])
                for cid, r in k["assigned"].items():
                    assigned.setdefault(cid, r)
            idle = [w.nodes[i] for i in comp
                    if w.nodes[i].role == "responder" and w.nodes[i].target is None and w.nodes[i].status == "ok"]
            for c in sorted(claims.values(), key=lambda c: -c.urgency):
                if c.id in assigned or not idle:
                    continue
                r = min(idle, key=lambda r: math.dist(r.pos, c.pos))
                idle.remove(r)
                assigned[c.id] = r.id
                out.append(Dispatch(r.id, c, self.p_valid(c)))
            for i in comp:
                self.known[i] = {"claims": dict(claims), "assigned": dict(assigned)}
        return out
