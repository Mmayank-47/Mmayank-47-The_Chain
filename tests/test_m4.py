import math
import random
import unittest

from m4_hazard.flood_model import FloodModel
from m4_hazard.hazard_grid import HazardGrid
from m4_hazard.location_filter import ParticleFilter
from m4_hazard.router import RoadGraph, find_route


def square_graph():
    g = RoadGraph()
    for n, p in {"A": (0, 0), "B": (200, 0), "C": (0, 200), "D": (200, 200)}.items():
        g.add_node(n, p)
    for a, b in [("A", "B"), ("A", "C"), ("C", "D"), ("D", "B")]:
        g.add_edge(a, b)
    return g


class TestLocation(unittest.TestCase):
    def test_radius_grows_without_fixes_and_shrinks_with_ranging(self):
        truth = (100.0, 100.0)
        pf = ParticleFilter(truth, 30.0, rng=random.Random(1))
        r0 = pf.estimate()[1]
        for _ in range(20):
            pf.predict(0.0, 0.0, 4.0)
        r1 = pf.estimate()[1]
        self.assertGreater(r1, r0)                       # dead-reckoning drift widens the cloud
        rng = random.Random(2)
        for anchor in [(160.0, 100.0), (100.0, 170.0), (30.0, 90.0), (150.0, 30.0)]:
            pf.update_range(anchor, math.dist(truth, anchor) + rng.gauss(0, 2), 5.0)
        mean, r2 = pf.estimate()
        self.assertLess(r2, r1 / 2)
        self.assertLess(math.dist(mean, truth), 25.0)

    def test_gps_fix_tightens(self):
        pf = ParticleFilter((0.0, 0.0), 50.0, rng=random.Random(3))
        r0 = pf.estimate()[1]
        pf.update_gps((5.0, -3.0), 10.0)
        self.assertLess(pf.estimate()[1], r0)

    def test_altitude_against_dem_breaks_ambiguity(self):
        dem = lambda p: p[0] * 0.1                      # slope in x
        pf = ParticleFilter((100.0, 0.0), 40.0, rng=random.Random(4))
        r0 = pf.estimate()[1]
        pf.update_altitude(dem, 10.0, 0.5)              # x ~ 100
        self.assertLess(pf.estimate()[1], r0)

    def test_deterministic_given_seed(self):
        a = ParticleFilter((0.0, 0.0), 10.0, rng=random.Random(9))
        b = ParticleFilter((0.0, 0.0), 10.0, rng=random.Random(9))
        for f in (a, b):
            f.predict(1.0, 0.0, 1.0)
            f.update_gps((1.0, 0.0), 5.0)
        self.assertEqual(a.estimate(), b.estimate())


class TestFlood(unittest.TestCase):
    def test_never_lowers_and_rate_is_steepest_seen(self):
        f = FloodModel(base_level=1.0)
        self.assertTrue(f.observe(2.0, 100.0))
        self.assertFalse(f.observe(1.5, 200.0))        # lower reading ignored
        self.assertTrue(f.observe(4.0, 300.0))
        self.assertAlmostEqual(f.rate, 2.0 / 200.0)
        self.assertGreaterEqual(f.level(500.0), f.level(300.0))
        self.assertEqual(f.level(0.0), f.level(0.0))
        self.assertLessEqual(f.level(50.0), f.level(60.0))

    def test_p_flooded_monotone_in_time_and_elevation(self):
        f = FloodModel(base_level=1.0, rate=0.01)
        self.assertEqual(f.p_flooded(0.5, 0.0), 1.0)
        self.assertLess(f.p_flooded(5.0, 0.0), f.p_flooded(5.0, 400.0))
        self.assertLess(f.p_flooded(8.0, 0.0), f.p_flooded(5.0, 0.0))


class TestGrid(unittest.TestCase):
    def test_hazard_accepted_on_weak_evidence_safe_is_not(self):
        g = HazardGrid()
        c = g.cell_of((100, 0))
        base = g.risk(c, 0.0)
        g.report("a", (100, 0), "hazard", 0.0, 0.3)
        self.assertGreater(g.risk(c, 0.0), base + 0.1)
        h = HazardGrid()
        h.report("a", (100, 0), "safe", 0.0, 1.0)         # one loud voice
        self.assertEqual(h.risk(c, 0.0), base)
        h2 = HazardGrid()
        h2.report("a", (100, 0), "safe", 0.0, 0.4)        # two reporters, too little weight
        h2.report("a2", (100, 0), "safe", 0.0, 0.4)
        self.assertEqual(h2.risk(c, 0.0), base)
        h.report("a2", (100, 0), "safe", 0.0, 0.1)
        self.assertLess(h.risk(c, 0.0), base)             # two reporters, enough weight: corroborated

    def test_newer_hazard_voids_safe(self):
        g = HazardGrid()
        c = g.cell_of((0, 0))
        for r in "ab":
            g.report(r, (0, 0), "safe", 10.0, 1.0)
        low = g.risk(c, 20.0)
        g.report("c", (0, 0), "hazard", 15.0, 0.6)
        self.assertGreater(g.risk(c, 20.0), low + 0.3)

    def test_one_reporter_cannot_stack(self):
        g = HazardGrid()
        c = g.cell_of((0, 0))
        for t in range(50):
            g.report("spam", (0, 0), "hazard", float(t), 0.3)
        one = HazardGrid()
        one.report("spam", (0, 0), "hazard", 49.0, 0.3)
        self.assertAlmostEqual(g.risk(c, 49.0), one.risk(c, 49.0))

    def test_hazards_expire(self):
        g = HazardGrid(hazard_half_life=100.0)
        c = g.cell_of((0, 0))
        g.report("a", (0, 0), "hazard", 0.0, 1.0)
        self.assertGreater(g.risk(c, 0.0), 0.8)
        self.assertAlmostEqual(g.risk(c, 5000.0), g.prior)

    def test_merge_converges_any_order(self):
        reps = [("a", "hazard", 5.0, 0.5), ("b", "safe", 6.0, 0.9), ("a", "safe", 9.0, 0.7), ("c", "hazard", 2.0, 0.4)]
        x, y = HazardGrid(), HazardGrid()
        for i, r in enumerate(reps):
            (x if i % 2 else y).report(r[0], (10, 10), r[1], r[2], r[3])
        x2, y2 = HazardGrid(), HazardGrid()
        x2.merge(x); x2.merge(y); y2.merge(y); y2.merge(x)
        c = x2.cell_of((10, 10))
        self.assertEqual(x2.risk(c, 30.0), y2.risk(c, 30.0))
        self.assertEqual(x2._r, y2._r)

    def test_safe_claim_cannot_override_flood_physics(self):
        fm = FloodModel(base_level=5.0)
        g = HazardGrid(flood=fm, dem=lambda p: 1.0)       # ground at 1 m, water at 5 m
        c = g.cell_of((0, 0))
        for r in "abcd":
            g.report(r, (0, 0), "safe", 0.0, 1.0)
        self.assertGreater(g.risk(c, 0.0), 0.99)          # the lure: "safe" claims in floodwater


class TestRouter(unittest.TestCase):
    def test_direct_when_clear(self):
        r = find_route(square_graph(), "A", "B", HazardGrid().risk_fn())
        self.assertEqual(r.path, ["A", "B"])
        self.assertAlmostEqual(r.length, 200.0)
        self.assertGreater(r.p_safe, 0.8)

    def test_moderate_hazard_reroutes_via_convex_cost(self):
        g, grid = square_graph(), HazardGrid()
        grid.report("a", (100, 0), "hazard", 0.0, 0.5)
        r = find_route(g, "A", "B", grid.risk_fn(), t0=0.0)
        self.assertEqual(r.path, ["A", "C", "D", "B"])
        self.assertGreater(r.p_safe, 0.6)

    def test_strong_hazard_blocks_edge_and_no_route_returns_none(self):
        g, grid = square_graph(), HazardGrid()
        grid.report("a", (100, 0), "hazard", 0.0, 1.0)
        self.assertEqual(find_route(g, "A", "B", grid.risk_fn()).path, ["A", "C", "D", "B"])
        grid.report("b", (0, 100), "hazard", 0.0, 1.0)
        self.assertIsNone(find_route(g, "A", "B", grid.risk_fn()))

    def test_flood_rising_during_walk_is_seen(self):
        low_underpass = lambda p: 8.0 if (50 < p[0] < 150 and p[1] < 30) else 100.0
        fm = FloodModel(base_level=0.0, rate=0.08)       # 8 cm/s: the underpass is under by t=100 s
        grid = HazardGrid(flood=fm, dem=low_underpass)
        # dry when the walk starts, but the walker reaches the underpass after it has flooded
        r = find_route(square_graph(), "A", "B", grid.risk_fn(), t0=0.0, speed=1.0)
        self.assertEqual(r.path, ["A", "C", "D", "B"])
        fast = find_route(square_graph(), "A", "B", grid.risk_fn(), t0=0.0, speed=10.0)
        self.assertEqual(fast.path, ["A", "B"])          # a fast runner beats the water


if __name__ == "__main__":
    unittest.main()
