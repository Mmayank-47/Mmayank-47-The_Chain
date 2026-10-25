"""Run: python -m unittest tests.test_sim   (or `make test`)"""
import unittest

from sim.config import Scenario
from sim.run import calibration, monte_carlo, run
from sim.world import World


class TestSim(unittest.TestCase):
    def test_deterministic(self):
        a, b = run(Scenario(seed=3)), run(Scenario(seed=3))
        self.assertEqual(a, b)

    def test_flood_only_rises(self):
        w = World(Scenario())
        self.assertTrue(all(w.flood_front(t) <= w.flood_front(t + 1) for t in range(0, 3600, 50)))

    def test_clean_run_rescues_some(self):
        self.assertGreater(monte_carlo(Scenario(), runs=10)["rescued"][0], 0.3)

    def test_decoys_fool_baseline(self):
        r = monte_carlo(Scenario(attack="decoy", n_attackers=3), runs=10)
        self.assertGreater(r["attacker_success"][0], 0.5)
        self.assertGreater(r["fake_dispatch"][0], 0)

    def test_partition_causes_double_dispatch(self):
        base = dict(n_responders=10, n_victims=25)
        clean = monte_carlo(Scenario(**base), runs=20)["double_dispatch"][0]
        part = monte_carlo(Scenario(partition=(300.0, 2400.0), **base), runs=20)["double_dispatch"][0]
        self.assertGreater(part, clean)

    def test_calibration_uses_the_named_scenario_not_the_last_one(self):
        results = {"clean": {"_pairs": [(1.0, True), (1.0, True)]},
                   "scarce+split": {"_pairs": [(0.1, False), (0.1, False)]}}   # last in dict order
        self.assertEqual(calibration(results, "clean"), [(1.0, 1.0, 2)])
        self.assertEqual(calibration(results, "scarce+split"), [(0.1, 0.0, 2)])


if __name__ == "__main__":
    unittest.main()
