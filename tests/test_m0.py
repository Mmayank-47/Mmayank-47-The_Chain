import random
import unittest

from core.bloom import BloomFilter
from core.types import HLC, Message, Priority
from m0_transport.dtn import CarryStore, Carried, plan_handoff, SprayPolicy
from m0_transport.node import build_sim_node
from m0_transport.priority_queue import PriorityOutbox
from m0_transport.radio_adapter import SimMedium


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


if __name__ == "__main__":
    unittest.main()
