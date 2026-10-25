"""Needs the M12 sim/ package. Run: python -m unittest tests.test_m3_sim"""
import unittest

from m3_verify.policy import VerifiedPolicy
from sim.config import Scenario
from sim.run import monte_carlo, run


class TestM3InSim(unittest.TestCase):
    def test_clean_rescue_not_hurt(self):
        s = Scenario(seed=1)
        self.assertEqual(run(s, VerifiedPolicy)["rescued"], run(s)["rescued"])

    def test_spoof_and_sybil_blocked(self):
        for atk in ("spoof", "sybil"):
            r = monte_carlo(Scenario(attack=atk, n_attackers=3), VerifiedPolicy, runs=8)
            self.assertEqual(r["fake_dispatch"][0], 0.0, atk)

    def test_decoy_and_lure_mostly_blocked(self):
        for atk in ("decoy", "lure"):
            r = monte_carlo(Scenario(attack=atk, n_attackers=3), VerifiedPolicy, runs=8)
            self.assertLess(r["attacker_success"][0], 0.5, atk)


if __name__ == "__main__":
    unittest.main()
