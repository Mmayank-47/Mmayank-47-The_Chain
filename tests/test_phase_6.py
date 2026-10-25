"""Phase 6 verification: Forwarding evidence, watchdog, trust penalties, and multipath failover.

Verifies:
  1. Watchdog tracks pending forwards and records 'forward_success' when forward observed.
  2. Watchdog deadline expiration penalizes relay via M5 'forward_failure' and marks it untrusted.
  3. GossipEngine reroutes unverified messages to alternative trusted neighbors upon watchdog deadline.
  4. End-to-end blackhole/greyhole defense achieves >= 30% improvement over baseline without regressing clean scenarios.
"""
import unittest

from m0_transport.gossip import GossipEngine, NeighborInfo, Transmission
from m0_transport.radio_adapter import SimMedium, SimRadio
from m0_transport.watchdog import Watchdog
from m1_identity.keys import dev_fleet
from m5_trust.beta_rep import BetaRep
from phone.node import PhoneNode
from core.types import Message, Priority
from sim.mesh_sim import run_scenario


class Clock:
    def __init__(self, start_s: float = 1000.0):
        self.t = start_s

    def now(self) -> float:
        return self.t

    def advance(self, s: float = 1.0):
        self.t += s


class TestPhase6Watchdog(unittest.TestCase):
    def setUp(self):
        self.clk = Clock()
        self.rep = BetaRep()
        self.watchdog = Watchdog(self.rep, ack_timeout_s=6.0, trust_threshold=0.35)

    def test_forward_success_records_evidence(self):
        """Observing a forwarded message removes it from pending and increases trust."""
        self.watchdog.track("relay-1", "msg-123", self.clk.now())
        self.assertIn(("relay-1", "msg-123"), self.watchdog._pending)

        # Before evidence, default mean trust is 0.5
        self.assertAlmostEqual(self.rep.mean("relay-1", self.clk.now()), 0.5)

        # Observe forward
        self.clk.advance(2.0)
        observed = self.watchdog.observe_forward("relay-1", "msg-123", self.clk.now())
        self.assertTrue(observed)
        self.assertNotIn(("relay-1", "msg-123"), self.watchdog._pending)

        # Trust should have increased after forward_success (alpha increases)
        self.assertGreater(self.rep.mean("relay-1", self.clk.now()), 0.5)

    def test_forward_failure_penalizes_and_marks_untrusted(self):
        """When deadline expires without forward, watchdog penalizes relay below threshold."""
        self.watchdog.track("bad-relay", "msg-999", self.clk.now())

        # Advance past deadline
        self.clk.advance(7.0)
        failed = self.watchdog.tick(self.clk.now())

        self.assertEqual(len(failed), 1)
        self.assertEqual(failed[0][0], "bad-relay")
        self.assertEqual(failed[0][1], "msg-999")

        # Relay should be penalized and marked untrusted
        self.assertFalse(self.watchdog.is_trusted("bad-relay", self.clk.now()))
        self.assertLess(self.rep.mean("bad-relay", self.clk.now()), 0.35)

    def test_gossip_multipath_failover_on_watchdog_timeout(self):
        """GossipEngine reroutes failed message to alternative trusted neighbor when watchdog expires."""
        from core.types import HLC
        engine = GossipEngine("sender", watchdog=self.watchdog, base_fanout=1)
        hlc = HLC(int(self.clk.now() * 1000), 0, "sender")
        msg = Message("test", Priority.CHATTER, "sender", "critical payload", hlc, created=self.clk.now())
        engine.originate(msg, self.clk.now())

        # Neighbors: bad relay (battery 1.0) and good backup relay (battery 0.7)
        neighbors = {
            "bad-relay": NeighborInfo("bad-relay", battery=1.0),
            "good-relay": NeighborInfo("good-relay", battery=0.7),
        }

        # Step 1: engine selects bad-relay first due to higher battery
        out1 = engine.step(neighbors, self.clk.now())
        sent_peers = [t.to for t in out1]
        self.assertEqual(sent_peers, ["bad-relay"])

        # Advance time to expire bad-relay's forward deadline
        self.clk.advance(7.0)

        # Step 2: engine ticks watchdog, detects drop, penalizes bad-relay, pushes to good-relay
        out2 = engine.step(neighbors, self.clk.now())
        sent_peers2 = [t.to for t in out2]
        self.assertIn("good-relay", sent_peers2)

    def test_phase_6_blackhole_and_greyhole_improvement(self):
        """Simulate blackhole and greyhole attacks:
        Assert defended (watchdog ON) improves delivery ratio by >= 30% over baseline (watchdog OFF),
        and clean scenario has no regression."""
        # 1. Clean scenario regression check
        clean_res = run_scenario("clean", runs=5, enable_watchdog=True)
        self.assertGreaterEqual(clean_res["mean_delivery_ratio"], 0.90)

        # 2. Blackhole defense: baseline vs defended
        bh_baseline = run_scenario("blackhole", runs=5, enable_watchdog=False)
        bh_defended = run_scenario("blackhole", runs=5, enable_watchdog=True)

        improvement_bh = bh_defended["mean_delivery_ratio"] - bh_baseline["mean_delivery_ratio"]
        self.assertGreaterEqual(improvement_bh, 0.30,
                                f"Blackhole improvement {improvement_bh:.2f} must be >= 0.30")
        self.assertGreaterEqual(bh_defended["mean_delivery_ratio"], 0.85)

        # 3. Greyhole defense: baseline vs defended
        gh_baseline = run_scenario("greyhole", runs=5, enable_watchdog=False)
        gh_defended = run_scenario("greyhole", runs=5, enable_watchdog=True)

        improvement_gh = gh_defended["mean_delivery_ratio"] - gh_baseline["mean_delivery_ratio"]
        self.assertGreaterEqual(improvement_gh, 0.30,
                                f"Greyhole improvement {improvement_gh:.2f} must be >= 0.30")
        self.assertGreaterEqual(gh_defended["mean_delivery_ratio"], 0.85)

        print("\n--- Phase 6 Defense Numbers Verified ---")
        print(f"Clean scenario delivery ratio: {clean_res['mean_delivery_ratio']:.2f}")
        print(f"Blackhole delivery ratio: Baseline={bh_baseline['mean_delivery_ratio']:.2f} -> Defended={bh_defended['mean_delivery_ratio']:.2f} (+{improvement_bh*100:.1f}%)")
        print(f"Greyhole delivery ratio: Baseline={gh_baseline['mean_delivery_ratio']:.2f} -> Defended={gh_defended['mean_delivery_ratio']:.2f} (+{improvement_gh*100:.1f}%)")


if __name__ == "__main__":
    unittest.main()
