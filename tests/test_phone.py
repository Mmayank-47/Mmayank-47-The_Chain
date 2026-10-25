"""Unit and integration tests for phone.PhoneNode and the M2-M10 chain."""
import time
import unittest

from m0_transport.radio_adapter import SimMedium, SimRadio
from m1_identity.keys import dev_fleet
from phone.node import PhoneNode


class Clock:
    def __init__(self, start_ms: int = 1_000_000):
        self.t = start_ms

    def __call__(self) -> int:
        return self.t

    def advance(self, ms: int = 1000):
        self.t += ms

    def now_s(self) -> float:
        return self.t / 1000.0


class TestPhoneNode(unittest.TestCase):
    def setUp(self):
        self.clk = Clock()
        self.fleet = dev_fleet(["a", "b", "c"])
        self.medium = SimMedium()

    def test_phase_4a_two_phone_nodes_event_propagation(self):
        """Phase 4a: Two PhoneNode instances over SimMedium, node A emits event,
        spin medium, assert event arrives in node B's replica."""
        radio_a = SimRadio("a", self.medium)
        radio_b = SimRadio("b", self.medium)
        self.medium.link("a", "b")

        node_a = PhoneNode("a", radio_a, self.fleet, now_ms=self.clk)
        node_b = PhoneNode("b", radio_b, self.fleet, now_ms=self.clk)

        # Exchange hello beacons first so nodes know each other as neighbors
        for _ in range(3):
            self.clk.advance(1000)
            node_a.tick(self.clk.now_s())
            node_b.tick(self.clk.now_s())

        self.assertIn("b", node_a.node.neighbors)
        self.assertIn("a", node_b.node.neighbors)

        # Node A emits an M8 open_claim event
        ev = node_a.open_claim("claim-4a", "CELL-1", 0.95)
        self.assertIsNotNone(ev)
        self.assertIsNotNone(node_a.replica.book.claims.get("claim-4a"))

        # Spin medium to propagate gossip message
        for _ in range(5):
            self.clk.advance(1000)
            node_a.tick(self.clk.now_s())
            node_b.tick(self.clk.now_s())

        # Assert event arrived in Node B's replica
        claim_b = node_b.replica.book.claims.get("claim-4a")
        self.assertIsNotNone(claim_b)
        self.assertEqual(claim_b["cell"], "CELL-1")
        self.assertEqual(claim_b["urgency"], 0.95)

        # Verify books match
        self.assertEqual(node_a.replica.book.snapshot(), node_b.replica.book.snapshot())

        print("\n--- Phase 4a Verified ---")
        print(f"Node A emitted: {ev}")
        print(f"Node B received claim: {claim_b}")
        print(f"Convergence snapshot: {node_b.replica.book.snapshot()}")

    def test_phase_4b_decision_pipeline_trace(self):
        """Phase 4b: Decision pipeline trace through M2..M10.
        Input: 'Need medic at underpass, severe bleeding', gps (100, 50).
        Verify each stage consumed the output of the previous."""
        radio_a = SimRadio("a", self.medium)
        node = PhoneNode("a", radio_a, self.fleet, now_ms=self.clk)

        raw_text = "Need medic at underpass, severe bleeding"
        pos = (100.0, 50.0)
        trace = node.run_chain(raw_text, pos, now=self.clk.now_s())

        # Stage 1: M2 parse
        self.assertIn("m2_parsed", trace)
        parsed = trace["m2_parsed"]
        self.assertIn("medic", parsed.capability_tags)
        self.assertGreater(parsed.urgency, 0.5)

        # Stage 2: WireClaim wrapping M2 parsed claim
        self.assertIn("wire_claim", trace)
        wc = trace["wire_claim"]
        self.assertEqual(wc.node, "a")
        self.assertEqual(wc.pos, pos)
        self.assertEqual(wc.parsed["capability_tags"], list(parsed.capability_tags))

        # Stage 3: M3 verify claims
        self.assertIn("m3_verdict", trace)
        verdict = trace["m3_verdict"]
        self.assertGreater(verdict.p, 0.0)
        self.assertIn("identity", verdict.parts)

        # Stage 4: M5 score trust
        self.assertIn("m5_trust", trace)
        trust = trace["m5_trust"]
        self.assertIsInstance(trust, float)

        # Stage 5: M6 battery telemetry
        self.assertIn("m6_battery", trace)
        bat = trace["m6_battery"]
        self.assertIn("rate", bat)
        self.assertIn("tts_s", bat)
        self.assertIn("urgency", bat)

        # Stage 6: M7 gate CBBA matching (consumed M3 verdict.p and M5 trust)
        self.assertIn("m7_match", trace)
        m7_match = trace["m7_match"]
        self.assertEqual(m7_match["claim"].p_valid, verdict.p)
        self.assertEqual(m7_match["claim"].trust, trust)

        # Stage 7: M9 mask privacy case (consumed WireClaim pos/radius/tags)
        self.assertIn("m9_disclosure", trace)
        disclosure = trace["m9_disclosure"]
        self.assertEqual(disclosure["case"].pos, wc.pos)

        # Stage 8: M10 UX justification & prompt (consumed M7 gate and M9 public metadata)
        self.assertIn("m10_justification", trace)
        self.assertIsNotNone(trace["m10_justification"])
        self.assertIn("m10_prompt", trace)
        prompt = trace["m10_prompt"]
        self.assertTrue(len(prompt.text) > 0)
        self.assertEqual(prompt.case_id, wc.id)

        print("\n--- Phase 4b Verified ---")
        print(f"M2 tags: {parsed.capability_tags}, urgency: {parsed.urgency}")
        print(f"M3 verdict p: {verdict.p:.3f}, top reasons: {verdict.top_reasons(2)}")
        print(f"M5 trust: {trust}")
        print(f"M6 battery: {bat}")
        print(f"M7 gate: {m7_match['gate']}")
        print(f"M9 public: {disclosure['public']}")
        print(f"M10 justification: {trace['m10_justification']}")
        print(f"M10 prompt: {trace['m10_prompt']}")

    def test_policy_interface_compliance(self):
        """Verify PhoneNode complies with 3-method policy interface (p_valid, observe, tick)."""
        radio_a = SimRadio("a", self.medium)
        node = PhoneNode("a", radio_a, self.fleet, now_ms=self.clk)

        from sim.config import Scenario
        from sim.world import World
        w = World(Scenario(seed=42))

        # Advance world until a claim is emitted
        claims = []
        while not claims and w.t < 1000:
            claims = w.emit()
            if not claims:
                w.t += 10.0

        self.assertGreater(len(claims), 0)
        c0 = claims[0]
        node.observe(c0)
        p = node.p_valid(c0)
        self.assertIsInstance(p, float)
        self.assertGreaterEqual(p, 0.0)
        self.assertLessEqual(p, 1.0)

        # Test policy tick
        dispatches = node.tick(w)
        self.assertIsInstance(dispatches, list)


if __name__ == "__main__":
    unittest.main()
