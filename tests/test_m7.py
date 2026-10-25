import itertools
import math
import random
import unittest

from m7_matching import escalation as esc
from m7_matching.cbba import Bidder, gossip
from m7_matching.matcher import build_utilities, ranking
from m7_matching.opportunity_cost import opp_cost, prices
from m7_matching.triage import Category, Features, classify, scores
from m7_matching.utility import Claim, Responder, gate, pair_utility

MEDIC = frozenset({"medic"})


def claim(id, urgency=0.5, need=None, p=0.9, gain=0.5, pos=(0.0, 0.0), trust=0.8):
    return Claim(id, pos, p, urgency, gain, need, trust)


def resp(id, skills=frozenset(), pos=(0.0, 0.0)):
    return Responder(id, pos, skills)


def straight(r, c):
    return math.dist(r.pos, c.pos), 0.95


def reach(r, length):
    return 0.97


class TestTriage(unittest.TestCase):
    def test_start_rules(self):
        self.assertEqual(classify(Features(can_walk=True)), Category.MINOR)
        self.assertEqual(classify(Features(can_walk=False, breathing=False)), Category.EXPECTANT)
        self.assertEqual(classify(Features(can_walk=False, breathing=True, resp_rate=34)), Category.IMMEDIATE)
        self.assertEqual(classify(Features(can_walk=False, breathing=True, resp_rate=20, perfusion_ok=False)),
                         Category.IMMEDIATE)
        self.assertEqual(classify(Features(False, True, 20, True, True)), Category.DELAYED)
        self.assertEqual(classify(Features(False, None, None, None, None)), Category.UNKNOWN)

    def test_expectant_deprioritised_not_zero(self):
        _, u, g = scores(Features(can_walk=False, breathing=False))
        self.assertGreater(u, 0)
        self.assertLess(g, 0.1)


class TestUtilityAndGate(unittest.TestCase):
    def test_skill_gate_and_monotonicity(self):
        c = claim("c", 1.0, need="medic")
        self.assertEqual(pair_utility(c, resp("a"), 100, 0.95, 0.97, 0.0), -math.inf)
        near = pair_utility(c, resp("m", MEDIC), 100, 0.95, 0.97, 0.0)
        far = pair_utility(c, resp("m", MEDIC), 3000, 0.95, 0.97, 0.0)
        risky = pair_utility(c, resp("m", MEDIC), 100, 0.5, 0.97, 0.0)
        self.assertGreater(near, far)
        self.assertGreater(near, risky)

    def test_gate_threshold_rises_with_danger(self):
        self.assertEqual(gate(0.9, 0.95, 0.8, 0.97), "dispatch")
        self.assertEqual(gate(0.9, 0.95, 0.5, 0.97), "dispatch")        # stranger, plausible claim
        self.assertEqual(gate(0.9, 0.35, 0.6, 0.97), "scout")           # dangerous route
        self.assertEqual(gate(0.4, 0.95, 0.8, 0.97), "probe")           # shaky claim
        self.assertEqual(gate(0.3, 0.9, 0.2, 0.97), "probe")
        self.assertEqual(gate(0.65, 0.95, 0.45, 0.4), "hold")           # low charge, nothing wrong with claim


class TestOpportunityCost(unittest.TestCase):
    def test_medic_price_depends_on_scarcity(self):
        crit = claim("k", 1.0, need="medic", p=0.8)
        one = prices([resp("m", MEDIC)], [crit])["medic"]
        many = prices([resp(f"m{i}", MEDIC) for i in range(5)], [crit])["medic"]
        self.assertGreater(one, many)
        self.assertEqual(prices([resp("m", MEDIC)], [])["medic"], 0.0)

    def test_using_the_needed_skill_is_free(self):
        m, crit, minor = resp("m", MEDIC), claim("k", 1.0, need="medic"), claim("n", 0.15)
        pr = {"medic": 0.5}
        self.assertEqual(opp_cost(m, crit, pr), 0.0)
        self.assertEqual(opp_cost(m, minor, pr), 0.5)
        self.assertEqual(opp_cost(resp("a"), minor, pr), 0.0)

    def test_only_medic_not_sent_to_minor_case(self):
        minor = claim("minor", urgency=0.15, gain=0.2)
        rs = [resp("medic", MEDIC), resp("walker")]
        # a critical case is plausible but still unverified: expected arrivals keep the medic's price up
        safe = lambda r, c: (math.dist(r.pos, c.pos), 0.999)             # minor cases tolerate almost no risk
        util, _ = build_utilities([minor], rs, safe, reach, extra_demand={"medic": 0.7})
        self.assertNotIn("minor", util["medic"])
        self.assertIn("minor", util["walker"])
        # with nothing else expected, the medic is free to help
        util2, _ = build_utilities([minor], rs, safe, reach)
        self.assertIn("minor", util2["medic"])


class TestMatcher(unittest.TestCase):
    def test_decisions_recorded_and_unroutable_skipped(self):
        c = claim("c", 1.0)
        util, dec = build_utilities([c], [resp("a")], lambda r, c: None, reach)
        self.assertEqual(dec[("a", "c")], "no_route")
        self.assertEqual(util["a"], {})

    def test_gate_blocks_unverified_claim(self):
        c = claim("c", 1.0, p=0.2)
        util, dec = build_utilities([c], [resp("a")], straight, reach)
        self.assertEqual(dec[("a", "c")], "probe")
        self.assertEqual(util["a"], {})

    def test_ranking_deterministic_and_nearest_first(self):
        c = claim("c", 1.0)
        rs = [resp("far", pos=(900.0, 0.0)), resp("near", pos=(50.0, 0.0)), resp("mid", pos=(300.0, 0.0))]
        util, _ = build_utilities([c], rs, straight, reach)
        self.assertEqual(ranking(util, "c"), ["near", "mid", "far"])


class TestCBBA(unittest.TestCase):
    def fleet(self, util):
        return {a: Bidder(a, u) for a, u in util.items()}

    def check_conflict_free(self, bs):
        views = [b.assignment() for b in bs.values()]
        self.assertTrue(all(v == views[0] for v in views), views)
        mine = [b.my_task() for b in bs.values() if b.my_task()]
        self.assertEqual(len(mine), len(set(mine)))

    def test_conflict_free_and_agreed_in_connected_group(self):
        util = {"a": {"x": 0.9, "y": 0.5}, "b": {"x": 0.8, "y": 0.6}, "c": {"x": 0.4, "y": 0.3}}
        bs = self.fleet(util)
        gossip(bs, [("a", "b"), ("b", "c")])
        self.check_conflict_free(bs)
        self.assertEqual(bs["a"].assignment(), {"x": "a", "y": "b"})

    def test_agents_with_no_positive_utility_stay_idle(self):
        bs = self.fleet({"a": {"x": 0.5}, "b": {}})
        gossip(bs, [("a", "b")])
        self.assertIsNone(bs["b"].my_task())

    def test_tie_broken_by_agent_id(self):
        bs = self.fleet({"b": {"x": 0.5}, "a": {"x": 0.5}})
        gossip(bs, [("a", "b")])
        self.assertEqual(bs["b"].assignment(), {"x": "a"})

    def test_random_instances_converge_conflict_free(self):
        rng = random.Random(7)
        for _ in range(40):
            n, m = rng.randint(2, 8), rng.randint(1, 8)
            util = {f"r{i}": {f"t{j}": rng.uniform(-0.2, 1) for j in range(m)} for i in range(n)}
            bs = self.fleet(util)
            ids = list(bs)
            links = [(a, b) for a, b in itertools.combinations(ids, 2) if rng.random() < 0.6]
            links += [(ids[i], ids[i + 1]) for i in range(n - 1)]          # keep it connected
            gossip(bs, links)
            self.check_conflict_free(bs)
            view = bs[ids[0]].assignment()
            for a, u in util.items():                                        # stable: no idle agent could win a task
                if bs[a].my_task() is None:
                    for t, v in u.items():
                        if v > 0:
                            self.assertIn(t, view)
                            self.assertGreater(bs[view[t]].u[t] + 1e-9, v)

    def test_partition_then_merge_resolves_duplicate_claims(self):
        util = {"a1": {"x": 0.7, "y": 0.2}, "a2": {"x": 0.3}, "b1": {"x": 0.9, "y": 0.4}, "b2": {"x": 0.1}}
        bs = self.fleet(util)
        gossip(bs, [("a1", "a2"), ("b1", "b2")])                             # islands
        self.assertEqual(bs["a1"].my_task(), "x")
        self.assertEqual(bs["b1"].my_task(), "x")                            # both took x: double dispatch
        gossip(bs, [("a1", "a2"), ("b1", "b2"), ("a2", "b1")])               # heal
        self.check_conflict_free(bs)
        self.assertEqual(bs["a1"].assignment()["x"], "b1")                   # higher bid keeps it
        self.assertEqual(bs["a1"].my_task(), "y")                            # loser re-bids elsewhere

    def test_forget_frees_task_of_vanished_agent(self):
        bs = self.fleet({"a": {"x": 0.9}, "b": {"x": 0.5}})
        gossip(bs, [("a", "b")])
        self.assertEqual(bs["b"].my_task(), None)
        bs["b"].forget("a")
        gossip(bs, [])
        self.assertEqual(bs["b"].my_task(), "x")

    def test_utility_change_triggers_rebid(self):
        bs = self.fleet({"a": {"x": 0.9, "y": 0.8}, "b": {"x": 0.5, "y": 0.4}})
        gossip(bs, [("a", "b")])
        bs["a"].set_utilities({"x": -1.0, "y": 0.8})                        # route to x flooded
        gossip(bs, [("a", "b")])
        self.assertEqual(bs["a"].assignment(), {"y": "a", "x": "b"})


class TestEscalation(unittest.TestCase):
    def test_timeout_escalates_then_exhausts(self):
        e = esc.Escalation("c", ["a", "b"], now=0.0, ack_timeout=100.0)
        self.assertEqual(e.current, "a")
        self.assertIsNone(e.tick(99.0))
        self.assertEqual(e.tick(100.0), "b")
        self.assertIsNone(e.tick(250.0))
        self.assertEqual(e.state, esc.EXHAUSTED)
        self.assertIsNone(e.current)

    def test_ack_stops_escalation(self):
        e = esc.Escalation("c", ["a", "b"], 0.0)
        self.assertTrue(e.ack("a", 10.0))
        self.assertIsNone(e.tick(10_000.0))
        self.assertEqual(e.current, "a")

    def test_decline_and_wrong_responder(self):
        e = esc.Escalation("c", ["a", "b", "c"], 0.0)
        self.assertEqual(e.decline("b", 5.0), "a")        # not the prompted one: ignored
        self.assertEqual(e.decline("a", 5.0), "b")

    def test_referral_goes_next_and_dedupes(self):
        e = esc.Escalation("c", ["a", "b", "z"], 0.0)
        self.assertEqual(e.refer("a", "z", 5.0), "z")
        self.assertEqual(e.ranked, ["a", "z", "b"])

    def test_empty_ranking_is_exhausted(self):
        self.assertEqual(esc.Escalation("c", [], 0.0).state, esc.EXHAUSTED)


if __name__ == "__main__":
    unittest.main()
