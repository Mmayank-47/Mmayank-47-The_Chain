"""Part 1 (hygiene and layering): one home for TokenBucket, one wire path for hazards and vouches,
anti-entropy frames crossing the ingress guard, and fork quarantine."""
import ast
import json
import pathlib
import random
import re
import unittest

from core.dispatcher import KINDS
from core.ratelimit import TokenBucket
from core.types import HLC, Message, Priority, event_priority
from m0_transport.ingress import AE_FRAMES, AE_SUM, KIND_PRIORITY, IngressGuard, IngressLimits
from m0_transport.node import build_sim_node
from m0_transport.radio_adapter import SimMedium
from m1_identity.keys import dev_fleet
from m1_identity.ratelimit import IdentityLimiter
from m5_trust.trust import TrustEngine
from m5_trust.vouch_graph import VouchGraph
from m8_merge import bridge
from m8_merge.anti_entropy import AntiEntropy
from m8_merge.event_log import EventStore
from m8_merge.replica import Replica

ROOT = pathlib.Path(__file__).resolve().parent.parent
LAYER = re.compile(r"^m\d+_")


class Clock:
    def __init__(self, t=1_000_000):
        self.t = t

    def __call__(self):
        return self.t


def imports_of(path):
    """Top-level package names a file imports (relative imports stay inside their package)."""
    out = set()
    for n in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
        if isinstance(n, ast.Import):
            out |= {a.name.split(".")[0] for a in n.names}
        elif isinstance(n, ast.ImportFrom) and n.level == 0 and n.module:
            out.add(n.module.split(".")[0])
    return out


def layer_dirs():
    return sorted(p for p in ROOT.iterdir() if p.is_dir() and LAYER.match(p.name))


def wire(kind, payload=None):
    return Message(kind, Priority.DISTRESS, "o", payload if payload is not None else {"x": 1},
                   HLC(1, 0, "o"), dest_cell="c", created=0.0)


# ------------------------------------------------------------------------------------ layering
class TestLayering(unittest.TestCase):
    def test_nothing_below_m11_imports_the_red_team_package(self):
        offenders = [str(f.relative_to(ROOT))
                     for d in layer_dirs() + [ROOT / "core"] if d.name != "m11_redteam"
                     for f in d.glob("*.py") if "m11_redteam" in imports_of(f)]
        self.assertEqual(offenders, [])

    def test_transport_depends_only_on_core(self):
        for f in (ROOT / "m0_transport").glob("*.py"):
            up = {n for n in imports_of(f) if LAYER.match(n)} - {"m0_transport"}
            self.assertEqual(up, set(), f.name)

    def test_token_bucket_has_one_home_and_the_old_import_still_works(self):
        from m11_redteam import defenses
        self.assertIs(defenses.TokenBucket, TokenBucket)
        b = TokenBucket(rate=1.0, burst=2)
        self.assertEqual([b.allow(0.0) for _ in range(3)], [True, True, False])
        self.assertTrue(b.allow(1.0))


# ------------------------------------------------------------------------------------ one path
class TestOnePathPerKind(unittest.TestCase):
    def test_hazards_and_vouches_are_not_message_kinds(self):
        g = IngressGuard()
        for k in ("m4.hazard", "m5.vouch"):
            self.assertNotIn(k, KIND_PRIORITY)
            self.assertNotIn(k, KINDS)
            self.assertIsNone(g.admit(wire(k), "p", 1.0))
        self.assertEqual(g.dropped["kind_not_allowed"], 2)

    def test_dispatcher_kinds_and_ingress_allowlist_stay_pinned_together(self):
        self.assertEqual(set(KINDS), set(KIND_PRIORITY))

    def test_ingress_and_bridge_apply_the_same_priority_rule(self):
        r = Replica("a", dev_fleet(["a"]), now_ms=Clock())
        evs = {"claim": r.open_claim("c1", "X", 0.5),
               "hazard": r.emit("hazard", {"pos": [0.0, 0.0], "kind": "hazard"}),
               "vouch": r.emit("vouch", {"to": "b", "w": 1.0}),
               "beat": r.emit("beat", {"did": "c1|a"})}
        want = {"claim": Priority.DISTRESS, "hazard": Priority.HAZARD,
                "vouch": Priority.DISPATCH, "beat": Priority.DISPATCH}
        g = IngressGuard()
        for kind, ev in evs.items():
            m = bridge.to_message(ev, 1.0)
            self.assertEqual((m.priority, g.admit(m, "p", 1.0).priority), (want[kind], want[kind]), kind)

    def test_hostile_event_kind_gets_the_default_instead_of_raising(self):
        self.assertEqual(event_priority(["claim"]), Priority.DISPATCH)
        m = wire("m8.event", {"k": ["claim"]})
        self.assertEqual(IngressGuard().admit(m, "p", 1.0).priority, Priority.DISPATCH)


# ------------------------------------------------------------------------------------ AE frames
class TestAntiEntropyAtTheGuard(unittest.TestCase):
    def setUp(self):
        self.clk = Clock()
        reg = dev_fleet(["a", "b"])
        self.a = Replica("a", reg, now_ms=self.clk)
        self.b = Replica("b", reg, now_ms=self.clk)
        for i in range(3):
            self.clk.t += 1
            self.a.open_claim(f"c{i}", "X", 0.5)
        self.ea, self.eb = AntiEntropy(self.a), AntiEntropy(self.b)
        self.summary = self.eb.request("a", 0.0)
        self.batch = self.ea.on_summary("b", self.summary, 0.0)

    def test_allowlist_is_exactly_what_anti_entropy_sends(self):
        self.assertEqual({self.summary["t"], self.batch["t"]}, set(AE_FRAMES))

    def test_real_frames_cross_the_guard_unchanged_and_still_merge(self):
        g = IngressGuard()
        for f in (self.summary, self.batch):
            self.assertEqual(g.ae_frame(f, len(json.dumps(f)), "x", 0.0), f)
        admitted = g.ae_frame(self.batch, len(json.dumps(self.batch)), "x", 0.0)
        self.assertEqual(self.eb.on_events("a", admitted), 3)
        self.assertEqual(g.dropped.total(), 0)

    def test_hostile_types_shapes_and_sizes_are_dropped_and_counted(self):
        g = IngressGuard(IngressLimits(max_ae_nodes=2, max_ae_events=2))
        for f in ({"t": "ae.sum", "s": [1]}, {"t": "ae.sum", "s": {str(i): [0, ""] for i in range(3)}},
                  {"t": "ae.ev", "e": "x"}, {"t": "ae.ev", "e": [1, 2, 3]}):
            self.assertIsNone(g.ae_frame(f, 100, "p", 0.0), f)
        self.assertIsNone(g.ae_frame(self.batch, 10 ** 6, "p", 0.0))
        for f in ({"t": "hello"}, {"t": ["ae.sum"]}, {"t": 5}, [], None, {}):
            self.assertIsNone(g.ae_frame(f, 10, "p", 0.0), f)
        self.assertEqual((g.dropped["ae_shape"], g.dropped["ae_size"], g.dropped["ae_type"]), (4, 1, 6))

    def test_ae_frames_spend_the_sender_budget(self):
        g = IngressGuard(limiter=IdentityLimiter(dev_fleet(["peer"]), base_rate=0.0, base_burst=1.0))
        self.assertIsNotNone(g.ae_frame(self.summary, 50, "peer", 0.0))
        self.assertIsNone(g.ae_frame(self.summary, 50, "peer", 0.0))
        self.assertIsNone(g.ae_frame(self.summary, 50, "stranger", 0.0))
        self.assertEqual(g.dropped["rate_limited"], 2)

    def test_a_guarded_node_routes_only_admitted_frames_to_its_handler(self):
        m = SimMedium(rng=random.Random(1))
        rx = build_sim_node("rx", m, guard=IngressGuard())
        build_sim_node("tx", m)
        m.link("rx", "tx")
        got = []
        rx.frame_handlers[AE_SUM] = lambda frm, f, now: got.append((frm, f["t"]))
        bad = {"t": "ae.sum", "s": "nope"}
        for f in (self.summary, bad, self.batch):          # batch is admitted but has no handler
            m.transmit("tx", "rx", json.dumps(f).encode(), 0.0)
        rx.tick(0.0)
        self.assertEqual(got, [("tx", AE_SUM)])
        self.assertEqual(rx.guard.dropped["ae_shape"], 1)


# ------------------------------------------------------------------------------------ forks
class TestForkQuarantine(unittest.TestCase):
    def setUp(self):
        self.reg = dev_fleet(["a"])
        h1 = Replica("a", self.reg, now_ms=Clock())         # same identity, two histories
        h2 = Replica("a", self.reg, now_ms=Clock())
        self.e1, self.later, self.far = (h1.open_claim(c, "X", 0.5) for c in ("c1", "c3", "c4"))
        self.e2, self.e2b = (h2.open_claim(c, "X", 0.9) for c in ("c2", "c5"))
        self.store = EventStore(self.reg)

    def test_fork_is_recorded_with_evidence_and_quarantines_the_node(self):
        s, told = self.store, []
        s.on_fork.append(told.append)
        self.assertEqual(len(s.add(self.e1)), 1)
        self.assertEqual(s.add(self.e2), [])
        self.assertEqual(s.add(self.e2), [])                # same proof again: no second notification
        self.assertEqual(told, ["a"])
        self.assertEqual(s.fork_evidence["a"], (self.e1, self.e2))
        self.assertEqual(s.add(self.later), [])             # validly signed, but quarantined
        self.assertEqual(len(s.logs["a"]), 1)
        self.assertEqual(s.dropped_quarantined, 2)

    def test_chain_break_with_a_valid_signature_is_equivocation_too(self):
        s = self.store
        s.add(self.e1)
        self.assertEqual(s.add(self.e2b), [])               # seq 1, but its prev is e2's hash, not ours
        self.assertIn("a", s.forks)
        self.assertEqual(s.fork_evidence["a"], (self.e1, self.e2b))

    def test_buffered_events_of_a_forker_never_land(self):
        s = self.store
        s.add(self.far)                                     # seq 2 arrives early: buffered
        s.add(self.e1)
        s.add(self.e2)
        self.assertNotIn("a", s.pending)
        self.assertEqual(s.add(self.later), [])             # the gap can no longer be filled
        self.assertEqual(len(s.logs["a"]), 1)

    def test_a_peers_summary_is_only_a_suspicion_never_a_quarantine(self):
        s = self.store
        s.add(self.e1)
        s.missing({"a": [1, "not-the-head"]})               # unsigned claim from a hostile peer
        self.assertEqual((s.forks, s.suspects), (set(), {"a"}))
        self.assertEqual(len(s.add(self.later)), 1)         # honest node keeps working

    def test_fork_reaches_m5_through_the_listener(self):
        eng = TrustEngine(VouchGraph(seeds=["root"]))
        self.store.on_fork.append(lambda n: eng.rep.record(n, "equivocation", 0.0))
        before = eng.trust("a", 0.0)
        self.store.add(self.e1)
        self.store.add(self.e2)
        self.assertEqual(before, 0.5)
        self.assertLess(eng.trust("a", 0.0), 0.3)

    def test_listener_exceptions_do_not_break_ingestion(self):
        self.store.on_fork.append(lambda n: 1 / 0)
        self.store.add(self.e1)
        self.store.add(self.e2)
        self.assertIn("a", self.store.forks)


if __name__ == "__main__":
    unittest.main()
