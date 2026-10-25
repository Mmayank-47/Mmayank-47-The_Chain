import random
import unittest

from core.bloom import BloomFilter
from core.types import HLC, Message, Priority
from m0_transport.dtn import CarryStore, Carried, plan_handoff, SprayPolicy
from m0_transport.ingress import IngressGuard
from m0_transport.node import build_sim_node
from m0_transport.priority_queue import PriorityOutbox
from m0_transport.radio_adapter import SimMedium
from m1_identity.keys import dev_fleet
from m1_identity.ratelimit import IdentityLimiter


def msg(i, prio=Priority.DISTRESS, origin="n0", cell=None, now=0.0, ttl=3600):
    return Message("t", prio, origin, {"i": i}, HLC(1, i, origin), dest_cell=cell,
                   created=now, ttl_s=ttl)


def run(nodes, t0, t1, dt=1.0, hook=None):
    t = t0
    while t < t1:
        if hook:
            hook(t)
        for n in nodes:
            n.tick(t)
        t += dt
    return t


class TestQueue(unittest.TestCase):
    def test_priority_order_dedupe_eviction(self):
        q = PriorityOutbox(capacity=3)
        q.push("c", Priority.CHATTER, "c")
        q.push("h", Priority.HAZARD, "h")
        q.push("d", Priority.DISTRESS, "d")
        self.assertFalse(q.push("d", Priority.DISTRESS, "dup"))
        self.assertFalse(q.push("c2", Priority.CHATTER, "c2"))   # full, not better than worst
        self.assertTrue(q.push("d2", Priority.DISTRESS, "d2"))   # evicts chatter
        self.assertEqual([q.pop() for _ in range(3)], ["d", "d2", "h"])
        self.assertIsNone(q.pop())


class TestDTN(unittest.TestCase):
    def test_store_expiry_dedupe_and_eviction(self):
        s = CarryStore(capacity=2)
        self.assertTrue(s.add(msg(1, Priority.CHATTER, ttl=10), 2, 0))
        self.assertFalse(s.add(msg(1, Priority.CHATTER, ttl=10), 2, 0))
        s.add(msg(2, Priority.HAZARD), None, 0)
        self.assertTrue(s.add(msg(3, Priority.DISTRESS), None, 0))   # evicts chatter
        self.assertEqual(len(s), 2)
        s.expire(100)
        self.assertEqual(len(s), 2)      # default ttl 3600: still alive
        s.expire(4000)
        self.assertEqual(len(s), 0)      # expired

    def test_spray_halving_and_wait_phase(self):
        c = Carried(msg(1, Priority.CHATTER, cell="X"), 4, 0)
        self.assertEqual(plan_handoff(c, "Y"), (True, 2, 2))
        c.tokens = 1
        self.assertEqual(plan_handoff(c, "Y"), (False, 0, 1))
        self.assertEqual(plan_handoff(c, "X"), (True, 1, 0))

    def test_token_clamp(self):
        self.assertEqual(SprayPolicy().clamp(Priority.CHATTER, 10_000), 4)

    def test_bloom(self):
        b = BloomFilter.for_capacity(100)
        for i in range(100):
            b.add(f"id{i}")
        self.assertTrue(all(f"id{i}" in b for i in range(100)))
        fp = sum(f"x{i}" in b for i in range(1000))
        self.assertLess(fp, 40)


class TestGossip(unittest.TestCase):
    def line(self, n, **medium_kw):
        m = SimMedium(rng=random.Random(3), **medium_kw)
        got = {}
        nodes = []
        for i in range(n):
            nid = f"n{i}"
            nodes.append(build_sim_node(nid, m, on_deliver=lambda ms, nid=nid: got.setdefault(nid, ms)))
        for i in range(n - 1):
            m.link(f"n{i}", f"n{i+1}")
        return m, nodes, got

    def test_multihop_distress(self):
        m, nodes, got = self.line(6)
        nodes[0].originate(msg(1), 0)
        run(nodes, 0, 30)
        self.assertIn("n5", got)

    def test_priority_under_tight_budget(self):
        m, nodes, got = self.line(2)
        nodes[0].engine.byte_budget = 1          # one frame per tick
        nodes[0].originate(msg(1, Priority.CHATTER), 0)
        nodes[0].originate(msg(2, Priority.DISTRESS), 0)
        order = []
        nodes[1].engine.on_deliver = lambda ms: order.append(ms.priority)
        run(nodes, 0, 12)
        self.assertEqual(order[0], Priority.DISTRESS)
        self.assertEqual(len(order), 2)

    def test_survives_loss(self):
        m, nodes, got = self.line(5, loss=0.4)
        nodes[0].originate(msg(1), 0)
        run(nodes, 0, 120)
        self.assertIn("n4", got)

    def test_low_battery_shrinks_fanout(self):
        e = build_sim_node("x", SimMedium()).engine
        self.assertEqual(e.fanout(10, 5, 1.0, Priority.HAZARD), 3)
        self.assertEqual(e.fanout(10, 5, 0.1, Priority.HAZARD), 1)
        self.assertEqual(e.fanout(10, 5, 0.1, Priority.DISTRESS), 2)
        self.assertEqual(e.fanout(10, 20, 1.0, Priority.HAZARD), 2)

    def test_malformed_frame_ignored(self):
        m, nodes, got = self.line(2)
        m.transmit("n0", "n1", b"\xff not json", 0)
        run(nodes, 0, 3)     # must not raise

    def test_mule_carries_across_partition(self):
        m = SimMedium(rng=random.Random(5))
        got = {}
        mk = lambda n: build_sim_node(n, m, on_deliver=lambda ms, n=n: got.setdefault(n, ms))
        a1, a2, mule, b1, b2 = (mk(n) for n in ["a1", "a2", "mule", "b1", "b2"])
        m.link("a1", "a2"); m.link("b1", "b2"); m.link("mule", "a1")
        nodes = [a1, a2, mule, b1, b2]
        a1.originate(msg(1, Priority.DISTRESS, origin="a1"), 0)
        t = run(nodes, 0, 15)
        self.assertIn("mule", got); self.assertIn("a2", got)
        self.assertNotIn("b1", got)                      # island B still cut off
        m.isolate("mule"); run(nodes, t, t + 10)         # mule walking
        self.assertNotIn("b1", got)
        m.link("mule", "b1")                             # arrives at island B
        run(nodes, t + 10, t + 30)
        self.assertIn("b1", got); self.assertIn("b2", got)


class TestCarryStoreQuotas(unittest.TestCase):
    def test_origin_quota_blocks_excess_and_cannot_exceed(self):
        s = CarryStore(capacity=512, max_per_origin=5, max_per_neighbour=10)
        for i in range(5):
            self.assertTrue(s.add(msg(i, Priority.DISTRESS, origin="attacker"), None, 0.0))
        self.assertEqual(s.origin_count("attacker"), 5)
        # 6th distress from same origin rejected
        self.assertFalse(s.add(msg(5, Priority.DISTRESS, origin="attacker"), None, 0.0))
        self.assertEqual(s.origin_count("attacker"), 5)
        self.assertEqual(len(s), 5)
        # honest node is still admitted
        self.assertTrue(s.add(msg(100, Priority.DISTRESS, origin="honest"), None, 0.0))
        self.assertEqual(s.origin_count("honest"), 1)
        self.assertEqual(len(s), 6)

    def test_origin_quota_evicts_lower_priority_from_same_origin(self):
        s = CarryStore(capacity=512, max_per_origin=3, max_per_neighbour=10)
        s.add(msg(1, Priority.CHATTER, origin="n0"), 2, 0.0)
        s.add(msg(2, Priority.HAZARD, origin="n0"), None, 0.0)
        s.add(msg(3, Priority.DISTRESS, origin="n0"), None, 0.0)
        self.assertEqual(s.origin_count("n0"), 3)
        # Adding higher-priority DISTRESS evicts CHATTER from same origin
        self.assertTrue(s.add(msg(4, Priority.DISTRESS, origin="n0"), None, 1.0))
        self.assertEqual(s.origin_count("n0"), 3)
        self.assertIsNone(s.get(msg(1, Priority.CHATTER, origin="n0").msg_id))
        self.assertIsNotNone(s.get(msg(4, Priority.DISTRESS, origin="n0").msg_id))

    def test_neighbour_quota_blocks_peer_exhaustion(self):
        s = CarryStore(capacity=512, max_per_origin=50, max_per_neighbour=4)
        for i in range(4):
            # different origins, but same neighbour peer
            self.assertTrue(s.add(msg(i, Priority.DISTRESS, origin=f"orig_{i}"), None, 0.0, from_id="bad_neighbour"))
        self.assertEqual(s.neighbour_count("bad_neighbour"), 4)
        # 5th from same neighbour rejected
        self.assertFalse(s.add(msg(99, Priority.DISTRESS, origin="orig_99"), None, 0.0, from_id="bad_neighbour"))
        self.assertEqual(s.neighbour_count("bad_neighbour"), 4)
        # Another neighbour can still add
        self.assertTrue(s.add(msg(100, Priority.DISTRESS, origin="orig_100"), None, 0.0, from_id="good_neighbour"))
        self.assertEqual(s.neighbour_count("good_neighbour"), 1)

    def test_removal_and_expiry_frees_quota(self):
        s = CarryStore(capacity=512, max_per_origin=2, max_per_neighbour=2)
        m1 = msg(1, Priority.DISTRESS, origin="n0", ttl=10)
        m2 = msg(2, Priority.DISTRESS, origin="n0", ttl=100)
        s.add(m1, None, 0.0, from_id="p1")
        s.add(m2, None, 0.0, from_id="p1")
        self.assertEqual(s.origin_count("n0"), 2)
        self.assertEqual(s.neighbour_count("p1"), 2)
        self.assertFalse(s.add(msg(3, Priority.DISTRESS, origin="n0"), None, 0.0, from_id="p1"))
        # Expire m1
        s.expire(20.0)
        self.assertEqual(s.origin_count("n0"), 1)
        self.assertEqual(s.neighbour_count("p1"), 1)
        # Now n0 can add again
        self.assertTrue(s.add(msg(3, Priority.DISTRESS, origin="n0"), None, 20.0, from_id="p1"))

    def test_bad_or_edge_case_inputs(self):
        s = CarryStore(capacity=10, max_per_origin=2, max_per_neighbour=2)
        # None from_id works
        self.assertTrue(s.add(msg(1, origin="x"), None, 0.0, from_id=None))
        self.assertEqual(s.neighbour_count(""), 0)
        # dedupe
        self.assertFalse(s.add(msg(1, origin="x"), None, 0.0, from_id=None))


class TestStrangerLane(unittest.TestCase):
    def test_stranger_distress_allowed_exactly_once(self):
        reg = dev_fleet(["enrolled_peer"])
        lim = IdentityLimiter(reg, base_rate=1.0, base_burst=2.0, stranger_burst=1.0)
        g = IngressGuard(limiter=lim, stranger_lane=True)

        m1 = Message("m9.case", Priority.DISTRESS, "stranger1", {"c": 1}, HLC(1, 1, "stranger1"), ttl_s=3600.0)
        m2 = Message("m9.case", Priority.DISTRESS, "stranger1", {"c": 2}, HLC(1, 2, "stranger1"), ttl_s=3600.0)

        # 1st distress passes
        self.assertIsNotNone(g.admit(m1, "stranger1", 0.0))
        # 2nd distress fails
        self.assertIsNone(g.admit(m2, "stranger1", 0.0))
        self.assertEqual(g.dropped["rate_limited"], 1)

    def test_stranger_non_distress_rejected(self):
        reg = dev_fleet(["enrolled_peer"])
        lim = IdentityLimiter(reg, stranger_burst=1.0)
        g = IngressGuard(limiter=lim, stranger_lane=True)

        # Non-distress (e.g. sealed or chatter or beat)
        m_dispatch = Message("m9.sealed", Priority.DISPATCH, "stranger1", {"b": "hex"}, HLC(1, 1, "stranger1"), ttl_s=3600.0)
        self.assertIsNone(g.admit(m_dispatch, "stranger1", 0.0))

    def test_enrolled_node_does_not_consume_stranger_lane(self):
        reg = dev_fleet(["peer"])
        lim = IdentityLimiter(reg, base_rate=0.0, base_burst=1.0, stranger_burst=1.0)
        g = IngressGuard(limiter=lim, stranger_lane=True)

        m1 = Message("m9.case", Priority.DISTRESS, "peer", {"c": 1}, HLC(1, 1, "peer"), ttl_s=3600.0)
        m2 = Message("m9.case", Priority.DISTRESS, "peer", {"c": 2}, HLC(1, 2, "peer"), ttl_s=3600.0)

        self.assertIsNotNone(g.admit(m1, "peer", 0.0))
        # enrolled budget exhausted; cannot fall back to stranger lane
        self.assertIsNone(g.admit(m2, "peer", 0.0))


if __name__ == "__main__":
    unittest.main()
