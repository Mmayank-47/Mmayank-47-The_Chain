import json
import random
import unittest

from core.types import HLC
from m9_privacy.cover import CoverTraffic
from m9_privacy.disclosure import (Case, Discloser, cell_of, public_message, sealed_message)
from m9_privacy.pseudonyms import PseudonymChain
from m9_privacy.sealed_envelope import PACKET, BoxKey, open_box, seal


def case(**kw):
    d = dict(case_id="c1", pos=(1234.5, 987.6), radius_m=30.0, tags=("insulin",), features=("medication_dependent",),
             location_text="Shivaji Nagar", quantity=2, chain=PseudonymChain(b"seed"))
    d.update(kw)
    return Case(**d)


class TestPseudonyms(unittest.TestCase):
    def test_rotate_unlinkable_and_ownership(self):
        p = PseudonymChain(b"s", epoch_s=100)
        self.assertEqual(p.pid(5), p.pid(99))
        self.assertNotEqual(p.pid(5), p.pid(105))
        self.assertNotEqual(p.ble_addr(5), p.ble_addr(105))
        self.assertTrue(p.owns(p.pid(5), 150))
        self.assertFalse(p.owns(p.pid(5), 450))
        self.assertFalse(PseudonymChain(b"other", 100).owns(p.pid(5), 5))

    def test_ble_address_is_locally_administered_unicast(self):
        b = int(PseudonymChain(b"s").ble_addr(0).split(":")[0], 16)
        self.assertEqual((b & 0x03), 0x02)


class TestEnvelope(unittest.TestCase):
    def test_roundtrip_wrong_key_tamper_and_fixed_size(self):
        k, other = BoxKey(), BoxKey()
        small, big = seal(k.public(), "a", {"x": 1}), seal(k.public(), "a", {"x": "y" * 300})
        self.assertEqual((len(small), len(big)), (PACKET, PACKET))
        self.assertEqual(k.open(small), ("a", {"x": 1}, None))
        self.assertIsNone(other.open(small))
        bad = bytearray(small); bad[40] ^= 1
        self.assertIsNone(k.open(bytes(bad)))
        self.assertIsNone(k.open(small[:-1]))
        self.assertIsNone(k.open(None))

    def test_oversize_rejected_and_ciphertext_hides_plaintext(self):
        k = BoxKey()
        with self.assertRaises(ValueError):
            seal(k.public(), "a", {"x": "y" * 600})
        self.assertNotIn(b"Shivaji", seal(k.public(), "a", {"loc": "Shivaji Nagar"}))

    def test_nonce_changes_ciphertext(self):
        k = BoxKey()
        self.assertNotEqual(seal(k.public(), "a", {"x": 1}), seal(k.public(), "a", {"x": 1}))


class TestDisclosure(unittest.TestCase):
    def test_public_view_is_coarse_and_generalised(self):
        c = case()
        pub = c.public()
        self.assertEqual(pub["cell"], cell_of((1234.5, 987.6)))
        self.assertEqual((pub["cat"], pub["tag"], pub["need"]), ("medical", "medicine", None))
        m = public_message(c, c.chain.pid(0), HLC(1, 0, "x"), 0.0)
        wire = m.wire_bytes().decode()
        for secret in ("1234", "987", "Shivaji", "medication", "insulin", "seed"):
            self.assertNotIn(secret, wire)
        self.assertEqual(m.dest_cell, pub["cell"])

    def test_critical_features_need_medic(self):
        self.assertEqual(case(tags=(), features=("not_breathing",)).public()["need"], "medic")
        self.assertEqual(case(tags=("boat",), features=("trapped",)).public()["cat"], "rescue")
        self.assertEqual(case(tags=("xyz",), features=()).public()["cat"], "other")

    def test_details_only_after_accept_and_only_to_that_responder(self):
        r1, r2 = BoxKey(), BoxKey()
        d = Discloser(case(), {"r1": r1.public(), "r2": r2.public()})
        with self.assertRaises(PermissionError):
            d.release("r1", 0.0)
        self.assertFalse(d.accept("stranger"))
        self.assertTrue(d.accept("r1"))
        blob = d.release("r1", 0.0)
        sender, payload, _ = r1.open(blob)
        self.assertEqual(payload["pos"], [1234.5, 987.6])
        self.assertEqual(payload["feat"], ["medication_dependent"])
        self.assertEqual(sender, payload["pid"])
        self.assertIsNone(r2.open(blob))
        self.assertEqual(d.release("r1", 50.0), blob)           # same blob, no extra exposure

    def test_release_cap_blocks_location_harvesting(self):
        keys = {f"r{i}": BoxKey().public() for i in range(5)}
        d = Discloser(case(), keys, max_responders=2)
        self.assertEqual([d.accept(f"r{i}") for i in range(4)], [True, True, False, False])
        self.assertTrue(d.accept("r0"))
        with self.assertRaises(PermissionError):
            d.release("r3", 0.0)

    def test_signature_travels_inside_and_sealed_message_is_opaque(self):
        k = BoxKey()
        c = case()
        d = Discloser(c, {"r": k.public()})
        d.accept("r")
        blob = d.release("r", 0.0, sign=lambda b: "sig:" + str(len(b)))
        self.assertTrue(k.open(blob)[2].startswith("sig:"))
        m = sealed_message(blob, "pidx", c.cell, HLC(1, 0, "x"), 0.0)
        self.assertEqual(bytes.fromhex(m.payload["b"]), blob)
        self.assertEqual(m.dest_cell, c.cell)


class TestCover(unittest.TestCase):
    def test_budget_and_real_traffic_suppresses_dummies(self):
        ct = CoverTraffic(rate_Bps=PACKET, rng=random.Random(1))
        self.assertEqual(ct.step(0.0), [])
        out = ct.step(2.0)
        self.assertEqual(len(out), 2)
        self.assertTrue(all(len(b) == PACKET for b in out))
        self.assertIsNone(BoxKey().open(out[0]))
        busy = CoverTraffic(rate_Bps=PACKET, rng=random.Random(1))
        busy.step(0.0)
        self.assertEqual(busy.step(2.0, real_sent_bytes=2 * PACKET), [])

    def test_never_exceeds_burst_or_cap(self):
        ct = CoverTraffic(rate_Bps=10 ** 6, max_per_step=3, rng=random.Random(2))
        ct.step(0.0)
        self.assertEqual(len(ct.step(100.0)), 3)


if __name__ == "__main__":
    unittest.main()
