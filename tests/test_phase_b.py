"""Phase B shared contracts: signed wire claim + adapters, kind dispatcher, M2->M7 features,
extra M8 event kinds, lease split, anti-entropy."""
import dataclasses
import unittest
from types import SimpleNamespace

from core import claim_adapters as A
from core.claim import ClaimError, WireClaim
from core.dispatcher import KINDS, Dispatcher
from core.types import HLC, DispatchState as S, Message, Priority
from m1_identity.keys import dev_fleet
from m6_battery.urgency import urgency as battery_urgency
from m7_matching.features import claim_scores, combine_urgency, to_triage
from m7_matching.triage import Category
from m8_merge import bridge, kinds
from m8_merge.anti_entropy import AntiEntropy
from m8_merge.event_log import Event
from m8_merge.leases import expired, is_live
from m8_merge.replica import Replica

REG = dev_fleet(["a", "b", "c", "1", "2", "evil"])


class Clock:
    def __init__(self, t=1_000_000):
        self.t = t

    def __call__(self):
        return self.t


def rep(name, clk=None, **kw):
    return Replica(name, REG, now_ms=clk or Clock(), **kw)


def parsed(**kw):
    d = {"kind": "need", "capability_tags": ["insulin"], "features": ["medication_dependent"],
         "urgency": 0.44, "quantity": None, "language": "en", "confidence": 0.8, "source": "keyword"}
    d.update(kw)
    return d


def claim(node="a", **kw):
    return WireClaim.make(node, kw.pop("pos", (1234.5, 987.6)), kw.pop("radius", 20.0),
                          kw.pop("t", 10.0), kw.pop("parsed", parsed()), REG, **kw)


# ------------------------------------------------------------------------------------------ B1
class TestWireClaim(unittest.TestCase):
    def test_roundtrip_and_verify(self):
        c = claim()
        self.assertTrue(c.verify(REG))
        self.assertEqual(WireClaim.from_json(c.to_json()), c)
        self.assertTrue(WireClaim.from_json(c.to_json()).verify(REG))

    def test_id_is_content_derived(self):
        self.assertEqual(claim().id, claim().id)
        self.assertNotEqual(claim().id, claim(pos=(1.0, 2.0)).id)

    def test_tamper_forgery_and_replayed_signature_fail(self):
        c = claim()
        self.assertFalse(dataclasses.replace(c, pos=(0.0, 0.0)).verify(REG))          # moved
        self.assertFalse(dataclasses.replace(c, node="b").verify(REG))                # signed by someone else
        self.assertFalse(dataclasses.replace(c, id="0" * 16).verify(REG))             # id not the content
        self.assertFalse(dataclasses.replace(c, sig="0" * 64).verify(REG))
        ghost = dataclasses.replace(c, node="ghost")
        self.assertFalse(ghost.verify(REG))                                           # not enrolled

    def test_spoofed_src_is_signed_by_the_real_device_and_stays_visible(self):
        c = claim(node="evil", src="a")
        self.assertTrue(c.verify(REG))
        m3 = A.to_m3(c)
        self.assertNotEqual(m3.src, m3.node)                    # M3.identity_binding will punish this

    def test_malformed_input_rejected(self):
        good = claim().to_json()
        for k, v in (("p", [float("nan"), 0.0]), ("p", [1.0]), ("r", -1.0), ("r", 10 ** 9), ("t", -5.0),
                     ("n", ""), ("n", 5), ("d", "x"), ("d", {"x": float("inf")}), ("d", {"x": "y" * 2000})):
            with self.assertRaises(ClaimError, msg=(k, v)):
                WireClaim.from_json({**good, k: v})
        for bad in (None, [], {}, {"n": "a"}):
            with self.assertRaises(ClaimError):
                WireClaim.from_json(bad)
        with self.assertRaises(ClaimError):
            claim(radius=float("nan"))


class TestAdapters(unittest.TestCase):
    def test_to_m3(self):
        m = A.to_m3(claim(parsed=parsed(features=["unconscious"])), battery=0.2)
        self.assertEqual((m.node, m.src, m.pos, m.t, m.need, m.battery), ("a", "a", (1234.5, 987.6), 10.0, "medic", 0.2))
        self.assertEqual(A.to_m3(claim()).need, None)
        self.assertEqual(A.need_of({"capability_tags": ["boat"]}), "boat")

    def test_to_m8_args_never_carries_exact_position(self):
        cid, cell, urg = A.to_m8_args(claim())
        self.assertEqual((cell, urg), ("2:1", 0.44))
        r = rep("a")
        ev = A.open_in(r, claim())
        self.assertEqual(ev.data, {"claim": cid, "cell": "2:1", "urgency": 0.44})
        self.assertEqual(r.book.claims.get(cid)["cell"], "2:1")

    def test_cell_matches_m9_when_available(self):
        try:
            from m9_privacy.disclosure import cell_of
        except ImportError:
            self.skipTest("m9 not importable here")
        for p in ((0, 0), (499.9, 500.0), (-10.0, 1234.5)):
            self.assertEqual(A.cell_of(p), cell_of(p))

    def test_to_m7_uses_triage_and_battery(self):
        c = claim(parsed=parsed(features=["severe_bleeding"], capability_tags=[]))
        k = A.to_m7(c, p_valid=0.9, trust=0.7)
        self.assertEqual((k.id, k.pos, k.p_valid, k.trust, k.need), (c.id, c.pos, 0.9, 0.7, "medic"))
        self.assertEqual((k.urgency, k.survival_gain), (1.0, 0.8))
        minor = A.to_m7(claim(parsed=parsed(features=[], urgency=0.3)), 0.9)
        self.assertEqual(minor.urgency, 0.3)                       # UNKNOWN: M2's own estimate
        self.assertGreater(A.to_m7(claim(parsed=parsed(features=[], urgency=0.3)), 0.9, battery_tts_s=600).urgency, 0.3)

    def test_from_sim_signs_as_the_phone_and_drops_ground_truth(self):
        sc = SimpleNamespace(id=7, node=1, src=2, pos=(10.0, 20.0), t=5.0, urgency=0.9, need="medic",
                             kind="spoof", victim=None, battery=0.5)
        w = A.from_sim(sc, REG)
        self.assertTrue(w.verify(REG))
        self.assertEqual((w.node, w.src), ("1", "2"))
        self.assertNotIn("victim", w.parsed)
        self.assertEqual(A.need_of(w.parsed), "medic")


# ------------------------------------------------------------------------------------------ B2
def msg(kind="m8.event", payload=None):
    return Message(kind, Priority.DISPATCH, "o", payload or {"x": 1}, HLC(1, 0, "o"))


class TestDispatcher(unittest.TestCase):
    def test_routes_by_kind_and_counts_unrouted(self):
        d, got = Dispatcher(), []
        d.register("m8.event", lambda m: got.append(("a", m.kind)))
        d.on("m9.case")(lambda m: got.append(("c", m.kind)))
        self.assertEqual(d(msg("m8.event")), 1)
        self.assertEqual(d(msg("m9.case")), 1)
        self.assertEqual(d(msg("m3.response")), 0)        # a known kind nobody registered for
        self.assertEqual(got, [("a", "m8.event"), ("c", "m9.case")])
        self.assertEqual((d.stats["delivered"], d.stats["unrouted"]), (2, 1))

    def test_failing_handler_does_not_starve_the_next(self):
        d, got = Dispatcher(), []
        d.register("m8.event", lambda m: 1 / 0)
        d.register("m8.event", got.append)
        self.assertEqual(d(msg()), 1)
        self.assertEqual(len(got), 1)
        self.assertEqual(d.stats["error:m8.event"], 1)

    def test_unknown_kind_cannot_be_registered(self):
        with self.assertRaises(ValueError):
            Dispatcher().register("m8.evnt", print)

    def test_kinds_match_the_m0_ingress_allowlist(self):
        try:
            from m0_transport.ingress import KIND_PRIORITY
        except ImportError:
            self.skipTest("m0 not importable here")
        self.assertEqual(set(KINDS), set(KIND_PRIORITY))

    def test_bridge_attach_through_a_dispatcher(self):
        a, b = rep("a"), rep("b")
        node = SimpleNamespace(engine=SimpleNamespace(on_deliver=None), originate=lambda m, now: True)
        d = Dispatcher()
        bridge.attach(b, node, d)
        self.assertIs(node.engine.on_deliver, d)
        ev = a.open_claim("c1", "X", 0.5)
        node.engine.on_deliver(bridge.to_message(ev, 1.0))
        self.assertIsNotNone(b.book.claims.get("c1"))
        legacy = SimpleNamespace(engine=SimpleNamespace(on_deliver=None), originate=lambda m, now: True)
        bridge.attach(rep("c"), legacy)                         # old call shape still works
        self.assertTrue(callable(legacy.engine.on_deliver))


# ------------------------------------------------------------------------------------------ B3
class TestFeatureAdapter(unittest.TestCase):
    def test_mapping_only_asserts_what_the_text_says(self):
        f = to_triage(["not_walking", "not_breathing", "unconscious", "severe_bleeding", "trapped", "child"])
        self.assertEqual((f.can_walk, f.breathing, f.obeys_commands, f.perfusion_ok, f.resp_rate),
                         (False, False, False, False, None))
        self.assertEqual(to_triage([]), to_triage(["trapped", "elderly"]))
        self.assertIsNone(to_triage(["elderly"]).can_walk)

    def test_categories(self):
        self.assertEqual(claim_scores(["severe_bleeding"])[0], Category.IMMEDIATE)
        self.assertEqual(claim_scores(["unconscious"])[0], Category.IMMEDIATE)
        self.assertEqual(claim_scores(["not_walking"])[0], Category.UNKNOWN)        # one flag is too little for DELAYED
        self.assertEqual(claim_scores([], 0.3)[1:], (0.3, 0.5))

    def test_remote_not_breathing_is_not_written_off(self):
        cat, u, g = claim_scores(["not_breathing"])
        self.assertEqual((cat, u, g), (Category.IMMEDIATE, 1.0, 0.8))

    def test_offers_have_no_urgency(self):
        self.assertEqual(claim_scores(["child"], 0.0, "offer")[1:], (0.0, 0.0))

    def test_vulnerability_flags_do_not_change_start_scores(self):
        self.assertEqual(claim_scores(["child", "elderly"], 0.3)[1], 0.3)           # M2's urgency already has them
        self.assertEqual(claim_scores(["unconscious", "child"])[1:], (1.0, 0.8))

    def test_battery_combiner_is_monotone_capped_and_cannot_invent_urgency(self):
        self.assertEqual(combine_urgency(0.5, None), 0.5)
        self.assertEqual(combine_urgency(0.0, 0.0), 0.0)
        self.assertGreater(combine_urgency(0.5, 0.0), combine_urgency(0.5, 7200.0))
        self.assertGreater(combine_urgency(0.5, 600.0), 0.5)
        self.assertLessEqual(combine_urgency(0.15, 0.0), 0.15 + 0.3 * (1 - 0.15) + 1e-9)
        self.assertAlmostEqual(combine_urgency(0.5, 0.0), 1 - 0.5 * 0.7)
        self.assertEqual(combine_urgency(1.0, 0.0), 1.0)
        self.assertAlmostEqual(battery_urgency(0.0), 1.0)


# ------------------------------------------------------------------------------------------ B4
class Spy:
    def __init__(self):
        self.calls = []

    def report(self, *a):
        self.calls.append(("report", *a))

    def vouch(self, *a):
        self.calls.append(("vouch", *a))

    def record(self, *a):
        self.calls.append(("record", *a))

    def ingest(self, items):
        self.calls.append(("ingest", list(items)))
        return len(items)


class TestKinds(unittest.TestCase):
    def setUp(self):
        self.clk = Clock()
        self.a, self.b = rep("a", self.clk), rep("b", self.clk)
        self.spy = Spy()
        self.proj = kinds.Projector(hazards=self.spy, graph=self.spy, revocations=self.spy,
                                    reputation=self.spy, weight_fn=lambda n: 0.4).attach(self.b)

    def sync(self):
        self.b.ingest(self.a.events_for(self.b.summary()))

    def test_every_kind_projects_and_the_book_ignores_them(self):
        self.a.emit(kinds.HAZARD, kinds.hazard_data((100, 0), "hazard"))
        self.a.emit(kinds.VOUCH, kinds.vouch_data("c", 0.8))
        self.a.emit(kinds.REVOKE, kinds.revoke_data({"i": "council", "n": "x", "r": "medic", "t": 1.0, "g": "s"}))
        self.a.emit(kinds.CHALLENGE, kinds.challenge_data("c1", "c", "cid1", 120.0))
        self.a.emit(kinds.REPUTATION, kinds.reputation_data("c", "failed_challenge", 1.0))
        self.sync()
        names = [c[0] for c in self.spy.calls]
        self.assertEqual(names, ["report", "vouch", "ingest", "record"])
        self.assertEqual(self.spy.calls[0][1:3], ("a", (100.0, 0.0)))
        self.assertEqual(self.spy.calls[0][-1], 0.4)                         # weight is OURS, not the event's
        self.assertEqual(self.spy.calls[3][1:3], ("c", "failed_challenge"))
        self.assertAlmostEqual(self.spy.calls[3][-1], 0.4)
        self.assertEqual(self.proj.challenges["cid1"]["verifier"], "a")
        self.assertEqual(self.b.book.snapshot(), ([], []))
        self.assertEqual(sum(self.proj.rejected.values()), 0)

    def test_events_apply_once_even_if_delivered_twice_and_local_events_project_too(self):
        self.a.emit(kinds.VOUCH, kinds.vouch_data("c"))
        evs = self.a.events_for(self.b.summary())
        self.b.ingest(evs)
        self.b.ingest(evs)
        self.assertEqual(len(self.spy.calls), 1)
        self.b.emit(kinds.VOUCH, kinds.vouch_data("a"))
        self.assertEqual(len(self.spy.calls), 2)

    def test_hostile_payloads_are_rejected_not_fatal(self):
        bad = [(kinds.HAZARD, {"pos": [float("nan"), 0], "kind": "hazard"}),
               (kinds.HAZARD, {"pos": [0, 0], "kind": "lava"}),
               (kinds.HAZARD, {"pos": "x", "kind": "hazard"}),
               (kinds.VOUCH, {"to": "a", "w": 1.0}),                          # self-vouch
               (kinds.VOUCH, {"to": "x" * 500}),
               (kinds.REPUTATION, {"node": "a", "event": "verified_arrival", "w": 1}),   # self-praise
               (kinds.REPUTATION, {"node": "c", "event": "made_up"}),
               (kinds.CHALLENGE, {"cid": "k", "claim": "c", "claimant": "c", "deadline": float("inf")}),
               (kinds.HAZARD, {})]
        for k, d in bad:
            self.a.emit(k, d)
        self.sync()
        self.assertEqual(self.spy.calls, [])
        self.assertEqual(self.proj.rejected.total(), len(bad))

    def test_zero_trust_reporter_has_no_voice_and_challenge_table_is_bounded(self):
        p = kinds.Projector(hazards=self.spy, weight_fn=lambda n: 0.0, max_challenges=1)
        p.apply(self.a.emit(kinds.HAZARD, kinds.hazard_data((0, 0), "safe")))
        self.assertEqual(self.spy.calls, [])
        self.assertEqual(p.rejected["hazard"], 1)
        p.apply(self.a.emit(kinds.CHALLENGE, kinds.challenge_data("c1", "c", "k1", 1.0)))
        p.apply(self.a.emit(kinds.CHALLENGE, kinds.challenge_data("c1", "c", "k2", 1.0)))
        self.assertEqual(list(p.challenges), ["k1"])

    def test_builders_validate(self):
        for f in (lambda: kinds.hazard_data((0, 0), "x"), lambda: kinds.hazard_data((float("nan"), 0), "hazard"),
                  lambda: kinds.vouch_data(""), lambda: kinds.reputation_data("c", "e", float("nan"))):
            with self.assertRaises(ValueError):
                f()
        self.assertEqual(kinds.vouch_data("c", 5)["w"], 1.0)


class TestLeases(unittest.TestCase):
    def test_boundary_and_expired_orphans(self):
        self.assertTrue(is_live(1000, 61_000, 60_000))
        self.assertFalse(is_live(1000, 61_001, 60_000))
        clk = Clock()
        a = rep("a", clk, lease_ms=60_000)
        d1, d2 = a.dispatch("c1", "a"), a.dispatch("c2", "a")
        a.set_state(d2, S.RESOLVED)
        self.assertEqual(a.book.expired_dispatches(clk.t), [])
        clk.t += 61_000
        got = a.book.expired_dispatches(clk.t)
        self.assertEqual([r.did for r in got], [d1])                         # resolved is never an orphan
        self.assertEqual([r.responder for r in expired(a.book.recs.values(), clk.t, 60_000)], ["a"])
        a.heartbeat(d1)
        self.assertEqual(a.book.expired_dispatches(clk.t), [])


class TestAntiEntropy(unittest.TestCase):
    def pair(self, n_a=0, n_b=0, **kw):
        clk = Clock()
        a, b = rep("a", clk), rep("b", clk)
        for i in range(n_a):
            clk.t += 1
            a.open_claim(f"ca{i}", "X", 0.5)
        for i in range(n_b):
            clk.t += 1
            b.open_claim(f"cb{i}", "X", 0.5)
        return clk, a, b, AntiEntropy(a, **kw), AntiEntropy(b, **kw)

    def pull(self, src_ae, dst_ae, src_id, dst_id, now):
        """dst asks, src answers in batches; returns number of batches."""
        frame = dst_ae.request(src_id, now)
        out, n = src_ae.on_summary(dst_id, frame, now), 0
        while out:
            n += 1
            dst_ae.on_events(src_id, out, now)
            out = src_ae.next_batch(dst_id)
        return n

    def test_converges_with_batched_backfill_in_order(self):
        clk, a, b, ea, eb = self.pair(n_a=100, batch=10)
        self.assertEqual(self.pull(ea, eb, "a", "b", 0.0), 10)
        self.assertEqual(len(b.store.logs["a"]), 100)
        self.assertEqual(a.book.snapshot(), b.book.snapshot())
        self.assertEqual(self.pull(ea, eb, "a", "b", 100.0), 0)               # nothing left to send

    def test_both_directions(self):
        clk, a, b, ea, eb = self.pair(n_a=5, n_b=7)
        self.pull(ea, eb, "a", "b", 0.0)
        self.pull(eb, ea, "b", "a", 0.0)
        self.assertEqual(a.book.snapshot(), b.book.snapshot())
        self.assertEqual((len(a.store.logs["b"]), len(b.store.logs["a"])), (7, 5))

    def test_requests_and_summaries_are_rate_limited_per_peer(self):
        clk, a, b, ea, eb = self.pair(n_a=3, min_interval=5.0)
        self.assertIsNotNone(ea.request("b", 0.0))
        self.assertIsNone(ea.request("b", 4.9))
        self.assertIsNotNone(ea.request("c", 4.9))
        self.assertIsNotNone(ea.request("b", 5.0))
        frame = eb.request("a", 0.0)
        self.assertIsNotNone(ea.on_summary("b", frame, 0.0))
        self.assertIsNone(ea.on_summary("b", frame, 1.0))
        self.assertEqual(ea.dropped["summary_rate"], 1)

    def test_fresh_summary_supersedes_backlog(self):
        clk, a, b, ea, eb = self.pair(n_a=30, batch=10)
        ea.on_summary("b", eb.request("a", 0.0), 0.0)
        self.assertEqual(ea.pending_for("b"), 20)
        ea.on_summary("b", eb.request("a", 10.0), 10.0)
        self.assertEqual(ea.pending_for("b"), 20)

    def test_hostile_summaries_and_batches_are_dropped(self):
        clk, a, b, ea, eb = self.pair(n_a=3, batch=2, max_nodes=2)
        bads = [None, {"s": 5}, {"s": {"a": [1]}}, {"s": {"a": [-1, "h"]}}, {"s": {"a": [True, "h"]}},
                {"s": {"a": [1, "h" * 500]}}, {"s": {str(i): [0, ""] for i in range(3)}}, {"s": {"": [0, ""]}}]
        for i, f in enumerate(bads):
            self.assertIsNone(ea.on_summary("p", f, float(i * 10)), f)
        self.assertEqual(ea.dropped["bad_summary"], len(bads))
        evs = [e.to_json() for e in a.store.logs["a"]]
        self.assertEqual(eb.on_events("a", {"e": evs}), 0)                     # 3 > batch of 2
        self.assertEqual(eb.on_events("a", {"e": "x"}), 0)
        self.assertEqual(eb.on_events("a", {"e": [{"junk": 1}, 5]}), 0)
        self.assertEqual(eb.dropped["oversize_batch"], 1)
        self.assertEqual(eb.dropped["bad_event"], 2)

    def test_forged_events_inside_a_batch_do_not_land(self):
        clk, a, b, ea, eb = self.pair(n_a=2)
        evs = [e.to_json() for e in a.store.logs["a"]]
        evs[0] = {**evs[0], "d": {"claim": "evil", "cell": "X", "urgency": 1}}
        self.assertEqual(eb.on_events("a", {"e": evs}), 0)
        self.assertEqual(b.store.logs.get("a", []), [])

    def test_peer_tables_are_bounded(self):
        clk, a, b, ea, eb = self.pair(max_peers=3)
        for i in range(10):
            ea.request(f"p{i}", 0.0)
        self.assertEqual(len(ea._sent), 3)


if __name__ == "__main__":
    unittest.main()
