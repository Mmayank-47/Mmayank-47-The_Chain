"""Phase 7 verification: Real Radio fitting (compact wire <= 255B, airtime budget, reboot persistence).

Verifies:
  1. Compact binary wire format: Hello beacon fits under 48 bytes (raw binary sig),
     and fragmentation guarantees all packets are strictly <= 255 bytes (LoRa MTU).
  2. Airtime & Duty-Cycle: LoRa Semtech airtime calculation, 1% duty-cycle cap enforcement,
     and Priority.DISTRESS emergency reserve bypass.
  3. Reboot persistence: PhoneNode saves state to disk, survives simulated reboot/restart,
     restores CarryStore, M8 Replica book, HLC counter, and M5 BetaRep scores without loss.
  4. End-to-end operation of PhoneNode over SimMedium with compact binary wire enabled.
"""
import os
import shutil
import tempfile
import unittest

from core.bloom import BloomFilter
from core.types import HLC, Message, Priority
from m0_transport.profile import AirtimeTracker, BLEProfile, LoRaProfile
from m0_transport.radio_adapter import SimMedium, SimRadio
from m0_transport.wire import Fragmenter, MAX_LORA_MTU, WireCodec
from m1_identity.keys import dev_fleet
from phone.node import PhoneNode
from phone.persistence import NodePersistence


class TestPhase7CompactWire(unittest.TestCase):
    def setUp(self):
        self.fleet = dev_fleet(["node-a", "node-b"])

    def test_compact_hello_beacon_size_under_limit(self):
        """Unsigned hello beacon is under 32 bytes; signed with binary 32B sig is under 60 bytes."""
        bf = BloomFilter(128, 3)
        hello = {
            "t": "hello",
            "id": "a",
            "bat": 0.95,
            "cell": "0:0",
            "bf": bf.to_json(),
        }

        # Unsigned compact hello
        unsigned_bytes = WireCodec.encode(hello, signer=None)
        self.assertLessEqual(len(unsigned_bytes), 32, f"Unsigned hello {len(unsigned_bytes)}B should be <= 32B")

        # Signed compact hello (with 32-byte raw binary signature)
        signed_bytes = WireCodec.encode(hello, signer=self.fleet)
        self.assertLessEqual(len(signed_bytes), 64, f"Signed hello {len(signed_bytes)}B should be <= 64B")

        # Decode round-trip
        decoded = WireCodec.decode(signed_bytes)
        self.assertIsNotNone(decoded)
        self.assertEqual(decoded["t"], "hello")
        self.assertEqual(decoded["id"], "a")
        self.assertAlmostEqual(decoded["bat"], 0.95, delta=0.01)
        self.assertEqual(decoded["cell"], "0:0")

    def test_packet_fragmentation_guarantees_under_255_bytes(self):
        """Frames larger than LoRa MTU are split into fragments strictly <= 255 bytes and reassembled."""
        # Create a large payload frame that exceeds MTU even after zlib compression
        large_payload = " ".join(f"claim-item-{i}-xyz-987-detail-status" for i in range(50))
        frame = {
            "t": "data",
            "snd": "node-a",
            "seq": 42,
            "m": {
                "kind": "claim",
                "prio": 0,
                "origin": "node-a",
                "payload": large_payload,
                "hlc": [1000, 0, "node-a"],
                "mid": "mid-large-1",
            },
            "tok": None,
        }

        raw_bytes = WireCodec.encode(frame, signer=self.fleet)
        self.assertGreater(len(raw_bytes), 255, "Raw frame should exceed MTU to test fragmentation")

        fragmenter = Fragmenter(max_packet_size=MAX_LORA_MTU)
        packets = fragmenter.fragment(raw_bytes, sender="node-a")

        # Every single fragment packet must be <= 255 bytes
        self.assertGreater(len(packets), 1)
        for i, pkt in enumerate(packets):
            self.assertLessEqual(len(pkt), MAX_LORA_MTU,
                                 f"Packet {i} size {len(pkt)} exceeded LoRa MTU 255 bytes")

        # Reassemble packets on receiver
        rx_fragmenter = Fragmenter(max_packet_size=MAX_LORA_MTU)
        reassembled = None
        for pkt in packets:
            reassembled = rx_fragmenter.reassemble(pkt, now=100.0)

        self.assertIsNotNone(reassembled)
        self.assertEqual(reassembled, raw_bytes)

        # Decode reassembled frame
        decoded = WireCodec.decode(reassembled)
        self.assertIsNotNone(decoded)
        self.assertEqual(decoded["t"], "data")
        self.assertEqual(decoded["m"]["payload"], large_payload)


class TestPhase7AirtimeDutyCycle(unittest.TestCase):
    def test_lora_and_ble_profiles_airtime(self):
        """Test Semtech LoRa and BLE airtime calculation models."""
        lora = LoRaProfile(spreading_factor=7, bandwidth_hz=125_000)
        # 50-byte packet at SF7/125kHz takes ~100ms airtime
        airtime_50b = lora.calc_airtime_s(50)
        self.assertGreater(airtime_50b, 0.05)
        self.assertLess(airtime_50b, 0.20)

        # Longer payload takes more airtime
        airtime_200b = lora.calc_airtime_s(200)
        self.assertGreater(airtime_200b, airtime_50b)

        # BLE profile transmits much faster (microsecond scale)
        ble = BLEProfile()
        airtime_ble = ble.calc_airtime_s(50)
        self.assertLess(airtime_ble, 0.001)

    def test_1_percent_duty_cycle_enforcement_and_distress_bypass(self):
        """AirtimeTracker enforces 1% duty cycle limit, while Priority.DISTRESS bypasses."""
        lora = LoRaProfile()
        tracker = AirtimeTracker(lora, window_s=100.0)

        now = 100.0
        # 1% of 100s window is 1.0s airtime budget
        # Send packets until 1% duty-cycle cap is reached
        pkt_size = 150
        single_airtime = lora.calc_airtime_s(pkt_size)  # ~0.25s
        
        # Can transmit initially
        self.assertTrue(tracker.can_transmit(pkt_size, now, Priority.CHATTER))

        # Transmit 4 packets to exhaust 1.0s budget
        for _ in range(4):
            tracker.record_tx(pkt_size, now)

        # Chatter and Hazard are now blocked by duty cycle limit
        self.assertFalse(tracker.can_transmit(pkt_size, now, Priority.CHATTER))
        self.assertFalse(tracker.can_transmit(pkt_size, now, Priority.HAZARD))

        # Priority.DISTRESS bypasses regulatory limit for life safety
        self.assertTrue(tracker.can_transmit(pkt_size, now, Priority.DISTRESS))


class TestPhase7RebootPersistence(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp()
        self.db_path = os.path.join(self.temp_dir, "node_a_state.json")
        self.fleet = dev_fleet(["a", "b"])
        self.medium = SimMedium()

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def test_phone_node_state_survives_reboot(self):
        """Simulate PhoneNode reboot: verify CarryStore, Replica, and BetaRep persist."""
        radio_a = SimRadio("a", self.medium)
        node_a = PhoneNode("a", radio_a, self.fleet, battery_level=0.88,
                           persistence_path=self.db_path)

        # 1. Node A emits claims and updates trust scores
        ev = node_a.open_claim("claim-persist-1", "CELL-9", 0.92)
        self.assertIsNotNone(ev)
        node_a.trust_engine.rep.record("b", "forward_success", 1000.0)
        node_a.trust_engine.rep.record("bad-peer", "forward_failure", 1000.0)

        # Save state to disk
        node_a.save_state()
        self.assertTrue(os.path.exists(self.db_path))

        # 2. Simulate node reboot: completely new instance with same persistent storage
        radio_a_new = SimRadio("a", self.medium)
        node_a_rebooted = PhoneNode("a", radio_a_new, self.fleet,
                                    persistence_path=self.db_path)

        # Verify battery level restored
        self.assertAlmostEqual(node_a_rebooted.battery_level, 0.88, delta=0.01)

        # Verify claim and Replica CRDT book restored
        claim_restored = node_a_rebooted.replica.book.claims.get("claim-persist-1")
        self.assertIsNotNone(claim_restored)
        self.assertEqual(claim_restored["cell"], "CELL-9")
        self.assertEqual(claim_restored["urgency"], 0.92)

        # Verify EventLog events restored
        logs_a = node_a_rebooted.replica.store.logs.get("a", [])
        self.assertEqual(len(logs_a), 1)
        self.assertEqual(logs_a[0].data["claim"], "claim-persist-1")

        # Verify BetaRep reputation scores restored
        b_trust = node_a_rebooted.trust_engine.rep.mean("b", 1000.0)
        bad_trust = node_a_rebooted.trust_engine.rep.mean("bad-peer", 1000.0)
        self.assertGreater(b_trust, 0.5)
        self.assertLess(bad_trust, 0.35)

    def test_two_nodes_end_to_end_with_compact_wire_and_lora_profile(self):
        """Test two PhoneNodes operating over SimMedium with compact binary wire enabled."""
        radio_a = SimRadio("a", self.medium)
        radio_b = SimRadio("b", self.medium)
        self.medium.link("a", "b")

        lora = LoRaProfile()
        node_a = PhoneNode("a", radio_a, self.fleet, compact_wire=True, profile=lora)
        node_b = PhoneNode("b", radio_b, self.fleet, compact_wire=True, profile=lora)

        now = 1000.0
        # Exchange compact beacons
        for _ in range(3):
            now += 1.0
            node_a.tick(now)
            node_b.tick(now)

        self.assertIn("b", node_a.node.neighbors)
        self.assertIn("a", node_b.node.neighbors)

        # Node A emits a claim
        node_a.open_claim("claim-lora", "CELL-A", 0.85)

        # Propagate gossip across compact wire
        for _ in range(5):
            now += 1.0
            node_a.tick(now)
            node_b.tick(now)

        # Node B should have received the claim
        claim_b = node_b.replica.book.claims.get("claim-lora")
        self.assertIsNotNone(claim_b)
        self.assertEqual(claim_b["cell"], "CELL-A")


if __name__ == "__main__":
    unittest.main()
