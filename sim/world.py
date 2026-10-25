"""Ground-truth world: nodes, rising flood, radio links, partitions, outcomes.
Policies (the system under test) never see ground truth, only claims + w.components()."""
import math
import random
from dataclasses import dataclass
from itertools import count
from typing import NamedTuple, Optional

from . import attackers

SPEED = 1.5      # responder walking speed, m/s
ARRIVE = 60.0    # m, must exceed GPS noise so honest claims resolve


@dataclass
class Claim:
    id: int
    node: int            # physical phone that emitted it (used for gossip)
    src: int             # claimed identity (differs from node under sybil/spoof)
    pos: tuple
    t: float
    urgency: float       # 0..1
    need: Optional[str]  # "medic" or None
    kind: str            # victim | decoy | lure | sybil | spoof
    victim: Optional[int] = None   # true victim node id; None => fake
    battery: float = 1.0

    @property
    def real(self):
        return self.victim is not None


@dataclass
class Node:
    id: int
    role: str            # responder | victim | attacker
    pos: list
    skill: Optional[str] = None
    battery: float = 1.0
    target: Optional[Claim] = None
    status: str = "ok"   # ok | lost (entered water) | dead
    t0: float = 0.0
    deadline: float = 0.0
    rescued: Optional[float] = None
    died: Optional[float] = None


class Dispatch(NamedTuple):      # what a policy returns
    responder: int
    claim: Claim
    p_valid: float = 1.0         # policy's belief, for calibration plots


class Rec(NamedTuple):           # what the world logs
    t: float
    rid: int
    claim: Claim
    p_valid: float
    misuse: bool                 # medic sent to minor case while a critical one was pending


class World:
    def __init__(self, scn):
        self.scn, self.rng, self.t = scn, random.Random(scn.seed), 0.0
        self.nodes, self.pending, self.claims = [], [], []
        self.dispatches, self.events, self.dispatched = [], [], set()
        self._cid = count()
        for _ in range(scn.n_responders):
            self._add("responder", skill="medic" if self.rng.random() < scn.medic_frac else None)
        for _ in range(scn.n_victims):
            v = self._add("victim")
            u = self.rng.random()
            v.t0 = self.rng.uniform(0, 600)
            v.deadline = v.t0 + (1200 if u > 0.7 else 3000)
            g = scn.gps_sigma
            pos = (v.pos[0] + self.rng.gauss(0, g), v.pos[1] + self.rng.gauss(0, g))
            c = self.claim(v, v.id, pos, v.t0, u, "medic" if u > 0.7 else None, "victim", victim=v.id)
            c.battery = self.rng.uniform(0.1, 0.9)
            self.pending.append(c)
        for _ in range(scn.n_attackers):
            self._add("attacker")
        self.pending += attackers.schedule(self)
        self.pending.sort(key=lambda c: c.t)

    # --- construction helpers
    def _add(self, role, **kw):
        n = Node(len(self.nodes), role, list(self.dry_pos()), **kw)
        self.nodes.append(n)
        return n

    def claim(self, node, src, pos, t, urgency, need, kind, victim=None):
        return Claim(next(self._cid), node.id, src, pos, t, urgency, need, kind, victim)

    def flood_front(self, t):
        return self.scn.flood_x0 + self.scn.flood_rate * t

    def flooded(self, pos, t=None):
        return pos[0] < self.flood_front(self.t if t is None else t)

    def dry_pos(self):
        s = self.scn
        return (self.rng.uniform(self.flood_front(0) + 100, s.area), self.rng.uniform(0, s.area))

    # --- network view for policies
    def _linked(self, a, b):
        s = self.scn
        if math.dist(a.pos, b.pos) > s.radio_range:
            return False
        if s.partition and s.partition[0] <= self.t < s.partition[1]:
            return (a.pos[0] < s.area / 2) == (b.pos[0] < s.area / 2)
        return True

    def components(self):
        """Connected groups of live phones (lists of node ids)."""
        alive = [n for n in self.nodes if n.status != "dead"]
        parent = {n.id: n.id for n in alive}

        def find(i):
            while parent[i] != i:
                parent[i] = parent[parent[i]]
                i = parent[i]
            return i

        for i, a in enumerate(alive):
            for b in alive[i + 1:]:
                if self._linked(a, b):
                    parent[find(a.id)] = find(b.id)
        groups = {}
        for n in alive:
            groups.setdefault(find(n.id), []).append(n.id)
        return list(groups.values())

    # --- simulation step pieces
    def emit(self):
        out = []
        while self.pending and self.pending[0].t <= self.t:
            c = self.pending.pop(0)
            if self.nodes[c.node].status != "dead":
                self.claims.append(c)
                out.append(c)
        return out

    def dispatch(self, d):
        r, c = self.nodes[d.responder], d.claim
        if r.target or r.status != "ok":
            return
        crit = any(k.real and k.urgency > 0.7 and k.id not in self.dispatched
                   and self.nodes[k.victim].rescued is None and self.nodes[k.victim].died is None
                   for k in self.claims)
        misuse = r.skill == "medic" and c.real and c.need is None and crit
        self.dispatched.add(c.id)
        r.target = c
        self.dispatches.append(Rec(self.t, r.id, c, d.p_valid, misuse))

    def _arrive(self, n):
        c, n.target = n.target, None
        if not c.real:
            kind = "wasted"
        else:
            v = self.nodes[c.victim]
            if v.rescued is None and v.died is None:
                v.rescued, kind = self.t, "rescued"
            else:
                kind = "redundant"
        self.events.append((self.t, kind, n.id, c))

    def move(self):
        s, dt = self.scn, self.scn.tick
        for n in self.nodes:
            if n.status == "dead":
                continue
            if n.role == "responder":
                n.battery -= dt * (2e-5 + (1e-4 if n.target else 0))
                if n.battery <= 0:
                    n.status = "dead"
                    continue
                if n.target:
                    d = math.dist(n.pos, n.target.pos)
                    if d <= ARRIVE:
                        self._arrive(n)
                    else:
                        k = SPEED * dt / d
                        n.pos[0] += (n.target.pos[0] - n.pos[0]) * k
                        n.pos[1] += (n.target.pos[1] - n.pos[1]) * k
                        if self.flooded(n.pos):
                            self.events.append((self.t, "unsafe", n.id, n.target))
                            n.status, n.target = "lost", None
                elif n.status == "ok":   # idle wander, never into water
                    a = self.rng.uniform(0, 2 * math.pi)
                    x = n.pos[0] + 0.5 * dt * math.cos(a)
                    y = n.pos[1] + 0.5 * dt * math.sin(a)
                    n.pos = [min(max(x, self.flood_front(self.t) + 5), s.area), min(max(y, 0), s.area)]
            elif n.role == "victim" and n.rescued is None:
                if self.t >= n.deadline or self.flooded(n.pos):
                    n.died, n.status = self.t, "dead"
