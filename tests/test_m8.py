import dataclasses
import random
import unittest

from core.crdt import LWWMap
from core.types import HLC, DispatchState as S
from m0_transport.node import build_sim_node
from m0_transport.radio_adapter import SimMedium
from m1_identity.keys import dev_fleet
from m8_merge import bridge
from m8_merge.event_log import Event, EventStore
from m8_merge.replica import Replica

# One shared registry: every replica signs with its own enrolled hardware key (no dev signer).
REG = dev_fleet(["a", "b", "c", "z", "a1", "a2", "b1", "b2"])


class Clock:
    def __init__(self):
        self.t = 1_000_000
    def __call__(self):
        return self.t


def rep(name, clk, lease=60_000):
    return Replica(name, REG, now_ms=clk, lease_ms=lease)


class TestCrdt(unittest.TestCase):
    def test_lww_converges_either_order(self):
        a, b = LWWMap(), LWWMap()
        a.set("k", 1, HLC(1, 0, "x")); b.set("k", 2, HLC(2, 0, "y"))
        a.merge(b); b.merge(a)
        self.assertEqual((a.get("k"), b.get("k")), (2, 2))
        a.delete("k", HLC(3, 0, "x")); b.merge(a)
        self.assertIsNone(b.get("k"))


class TestEventLog(unittest.TestCase):
    def mk(self, n=3):
        r = rep("a", Clock())
        return r, [r.open_claim(f"c{i}", "X", 0.5) for i in range(n)]

    def test_tamper_and_bad_signature_rejected(self):
        _, evs = self.mk(1)
        bad = dataclasses.replace(evs[0], data={"claim": "evil", "cell": "X", "urgency": 1})
        self.assertEqual(EventStore(REG).add(bad), [])
        forged = dataclasses.replace(evs[0], node="b")
        self.assertEqual(EventStore(REG).add(forged), [])

    def test_out_of_order_buffered_then_drained(self):
        _, evs = self.mk(3)
        s = EventStore(REG)
        self.assertEqual(s.add(evs[2]), [])
        self.assertEqual(s.add(evs[1]), [])
        self.assertEqual([e.seq for e in s.add(evs[0])], [0, 1, 2])

    def test_fork_detected(self):
        r1, r2 = rep("a", Clock()), rep("a", Clock())      # same identity, two histories
        e1 = r1.open_claim("c1", "X", 0.1)
        e2 = r2.open_claim("c2", "X", 0.9)
        s = EventStore(REG)
        s.add(e1); s.add(e2)
        self.assertIn("a", s.forks)


class TestConflictAndLeases(unittest.TestCase):
    def setUp(self):
        self.clk = Clock()
        self.a, self.b = rep("a", self.clk), rep("b", self.clk)

    def sync(self):
        self.a.ingest(self.b.events_for(self.a.summary()))
        self.b.ingest(self.a.events_for(self.b.summary()))

    def test_furthest_along_wins_and_door_not_cancelled(self):
        self.a.open_claim("c1", "X", 0.9); self.sync()
        self.clk.t += 1000
        da = self.a.dispatch("c1", "a"); self.a.set_state(da, S.EN_ROUTE)
        db = self.b.dispatch("c1", "b"); self.b.set_state(db, S.ARRIVED)
        self.sync()
        p = self.a.plan()
        self.assertEqual(p.winners["c1"], db)
        self.assertEqual(p.redirect, [da])
        self.b.set_state(db, S.ARRIVED); self.a.set_state(da, S.ARRIVED); self.sync()
        p = self.a.plan()
        self.assertEqual(p.redirect, [])              # both at the door: nobody cancelled
        self.assertEqual(p.winners["c1"], self.b.book.plan(self.clk.t).winners["c1"])

    def test_tie_break_is_deterministic_on_both_replicas(self):
        da, db = self.a.dispatch("c1", "a"), self.b.dispatch("c1", "b")
        self.sync()
        self.assertEqual(self.a.plan().winners, self.b.plan().winners)

    def test_spoofed_progress_ignored(self):
        da = self.a.dispatch("c1", "a")
        with self.assertRaises(ValueError):
            self.b.set_state(da, S.RESOLVED)           # local guard
        evil = self.b._emit("state", {"did": da, "state": int(S.RESOLVED)})   # bypass guard
        self.sync()
        self.assertEqual(self.a.book.recs[da].state, S.PROPOSED)

    def test_lease_orphan_reopens_and_heartbeat_keeps(self):
        d = self.a.dispatch("c1", "a"); self.sync()
        self.clk.t += 59_000
        self.assertEqual(self.b.plan().reopen, [])
        self.a.heartbeat(d); self.sync()
        self.clk.t += 59_000
        self.assertEqual(self.b.plan().reopen, [])     # heartbeat renewed the lease
        self.clk.t += 5_000
        self.assertEqual(self.b.plan().reopen, ["c1"])  # silent too long -> victim re-opens

    def test_resolved_claim_not_reopened(self):
        d = self.a.dispatch("c1", "a"); self.a.set_state(d, S.RESOLVED); self.sync()
        self.clk.t += 10 ** 7
        p = self.b.plan()
        self.assertEqual((p.reopen, p.winners), ([], {}))

    def test_state_never_moves_backward(self):
        d = self.a.dispatch("c1", "a")
        self.a.set_state(d, S.ARRIVED); self.a.set_state(d, S.ACCEPTED); self.sync()
        self.assertEqual(self.b.book.recs[d].state, S.ARRIVED)


class TestConvergence(unittest.TestCase):
    def test_any_delivery_order_same_book(self):
        clk = Clock()
        rs = [rep(n, clk) for n in "abc"]
        rs[0].open_claim("c1", "X", 0.9)
        dids = [rs[i].dispatch("c1", "abc"[i]) for i in range(3)]
        for i, d in enumerate(dids):
            clk.t += 10
            rs[i].set_state(d, S.EN_ROUTE if i else S.ARRIVED); rs[i].heartbeat(d)
        every = [e for r in rs for l in r.store.logs.values() for e in l]
        snaps = set()
        for seed in range(25):
            ev = every[:]; random.Random(seed).shuffle(ev)
            fresh = rep("z", clk)
            fresh.ingest(ev)                       # out-of-order buffering handles gaps
            snaps.add(repr(fresh.book.snapshot()))
        self.assertEqual(len(snaps), 1)


class TestWithM0Partition(unittest.TestCase):
    def test_islands_dispatch_independently_then_reconcile(self):
        clk, m = Clock(), SimMedium(rng=random.Random(1))
        names = ["a1", "a2", "b1", "b2"]
        nodes = {n: build_sim_node(n, m) for n in names}
        reps = {n: rep(n, clk, lease=120_000) for n in names}
        for n in names:
            bridge.attach(reps[n], nodes[n])
        for x, y in [("a1", "a2"), ("a2", "b1"), ("b1", "b2")]:
            m.link(x, y)

        def run(secs):
            for _ in range(secs):
                clk.t += 1000
                for n in nodes.values():
                    n.tick(clk.t / 1000)

        reps["a1"].open_claim("c1", "X", 0.9); run(10)
        self.assertIsNotNone(reps["b2"].book.claims.get("c1"))   # claim reached both islands

        m.unlink("a2", "b1")                                    # partition
        da = reps["a1"].dispatch("c1", "a1"); reps["a1"].set_state(da, S.EN_ROUTE)
        db = reps["b1"].dispatch("c1", "b2"); reps["b2"].set_state(db, S.ARRIVED)
        run(20)
        self.assertNotIn(db, reps["a1"].book.recs)             # islands really are blind
        self.assertNotIn(da, reps["b2"].book.recs)

        m.link("a2", "b1"); run(30)                             # heal
        plans = [reps[n].plan() for n in names]
        for p in plans:
            self.assertEqual(p.winners["c1"], db)               # one responder keeps the victim
            self.assertEqual(p.redirect, [da])                  # the other is redirected
        self.assertEqual(len({repr(reps[n].book.snapshot()) for n in names}), 1)


if __name__ == "__main__":
    unittest.main()
