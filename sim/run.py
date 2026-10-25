"""Usage: python -m sim.run [--runs 30]"""
import argparse
from dataclasses import replace
from math import sqrt
from statistics import mean, stdev

from .baseline import NaivePolicy
from .config import Scenario
from .metrics import reliability, summarize
from .world import World

SCENARIOS = {
    "clean": {},
    "decoy": dict(attack="decoy", n_attackers=3),
    "lure": dict(attack="lure", n_attackers=3),
    "sybil": dict(attack="sybil", n_attackers=3),
    "spoof": dict(attack="spoof", n_attackers=3),
    # scarce responders leave a claim backlog; both islands then pick the same backlog claim
    "scarce": dict(n_responders=10, n_victims=25),
    "scarce+split": dict(n_responders=10, n_victims=25, partition=(300.0, 2400.0)),
}
COLS = ["rescued", "died", "fake_dispatch", "attacker_success", "unsafe", "double_dispatch", "medic_misuse"]


def run(scn, policy_cls=NaivePolicy):
    w = World(scn)
    p = policy_cls(w)
    while w.t < scn.duration:
        for c in w.emit():
            p.observe(c)
        for d in p.tick(w):
            w.dispatch(d)
        w.move()
        w.t += scn.tick
    return summarize(w)


def monte_carlo(scn, policy_cls=NaivePolicy, runs=30):
    """Returns {metric: (mean, 95% CI half-width)} plus pooled calibration pairs."""
    rs = [run(replace(scn, seed=scn.seed + i), policy_cls) for i in range(runs)]
    out = {}
    for k in rs[0]:
        if k != "_pairs":
            xs = [r[k] for r in rs]
            out[k] = (mean(xs), 1.96 * stdev(xs) / sqrt(runs) if runs > 1 else 0.0)
    out["_pairs"] = [p for r in rs for p in r["_pairs"]]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=30)
    runs = ap.parse_args().runs
    print(f"{'scenario':<10}" + "".join(f"{c:>18}" for c in COLS))
    for name, kw in SCENARIOS.items():
        res = monte_carlo(Scenario(**kw), runs=runs)
        print(f"{name:<10}" + "".join(f"{res[c][0]:>10.2f} ±{res[c][1]:.2f}" for c in COLS))
    print("\ncalibration (clean, p_valid vs observed real):", reliability(res["_pairs"]))


if __name__ == "__main__":
    main()
