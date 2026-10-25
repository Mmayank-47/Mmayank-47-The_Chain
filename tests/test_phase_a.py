"""Phase A hardening: shared crypto provider, M8 on the M1 registry, HLC drift bound, M0 ingress guard,
measured M11 baselines."""
import dataclasses
import hashlib
import hmac
import json
import os
import random
import unittest
from unittest import mock

from core import crypto
from core.bloom import BloomFilter
from core.types import HLC, HLClock, ClockDrift, Message, Priority
from m0_transport.dtn import SprayPolicy
from m0_transport.ingress import IngressGuard, IngressLimits
from m0_transport.node import build_sim_node
from m0_transport.radio_adapter import SimMedium
from m1_identity.keys import dev_fleet
from m1_identity.ratelimit import IdentityLimiter
from m8_merge import bridge
from m8_merge.event_log import EventStore
from m8_merge.replica import Replica
from m9_privacy.sealed_envelope import BoxKey, seal


# ---------------------------------------------------------------- A4: one crypto provider
class TestCryptoProvider(unittest.TestCase):
    def test_registry_and_boxkey_satisfy_the_shared_protocols(self):
        self.assertIsInstance(dev_fleet(["a"]), crypto.Signer)
        self.assertIsInstance(BoxKey(), crypto.Box)
        self.assertIsInstance(crypto.get_provider(), crypto.Provider)

    def test_dev_provider_is_byte_compatible_with_plain_hmac(self):
        self.assertEqual(crypto.mac(b"k", b"d"), hmac.new(b"k", b"d", hashlib.sha256).hexdigest())

    def test_mac_ok_rejects_non_strings_and_non_ascii(self):
        good = crypto.mac(b"k", b"d")
        self.assertTrue(crypto.mac_ok(b"k", b"d", good))
        for bad in (None, 5, b"x", "é" * 64, good[:-1]):
            self.assertFalse(crypto.mac_ok(b"k", b"d", bad))

    def test_swapping_the_provider_changes_m1_and_m9_together(self):
        class Other:
            name = "other"
            def mac_bytes(self, key, data):
                return hmac.new(key, b"other|" + data, hashlib.sha256).digest()
            def rand(self, n):
                return os.urandom(n)

        reg, box = dev_fleet(["a"]), BoxKey()
        sig_old = reg.sign("a", b"x")
        blob_old = seal(box.public(), "s", {"x": 1})
        prev = crypto.set_provider(Other())
        try:
            self.assertFalse(reg.verify("a", b"x", sig_old))            # M1 goes through the provider
            self.assertIsNone(box.open(blob_old))                       # so does M9
            self.assertTrue(reg.verify("a", b"x", reg.sign("a", b"x")))
            self.assertEqual(box.open(seal(box.public(), "s", {"x": 1}))[:2], ("s", {"x": 1}))
        finally:
            crypto.set_provider(prev)
        self.assertTrue(reg.verify("a", b"x", sig_old))
        with self.assertRaises(TypeError):
            crypto.set_provider(object())


# ---------------------------------------------------------------- A4: M8 uses the M1 registry
class Clock:
    def __init__(self, t=1_000_000):
        self.t = t
    def __call__(self):
        return self.t


class TestM8OnRegistry(unittest.TestCase):
    def test_signer_is_required_and_unenrolled_authors_are_rejected(self):
        with self.assertRaises(TypeError):
            EventStore()
        with self.assertRaises(TypeError):
            Replica("a")
        reg = dev_fleet(["a"])
        stranger = Replica("mallory", dev_fleet(["mallory"]), now_ms=Clock())   # signs under another registry
        ev = stranger.open_claim("c1", "X", 0.5)
        self.assertEqual(EventStore(reg).add(ev), [])                           # "mallory" is not enrolled


# ---------------------------------------------------------------- A3: HLC drift
class TestHlcDrift(unittest.TestCase):
    def test_clock_rejects_far_future_and_malformed_stamps(self):
        c = HLClock("n", Clock(), max_drift_ms=1000)
        self.assertTrue(c.drift_ok(HLC(1_000_900, 0, "x")))
        for bad in (HLC(1_002_000, 0, "x"), HLC(-1, 0, "x"), HLC(1, -1, "x"), HLC(1, 1 << 40, "x")):
            self.assertFalse(c.drift_ok(bad))
            with self.assertRaises(ClockDrift):
                c.update(bad)
        self.assertEqual(c.now().wall, 1_000_000)                  # failed update did not move the clock
        c.update(HLC(1_000_500, 3, "x"))
        self.assertGreaterEqual(c.now().wall, 1_000_500)

    def test_bound_can_be_disabled(self):
        c = HLClock("n", Clock(), max_drift_ms=None)
        c.update(HLC(10 ** 15, 0, "x"))

    def test_future_heartbeat_cannot_keep_a_dead_dispatch_alive(self):
        clk, evil_clk = Clock(), Clock(1_000_000 + 10 * 24 * 3600 * 1000)   # attacker's clock: +10 days
        reg = dev_fleet(["a", "evil"])
        a = Replica("a", reg, now_ms=clk, lease_ms=60_000)
        evil = Replica("evil", reg, now_ms=evil_clk, lease_ms=60_000)
        d = evil.dispatch("c1", "evil")
        beat = evil.heartbeat(d)
        self.assertEqual(a.ingest(list(evil.store.logs["evil"])), 0)
        self.assertEqual(a.rejected_drift, 2)
        self.assertNotIn(d, a.book.recs)                           # nothing from the skewed clock landed
        clk.t += 10 ** 6
        self.assertEqual(a.plan().reopen, [])                      # (and no phantom lease to reopen)
        self.assertGreater(beat.hlc.wall, clk.t)

    def test_honest_events_inside_the_bound_still_merge_and_advance_the_clock(self):
        clk_a, clk_b = Clock(), Clock(1_000_000 + 60_000)          # b is a minute fast: tolerated
        reg = dev_fleet(["a", "b"])
        a, b = Replica("a", reg, now_ms=clk_a), Replica("b", reg, now_ms=clk_b)
        b.open_claim("c1", "X", 0.5)
        self.assertEqual(a.ingest(b.events_for(a.summary())), 1)
        self.assertEqual(a.rejected_drift, 0)
        self.assertGreaterEqual(a.clock.now().wall, 1_060_000)


# ---------------------------------------------------------------- A3: M0 ingress
def wire(kind="m4.hazard", prio=Priority.DISTRESS, origin="o", payload=None, ttl=3600.0, created=0.0, cell="c"):
    return Message(kind, prio, origin, payload if payload is not None else {"x": 1}, HLC(1, 0, origin),
                   dest_cell=cell, ttl_s=ttl, created=created)


class TestIngressGuard(unittest.TestCase):
    def test_priority_comes_from_the_kind_not_the_wire(self):
        g = IngressGuard()
        m = g.admit(wire("m5.vouch", Priority.DISTRESS), "p", 10.0)
        self.assertEqual(m.priority, Priority.CHATTER)
        self.assertEqual(g.admit(wire("m4.hazard", Priority.DISTRESS), "p", 10.0).priority, Priority.HAZARD)
        self.assertEqual(g.admit(wire("m9.case", Priority.CHATTER), "p", 10.0).priority, Priority.DISTRESS)

    def test_m8_events_get_distress_only_for_claims(self):
        g = IngressGuard()
        self.assertEqual(g.admit(wire("m8.event", Priority.CHATTER, payload={"k": "claim"}), "p", 1.0).priority,
                         Priority.DISTRESS)
        self.assertEqual(g.admit(wire("m8.event", Priority.DISTRESS, payload={"k": "beat"}), "p", 1.0).priority,
                         Priority.DISPATCH)

    def test_msg_id_is_unchanged_by_sanitising(self):
        g, m = IngressGuard(), wire("m5.vouch", Priority.DISTRESS, ttl=10 ** 9)
        self.assertEqual(g.admit(m, "p", 5.0).msg_id, m.msg_id)

    def test_unknown_kind_dropped_and_counted(self):
        g = IngressGuard()
        self.assertIsNone(g.admit(wire("evil.kind"), "p", 1.0))
        self.assertEqual(g.dropped["kind_not_allowed"], 1)

    def test_ttl_clamped_and_created_cannot_be_in_the_future(self):
        g = IngressGuard(IngressLimits(max_ttl_s=100.0))
        m = g.admit(wire(ttl=10 ** 12, created=10 ** 12), "p", 50.0)
        self.assertEqual((m.ttl_s, m.created), (100.0, 50.0))
        self.assertTrue(m.expired(151.0))                           # not immortal any more
        for bad in (0.0, -5.0, float("nan"), float("inf")):
            self.assertIsNone(g.admit(wire(ttl=bad), "p", 1.0), bad)
        self.assertIsNone(g.admit(wire(created=float("nan")), "p", 1.0))

    def test_size_and_shape_caps(self):
        g = IngressGuard()
        self.assertIsNone(g.admit(wire(payload={"b": "x" * 5000}), "p", 1.0))
        self.assertIsNone(g.admit(wire(origin="o" * 200), "p", 1.0))
        self.assertIsNone(g.admit(wire(cell="c" * 200), "p", 1.0))
        self.assertIsNone(g.admit(dataclasses.replace(wire(), payload=[1, 2]), "p", 1.0))
        self.assertTrue({"wire_size", "origin", "dest_cell", "payload_type"} <= set(g.dropped))

    def test_sealed_m9_packet_fits_the_wire_cap(self):
        blob = seal(BoxKey().public(), "p", {"x": "y" * 300})
        m = wire("m9.sealed", payload={"b": blob.hex()})
        self.assertIsNotNone(IngressGuard().admit(m, "p", 1.0))

    def test_identity_limiter_applies_per_radio_neighbour(self):
        reg = dev_fleet(["peer"])
        g = IngressGuard(limiter=IdentityLimiter(reg, base_rate=0.0, base_burst=2.0))
        got = [g.admit(wire(payload={"i": i}), "peer", 0.0) is not None for i in range(4)]
        self.assertEqual(got, [True, True, False, False])
        self.assertIsNone(g.admit(wire(), "stranger", 0.0))         # unenrolled neighbour gets nothing
        self.assertEqual(g.dropped["rate_limited"], 3)

    def test_bloom_bounds(self):
        g, ok = IngressGuard(), BloomFilter.for_capacity(50)
        good = {"bf": ok.to_json(), "cell": "c", "bat": 0.5}
        self.assertIsNotNone(g.hello(good, 200))
        hostile = [
            {"bf": [10 ** 9, 4, "0"]},                              # absurd bit count
            {"bf": [256, 10 ** 6, "0"]},                            # absurd hash count
            {"bf": [256, 4, "f" * 100000]},                         # int() bomb
            {"bf": [256, 4, "zz"]}, {"bf": [256, 4, 7]}, {"bf": "x"}, {"bf": [True, 4, "0"]},
            {"bf": [64, 4, "f" * 17]},                              # hex longer than m bits
            {"bf": ok.to_json(), "cell": "c" * 99}, {"bf": ok.to_json(), "bat": float("nan")},
            {"bf": ok.to_json(), "bat": "full"},
        ]
        for f in hostile:
            self.assertIsNone(g.hello(f, 300), f)
        self.assertIsNone(g.hello(good, 10 ** 6))
        self.assertEqual(g.hello({"bf": ok.to_json(), "bat": 9}, 100)[2], 1.0)   # clamped

    def test_frame_size_cap(self):
        g = IngressGuard()
        self.assertFalse(g.frame_ok(b"x" * 100_000))
        self.assertFalse(g.frame_ok("not bytes"))
        self.assertTrue(g.frame_ok(b"{}"))


class TestIngressOnTheMesh(unittest.TestCase):
    def mesh(self, **guard_kw):
        m, got, order = SimMedium(rng=random.Random(1)), [], []
        rx = build_sim_node("rx", m, guard=IngressGuard(**guard_kw), on_deliver=lambda ms: got.append(ms))
        tx = build_sim_node("tx", m)
        m.link("rx", "tx")
        return m, rx, tx, got

    def spin(self, nodes, t0, t1):
        t = t0
        while t < t1:
            for n in nodes:
                n.tick(t)
            t += 1.0

    def test_honest_traffic_still_flows_through_a_guarded_node(self):
        m, rx, tx, got = self.mesh()
        tx.originate(wire("m9.case", created=0.0), 0.0)
        self.spin([rx, tx], 0, 10)
        self.assertEqual([x.kind for x in got], ["m9.case"])

    def test_hostile_frames_are_dropped_not_crashed_on(self):
        m, rx, tx, got = self.mesh()
        for raw in (b"\xff", b"{}", b'{"t":"hello","bf":[1,1,"0"]}', b'{"t":"data","m":5}', b"x" * 50_000,
                    json.dumps({"t": "hello", "bf": [256, 4, "f" * 70000]}).encode()):
            m.transmit("tx", "rx", raw, 0.0)
        self.spin([rx], 0, 3)
        self.assertEqual(got, [])
        self.assertEqual(rx.neighbors, {})

    def test_attacker_cannot_promote_chatter_to_distress_on_the_wire(self):
        m, rx, tx, got = self.mesh()
        m.transmit("tx", "rx", json.dumps({"t": "hello", "id": "tx", "cell": None, "bat": 1.0,
                                           "bf": BloomFilter.for_capacity(16).to_json()}).encode(), 0.0)
        forged = wire("m5.vouch", Priority.DISTRESS, created=0.0)
        m.transmit("tx", "rx", json.dumps({"t": "data", "m": forged.to_wire(), "tok": 3}).encode(), 0.0)
        self.spin([rx], 0, 2)
        self.assertEqual([x.priority for x in got], [Priority.CHATTER])

    def test_replica_bridge_survives_the_guard(self):
        clk, m = Clock(), SimMedium(rng=random.Random(2))
        reg = dev_fleet(["a", "b"])
        nodes = {n: build_sim_node(n, m, guard=IngressGuard()) for n in "ab"}
        reps = {n: Replica(n, reg, now_ms=clk) for n in "ab"}
        for n in "ab":
            bridge.attach(reps[n], nodes[n])
        m.link("a", "b")
        reps["a"].open_claim("c1", "X", 0.9)
        for _ in range(10):
            clk.t += 1000
            for n in nodes.values():
                n.tick(clk.t / 1000)
        self.assertIsNotNone(reps["b"].book.claims.get("c1"))
        self.assertEqual(nodes["b"].guard.dropped.total(), 0)


class TestTokenClamp(unittest.TestCase):
    def test_epidemic_classes_never_inherit_peer_tokens(self):
        p = SprayPolicy()
        for prio in (Priority.DISTRESS, Priority.DISPATCH, Priority.HAZARD):
            for tok in (0, 1, 5, -3, None, "x"):
                self.assertIsNone(p.clamp(prio, tok), (prio, tok))

    def test_spray_class_tokens_bounded_and_malformed_gets_cap(self):
        p = SprayPolicy()
        self.assertEqual([p.clamp(Priority.CHATTER, t) for t in (10 ** 6, 2, 0, -1, None, "x", True, 1.5)],
                         [4, 2, 4, 4, 4, 4, 4, 4])

    def test_distress_received_with_hostile_zero_tokens_keeps_spreading(self):
        node = build_sim_node("n1", SimMedium())
        m = wire("t", Priority.DISTRESS)
        self.assertTrue(node.engine.receive(m, 0, 0.0, "n0"))
        self.assertIsNone(node.engine.store.get(m.msg_id).tokens)      # not parked in the wait phase


# ---------------------------------------------------------------- A2: M11 measures its baselines
class TestM11Measured(unittest.TestCase):
    def setUp(self):
        try:
            from m11_redteam import attack_suite as A
        except ImportError as e:               # needs M3/M4/M5/M6
            self.skipTest(str(e))
        self.A = A

    def test_flood_baseline_is_a_measurement(self):
        self.assertEqual(self.A._flood(None, None), 500)
        from m11_redteam.defenses import Dedupe, RateLimiter
        self.assertLess(self.A._flood(RateLimiter(2.0, 10.0), Dedupe()), 50)
        r = self.A.packet_flood()
        self.assertEqual(r.undefended, 1.0)
        # make the "undefended" path stricter and the reported number must follow
        with mock.patch.object(self.A, "_flood", side_effect=lambda l, d, n=500: 250 if l is None else 10):
            self.assertEqual(self.A.packet_flood().undefended, 0.5)

    def test_battery_baseline_is_a_measurement(self):
        self.assertEqual(self.A.battery_curve_fraud().undefended, 1.0)
        with mock.patch.object(self.A, "_naive_battery_ok", lambda s: False):
            r = self.A.battery_curve_fraud()
        self.assertEqual((r.undefended, r.defended), (0.0, 0.0))

    def test_no_hard_coded_undefended_literals_remain(self):
        import inspect
        for f in (self.A.packet_flood, self.A.battery_curve_fraud):
            self.assertNotIn("Result(\"" + f.__name__ + "\", 1.0", inspect.getsource(f))
            self.assertNotIn("off = 1.0", inspect.getsource(f))


if __name__ == "__main__":
    unittest.main()
