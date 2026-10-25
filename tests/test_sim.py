"""Run: python -m tests.test_sim"""
from dataclasses import replace

from sim.config import Scenario
from sim.run import monte_carlo, run
from sim.world import World


def test_deterministic():
    a, b = run(Scenario(seed=3)), run(Scenario(seed=3))
    assert a == b


def test_flood_only_rises():
    w = World(Scenario())
    assert all(w.flood_front(t) <= w.flood_front(t + 1) for t in range(0, 3600, 50))


def test_clean_run_rescues_some():
    assert monte_carlo(Scenario(), runs=10)["rescued"][0] > 0.3


def test_decoys_fool_baseline():
    r = monte_carlo(Scenario(attack="decoy", n_attackers=3), runs=10)
    assert r["attacker_success"][0] > 0.5 and r["fake_dispatch"][0] > 0


def test_partition_causes_double_dispatch():
    base = dict(n_responders=10, n_victims=25)
    clean = monte_carlo(Scenario(**base), runs=20)["double_dispatch"][0]
    part = monte_carlo(Scenario(partition=(300.0, 2400.0), **base), runs=20)["double_dispatch"][0]
    assert part > clean


if __name__ == "__main__":
    for k, f in list(globals().items()):
        if k.startswith("test_"):
            f()
            print("ok", k)
