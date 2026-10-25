"""Attack suite: each attack runs against the stack with the defense OFF and ON.
Usage: python -m m11_redteam.attack_suite   (exit code 1 if any defense fails)

attack_success is the fraction of attacker goals achieved (0 = fully defended).
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from typing import Callable, Dict, List

from m3_verify import challenge as ch
from m3_verify.p_valid import Verifier
from m4_hazard.flood_model import FloodModel
from m4_hazard.hazard_grid import HazardGrid
from m4_hazard.router import RoadGraph, find_route
from m5_trust.trust import TrustEngine
from m5_trust.vouch_graph import VouchGraph
from m6_battery import drain_model as dm

from .defenses import Dedupe, RateLimiter, SensorVeto, sign_credential, verify_credential

RELEASE = 0.6       # M3 release threshold used by the sim policy
ROOT = b"pinned-root-key"


@dataclass
class Claim:
    id: int
    node: int
    src: int
    pos: tuple
    t: float
    urgency: float = 1.0
    need: str = "medic"
    battery: float = 1.0


@dataclass
class Result:
    name: str
    undefended: float
    defended: float
    max_ok: float
    note: str = ""

    @property
    def ok(self) -> bool:
        return self.defended <= self.max_ok and self.undefended > self.defended


def _square():
    g = RoadGraph()
    for n, p in {"A": (0, 0), "B": (200, 0), "C": (0, 200), "D": (200, 200)}.items():
        g.add_node(n, p)
    for a, b in [("A", "B"), ("A", "C"), ("C", "D"), ("D", "B")]:
        g.add_edge(a, b)
    return g


def _underpass(p):
    return 8.0 if (50 < p[0] < 150 and p[1] < 30) else 100.0


def forged_credentials() -> Result:
    revoked = {(3, "medic")}
    creds = {1: sign_credential(ROOT, 1, "medic"), 2: "f" * 64, 3: sign_credential(ROOT, 3, "medic")}
    check = lambda c: verify_credential(ROOT, c.node, "medic", creds.get(c.node), revoked) \
        if c.node in creds else None
    claims = [Claim(i, n, n, (100.0 * n, 0.0), 0.0) for i, n in enumerate((2, 3))]   # forged, revoked
    control = Claim(9, 1, 1, (500.0, 500.0), 0.0)

    def success(v):
        return sum(v.p_valid(c) >= RELEASE for c in claims) / len(claims)
    off, on = success(Verifier()), success(Verifier(credential=check))
    assert Verifier(credential=check).p_valid(control) >= 0.9          # genuine medic still passes
    return Result("forged_credentials", off, on, 0.0, "forged + revoked medic credentials")


def sybil_swarm() -> Result:
    g = VouchGraph(seeds=["root", "root2"])
    honest = [f"h{i}" for i in range(1, 6)]
    g.vouch("root", "h1")
    for a, b in zip(honest, honest[1:]):
        g.vouch(a, b); g.vouch(b, a)
    for r, n in [("root", "h3"), ("root", "h5"), ("root2", "h2"), ("root2", "h4")]:
        g.vouch(r, n)
    ring = [f"s{i}" for i in range(40)]
    for a in ring:
        for b in ring:
            g.vouch(a, b)
    g.vouch("h1", ring[0])
    naive = sum(sum(1 for vs in g.out.values() if s in vs) >= 3 for s in ring) / len(ring)  # "3 vouches = trusted"
    eng = TrustEngine(g)
    strong = sum(eng.trust(s, 0.0) >= 0.5 for s in ring) / len(ring)
    swarm = [Claim(100 + j, 7, 10_000 + j, (800.0, 800.0), 5.0) for j in range(40)]
    v = Verifier()
    for c in swarm:
        v.observe(c)
    claims_ok = sum(v.p_valid(c) >= RELEASE for c in swarm) / len(swarm)
    naive_c = 1.0 if len({c.src for c in swarm}) >= 3 else 0.0         # "3 identities agree = corroborated"
    return Result("sybil_swarm", max(naive, naive_c), max(strong, claims_ok), 0.0,
                  "40 fake identities: vouch ring + same-place claims")


def packet_flood() -> Result:
    lim, dd = RateLimiter(rate=2.0, burst=10.0), Dedupe()
    n, accepted = 500, 0
    for i in range(n):
        now = i * 0.02                                              # 50 packets/s for 10 s
        payload = f"spam-{i % 5}".encode() if i % 2 else f"spam-{i}".encode()
        if lim.allow(("device", 66), now) and dd.first(payload, now):   # key = device, rotating ids ignored
            accepted += 1
    honest = sum(lim.allow(("device", 1), float(t)) for t in range(30)) / 30
    assert honest == 1.0
    return Result("packet_flood", 1.0, accepted / n, 0.10, f"{accepted}/{n} flood packets got through")


def lure_into_floodwater() -> Result:
    truth = FloodModel(rate=0.08)                                   # real water: underpass floods at ~100 s
    def lured(grid):
        r = find_route(_square(), "A", "B", grid.risk_fn(), t0=0.0, speed=1.0)
        if r is None or r.path != ["A", "B"]:
            return 0.0
        return 1.0 if truth.p_flooded(8.0, 100.0) > 0.99 else 0.0   # walker is under water mid-route
    naive = HazardGrid(min_safe_reporters=1, min_safe_weight=0.1)
    safe = HazardGrid(flood=FloodModel(rate=0.08), dem=_underpass)  # responder sensors fed the flood model
    for g in (naive, safe):
        for k in range(4):
            for x in (60, 110, 140):
                g.report(f"sybil{k}", (x, 10), "safe", 0.0, 1.0)
    veto = SensorVeto()
    assert veto.abort(local_water_m=0.3, risk_ahead=0.0) and not veto.abort(None, 0.1)
    return Result("lure_into_floodwater", lured(naive), lured(safe), 0.0, "fake 'route is safe' claims over flood")


def decoy_emergencies() -> Result:
    decoys = [Claim(i, 5, 5, (300.0 + 400 * i, 400.0), 30.0 * i) for i in range(5)]
    passive = Verifier()
    for c in decoys:
        passive.observe(c)
    off = sum(passive.p_valid(c) >= RELEASE for c in decoys) / len(decoys)
    probed, eng = Verifier(), TrustEngine()
    for c in decoys:
        probed.observe(c)
    known = {10, 11, 12}
    for c in decoys:
        chal = ch.make_challenge(c.id, c.node, c.t, f"nonce{c.id}")
        fake = ch.Response(chal.cid, c.node, 950.0, frozenset({91, 92}), c.t + 20)   # invented neighbours, wrong pressure
        probed.record_challenge(c.id, ch.check_response(chal, fake, c.t + 30, 1005.0, known))
        eng.rep.record("5", "failed_challenge", c.t)
    on = sum(probed.p_valid(c) >= RELEASE for c in decoys) / len(decoys)
    honest = Claim(50, 6, 6, (900.0, 900.0), 0.0)
    good = Verifier()
    chal = ch.make_challenge(50, 6, 0.0, "n")
    ok = ch.Response(chal.cid, 6, 1004.0, frozenset({10, 11}), 10.0)
    good.record_challenge(50, ch.check_response(chal, ok, 20.0, 1005.0, known))
    assert good.p_valid(honest) >= 0.9 and eng.trust("5", 150.0) < 0.2
    return Result("decoy_emergencies", off, on, 0.0, "passive M3 only vs challenge-response + reputation hit")


def hazard_poisoning() -> Result:
    def denied(weight: float, reporters: int = 10) -> float:
        grid = HazardGrid()
        for cell_pos in [(100, 0), (0, 100), (100, 200), (200, 100)]:
            for k in range(reporters):
                grid.report(f"p{k}", cell_pos, "hazard", 0.0, weight)
        return 1.0 if find_route(_square(), "A", "B", grid.risk_fn()) is None else 0.0
    g = HazardGrid()
    g.report("honest", (100, 0), "hazard", 0.0, 0.9)
    assert g.risk(g.cell_of((100, 0)), 0.0) > 0.5                   # real reports still count
    old = HazardGrid(hazard_half_life=300.0)
    old.report("p", (0, 0), "hazard", 0.0, 1.0)
    assert old.risk(old.cell_of((0, 0)), 3000.0) < 0.15             # poison expires
    return Result("hazard_poisoning", denied(1.0), denied(0.05), 0.0, "10 fake reporters per cell, weight = trust")


def battery_curve_fraud() -> Result:
    fake = [dm.Sample(t * 300.0, 1.0) for t in range(20)]           # claims 100 % for hours
    jump = [dm.Sample(0.0, 0.1), dm.Sample(600.0, 0.9)]
    off = 1.0
    on = sum(dm.battery_llr(c) >= 0 for c in (fake, jump)) / 2      # share of fraudulent curves accepted
    return Result("battery_curve_fraud", off, on, 0.0, "flat or rising battery curves")


ALL: List[Callable[[], Result]] = [forged_credentials, sybil_swarm, packet_flood, lure_into_floodwater,
                                   decoy_emergencies, hazard_poisoning, battery_curve_fraud]


def run_all() -> List[Result]:
    return [f() for f in ALL]


def main() -> int:
    rs = run_all()
    print(f"{'attack':<24}{'undefended':>11}{'defended':>10}  verdict  note")
    for r in rs:
        print(f"{r.name:<24}{r.undefended:>11.2f}{r.defended:>10.2f}  {'DEFENDED' if r.ok else 'FAILED  '} {r.note}")
    return 0 if all(r.ok for r in rs) else 1


if __name__ == "__main__":
    sys.exit(main())
