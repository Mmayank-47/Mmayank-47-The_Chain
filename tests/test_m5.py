import unittest

from m5_trust.beta_rep import BetaRep
from m5_trust.collusion import discounts, find_clusters
from m5_trust.trust import TrustEngine
from m5_trust.vouch_graph import VouchGraph


def community(g, names, root):
    g.vouch(root, names[0])
    for a, b in zip(names, names[1:]):
        g.vouch(a, b)
        g.vouch(b, a)


def sybil_ring(g, k=10, attack_from="h1"):
    ring = [f"s{i}" for i in range(k)]
    for a in ring:
        for b in ring:
            g.vouch(a, b)
    g.vouch(attack_from, ring[0])              # one attack edge
    return ring


def world():
    g = VouchGraph(seeds=["root", "root2"])
    honest = [f"h{i}" for i in range(1, 6)]
    community(g, honest, "root")
    g.vouch("h1", "h3"); g.vouch("h2", "h5")
    for r, n in [("root", "h3"), ("root", "h5"), ("root2", "h2"), ("root2", "h4")]:
        g.vouch(r, n)                          # honest groups are vouched from several roots
    return g, honest, sybil_ring(g)


class TestBeta(unittest.TestCase):
    def test_cold_start_and_updates(self):
        r = BetaRep()
        self.assertEqual(r.mean("x", 0), 0.5)
        r.record("x", "verified_arrival", 0)
        self.assertGreater(r.mean("x", 0), 0.5)

    def test_failure_outweighs_success(self):
        r = BetaRep()
        r.record("x", "verified_arrival", 0)
        r.record("x", "failed_claim", 0)
        self.assertLess(r.mean("x", 0), 0.5)

    def test_decay_returns_to_prior(self):
        r = BetaRep(half_life_s=100)
        for _ in range(5):
            r.record("x", "verified_arrival", 0)
        hi, later = r.mean("x", 0), r.mean("x", 10_000)
        self.assertGreater(hi, later)
        self.assertAlmostEqual(later, 0.5, places=3)
        self.assertLess(r.evidence("x", 10_000), 0.01)

    def test_weight_capped_and_unknown_event(self):
        r = BetaRep()
        r.record("x", "verified_arrival", 0, weight=10 ** 9)
        self.assertLessEqual(r.evidence("x", 0), 3.0)
        with self.assertRaises(KeyError):
            r.record("x", "made_up", 0)

    def test_uncertainty_shrinks_with_evidence(self):
        r = BetaRep()
        s0 = r.std("x", 0)
        for _ in range(10):
            r.record("x", "verified_arrival", 0)
        self.assertLess(r.std("x", 0), s0)


class TestVouch(unittest.TestCase):
    def test_rank_sums_to_one_and_deterministic(self):
        g, _, _ = world()
        self.assertAlmostEqual(sum(g.rank().values()), 1.0, places=9)
        self.assertEqual(g.rank(), world()[0].rank())

    def test_roots_full_and_sybil_ring_far_below_honest(self):
        g, honest, ring = world()
        sc = g.scores()
        self.assertEqual(sc["root"], 1.0)
        h = sum(sc[n] for n in honest) / len(honest)
        s = sum(sc[n] for n in ring) / len(ring)
        self.assertGreater(h, 0.5)
        self.assertLess(s, h / 3)

    def test_ring_with_no_attack_edge_gets_nothing(self):
        g = VouchGraph(seeds=["root"])
        g.vouch("root", "a")
        for x in "pqr":
            for y in "pqr":
                g.vouch(x, y)
        self.assertEqual(g.score("p"), 0.0)

    def test_vouching_for_many_dilutes(self):
        a, b = VouchGraph(seeds=["r"]), VouchGraph(seeds=["r"])
        a.vouch("r", "x")
        b.vouch("r", "x")
        for i in range(5):
            b.vouch("r", f"y{i}")
        self.assertLess(b.score("x"), a.score("x"))

    def test_self_vouch_ignored_and_stranger_default(self):
        g = VouchGraph(seeds=["r"])
        g.vouch("z", "z")
        self.assertEqual(g.score("z"), 0.5)

    def test_no_seeds_no_trust(self):
        g = VouchGraph()
        g.vouch("a", "b")
        self.assertEqual(g.score("b"), 0.0)


class TestCollusion(unittest.TestCase):
    def test_ring_flagged_honest_not(self):
        g, honest, ring = world()
        flagged = find_clusters(g)
        self.assertEqual(flagged, [set(ring)])
        d = discounts(g)
        self.assertTrue(all(n in d for n in ring))
        self.assertFalse(any(n in d for n in honest + ["root"]))

    def test_seed_inside_cluster_protects_it(self):
        g = VouchGraph(seeds=["a"])
        for x in "abc":
            for y in "abc":
                g.vouch(x, y)
        self.assertEqual(find_clusters(g), [])


class TestEngine(unittest.TestCase):
    def test_sybil_below_stranger_below_honest(self):
        g, honest, ring = world()
        e = TrustEngine(g)
        self.assertLess(e.trust("s3", 0), 0.1)
        self.assertEqual(e.trust("nobody", 0), 0.5)
        self.assertGreater(e.trust("h2", 0), 0.5)

    def test_bad_behaviour_drops_trusted_node(self):
        g, _, _ = world()
        e = TrustEngine(g)
        before = e.trust("h2", 0)
        for _ in range(3):
            e.rep.record("h2", "failed_claim", 0)
        self.assertLess(e.trust("h2", 0), before / 2)

    def test_good_behaviour_lifts_stranger(self):
        e = TrustEngine(VouchGraph(seeds=["r"]))
        for _ in range(6):
            e.rep.record("new", "verified_arrival", 0)
        self.assertGreater(e.trust("new", 0), 0.7)

    def test_llr_sign_and_bounds(self):
        g, _, _ = world()
        e = TrustEngine(g)
        self.assertGreater(e.llr("h2", 0), 0)
        self.assertLess(e.llr("s3", 0), 0)
        self.assertAlmostEqual(e.llr("nobody", 0), 0.0)
        self.assertLessEqual(abs(e.llr("root", 0)), 3.0)


if __name__ == "__main__":
    unittest.main()
