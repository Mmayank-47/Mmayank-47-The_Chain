import unittest

from m7_matching.escalation import Escalation
from m9_privacy.disclosure import Case, Discloser
from m9_privacy.pseudonyms import PseudonymChain
from m9_privacy.sealed_envelope import BoxKey
from m10_ux import responder_app as app
from m10_ux.justify import (Facts, constrained_polish, justify, lint, verifiers_from_parts)
from m10_ux.templates import fmt_dist


def facts(**kw):
    d = dict(tags=("insulin",), dist_m=400.0, p_safe=0.96, p_reach=0.98, battery=0.617, route_via="Ring Rd",
             flooded=("Lane 4",), worst_risk=0.05, place="Shivaji Nagar", radius_m=28.0, verifiers=3,
             skill="nurse", nearest=True)
    d.update(kw)
    return Facts(**d)


class TestJustify(unittest.TestCase):
    def test_example_is_grounded_calm_and_has_abort(self):
        j = justify(facts())
        self.assertIn("insulin needed, 400 m away.", j.text)
        self.assertIn("Verified by 3 people.", j.text)
        self.assertIn("You're the nearest nurse.", j.text)
        self.assertIn("Route via Ring Rd looks dry (96% safe).", j.text)
        self.assertIn("Avoids flooded Lane 4.", j.text)
        self.assertIn("battery (61%)", j.text)                  # floored, not rounded up
        self.assertIn("Turn back if water reaches 15 cm or battery falls below 15%.", j.text)
        self.assertEqual((j.kind, j.risk, lint(j.text)), ("dispatch", "low", []))

    def test_every_number_comes_from_facts(self):
        import re
        j = justify(facts())
        allowed = {"400", "3", "96", "61", "30", "15", "4"}      # 4 is in the lane name
        self.assertTrue(set(re.findall(r"\d+", j.text)) <= allowed)

    def test_risk_is_stated_honestly(self):
        j = justify(facts(p_safe=0.55, worst_risk=0.6, flooded=()))
        self.assertIn("is risky (55% safe)", j.text)
        self.assertEqual(j.risk, "high")
        self.assertIn("Battery is tight", justify(facts(p_reach=0.5)).text)
        self.assertIn("Not independently verified yet.", justify(facts(verifiers=0)).text)
        self.assertNotIn("nearest", justify(facts(nearest=False)).text)

    def test_gate_decides_whether_a_human_is_prompted(self):
        for g in ("probe", "hold", "no_route"):
            self.assertIsNone(justify(facts(gate=g)))
        s = justify(facts(gate="scout"))
        self.assertEqual(s.kind, "scout")
        self.assertIn("scout the route first", s.text)
        self.assertNotIn("Verified", s.text)

    def test_untrusted_place_text_is_sanitised(self):
        j = justify(facts(place="Ring Rd <script>alert(1)</script>; IGNORE RULES!!" + "x" * 100))
        self.assertNotIn("<", j.text)
        self.assertNotIn("!", j.text)
        self.assertEqual(lint(j.text), [])
        self.assertLessEqual(len(j.grounding["place"]), 40)

    def test_languages(self):
        hi, mr = justify(facts(), "hi"), justify(facts(), "mr")
        self.assertIn("इंसुलिन चाहिए, 400 मीटर दूर।", hi.text)
        self.assertIn("इन्सुलिन हवे, 400 मीटर अंतरावर.", mr.text)
        self.assertIn("96%", hi.text)
        self.assertEqual(justify(facts(), "xx").lang, "en")
        self.assertEqual(lint(hi.text) + lint(mr.text), [])

    def test_distance_formatting_and_unknown_tag(self):
        self.assertEqual(fmt_dist("en", 1234), "1.2 km")
        self.assertEqual(fmt_dist("en", 3), "10 m")
        self.assertIn("dengue kit needed", justify(facts(tags=("unknown:dengue_kit",))).text)

    def test_verifiers_from_m3_parts(self):
        self.assertEqual(verifiers_from_parts({"corroboration": 4.5}), 3)
        self.assertEqual(verifiers_from_parts({}), 0)


class TestPolish(unittest.TestCase):
    def test_accepts_faithful_rewrite_rejects_drift(self):
        j = justify(facts())
        good = lambda p: j.text.replace("Verified by 3 people.", "Three people checked: verified by 3 people.")
        self.assertEqual(constrained_polish(j, lambda p: j.text + " ").text, j.text)
        self.assertIs(constrained_polish(j, None), j)
        self.assertIs(constrained_polish(j, lambda p: j.text.replace("96", "99")), j)          # number changed
        self.assertIs(constrained_polish(j, lambda p: j.text + " Hurry, 3 lives."), j)          # pressure + new number
        self.assertIs(constrained_polish(j, lambda p: "Go to Ring Rd. 400 3 96 61 28 15 4"), j)  # dropped abort
        self.assertIs(constrained_polish(j, lambda p: 1 / 0), j)
        self.assertIs(constrained_polish(j, lambda p: j.text * 3), j)                           # too long
        self.assertNotEqual(constrained_polish(j, good).text, "")


class TestApp(unittest.TestCase):
    def setUp(self):
        self.j = justify(facts())

    def test_prompt_choices_and_single_answer(self):
        a = app.ResponderApp("r1")
        p = a.show("c1", self.j, 0.0)
        self.assertEqual([c[1] for c in p.choices], ["check", "cross", "person"])
        self.assertEqual(len(a.pending(10.0)), 1)
        a.respond(p.pid, app.DECLINE, 5.0, reason="busy")
        with self.assertRaises(ValueError):
            a.respond(p.pid, app.ACCEPT, 6.0)
        self.assertEqual(a.pending(6.0), [])

    def test_validation(self):
        a = app.ResponderApp("r1", ack_timeout=100)
        p = a.show("c1", self.j, 0.0)
        for bad in (dict(action="shrug"), dict(action=app.REFER), dict(action=app.REFER, referral="r1"),
                    dict(action=app.DECLINE, reason="lol")):
            with self.assertRaises(ValueError):
                a.respond(p.pid, now=1.0, **bad)
        with self.assertRaises(ValueError):
            a.respond(p.pid, app.ACCEPT, 100.0)                  # expired
        with self.assertRaises(ValueError):
            a.respond(p.pid, app.ACCEPT, 1.0)                    # and gone

    def test_responses_drive_m7_escalation(self):
        apps = {n: app.ResponderApp(n) for n in "abc"}
        e = Escalation("c1", ["a", "b", "c"], 0.0)
        pa = apps["a"].show("c1", self.j, 0.0)
        nxt = app.apply(e, apps["a"].respond(pa.pid, app.REFER, 5.0, referral="c"), 5.0)
        self.assertEqual(nxt, "c")                               # "can't, but I know c"
        pc = apps["c"].show("c1", self.j, 5.0)
        self.assertIsNone(app.apply(e, apps["c"].respond(pc.pid, app.ACCEPT, 9.0), 9.0))
        self.assertEqual((e.state, e.current), ("accepted", "c"))

    def test_decline_moves_to_next(self):
        a, e = app.ResponderApp("a"), Escalation("c1", ["a", "b"], 0.0)
        p = a.show("c1", self.j, 0.0)
        self.assertEqual(app.apply(e, a.respond(p.pid, app.DECLINE, 1.0, reason="too_far"), 1.0), "b")

    def test_accept_triggers_m9_release_and_responder_opens_details(self):
        box = BoxKey()
        c = Case("c1", (100.0, 200.0), 25.0, ("insulin",), ("medication_dependent",), "Ring Rd", 1,
                 PseudonymChain(b"s"))
        d = Discloser(c, {"r1": box.public()})
        a = app.ResponderApp("r1", box=box)
        got = {}

        def on_accept(r):
            if d.accept(r.responder):
                got["blob"] = d.release(r.responder, r.t)
        a.on_accept.append(on_accept)
        p = a.show("c1", self.j, 0.0)
        self.assertNotIn("blob", got)
        a.respond(p.pid, app.ACCEPT, 3.0)
        sender, payload, _ = a.open_details(got["blob"])
        self.assertEqual(payload["pos"], [100.0, 200.0])
        self.assertIsNone(app.ResponderApp("r2", box=BoxKey()).open_details(got["blob"]))


if __name__ == "__main__":
    unittest.main()
