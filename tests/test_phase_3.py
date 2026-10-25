"""Phase 3: Authenticated links, replay protection, and signed CBBA auction bids."""
import json
import random
import unittest

from core import crypto
from core.types import HLC, Message, Priority, canonical
from m0_transport.ingress import IngressGuard, IngressLimits, ReplayWindow
from m0_transport.node import build_sim_node
from m0_transport.radio_adapter import SimMedium
from m1_identity.keys import dev_fleet
from m1_identity.ratelimit import IdentityLimiter
from m7_matching.cbba import Bidder, Entry, entry_digest, gossip


class TestReplayWindow(unittest.TestCase):
    def test_in_order_accepted(self):
        w = ReplayWindow(size=64)
        for seq in range(1, 10):
            ok, reason = w.check_and_add(seq)
            self.assertTrue(ok)
            self.assertEqual(reason, "ok")

    def test_replayed_frame_rejected(self):
        w = ReplayWindow(size=64)
        self.assertTrue(w.check_and_add(1)[0])
        self.assertTrue(w.check_and_add(2)[0])
        # Replaying seq 2 fails
        ok, reason = w.check_and_add(2)
        self.assertFalse(ok)
        self.assertEqual(reason, "replayed")
        # Replaying seq 1 fails
        ok, reason = w.check_and_add(1)
        self.assertFalse(ok)
        self.assertEqual(reason, "replayed")

    def test_reordered_frames_within_window_accepted(self):
        w = ReplayWindow(size=64)
        self.assertTrue(w.check_and_add(10)[0])
        # Reordered in-window frames: 8, 9, 7
        self.assertTrue(w.check_and_add(8)[0])
        self.assertTrue(w.check_and_add(9)[0])
        self.assertTrue(w.check_and_add(7)[0])
        # Replaying reordered frame 8 fails
        ok, reason = w.check_and_add(8)
        self.assertFalse(ok)
        self.assertEqual(reason, "replayed")

    def test_out_of_window_frames_rejected(self):
        w = ReplayWindow(size=64)
        self.assertTrue(w.check_and_add(100)[0])
        # Window covers (100 - 64) = 36 to 100.
        # seq 36 is diff=64 >= size -> out of window
        ok, reason = w.check_and_add(36)
        self.assertFalse(ok)
        self.assertEqual(reason, "out_of_window")
        # seq 10 is way out of window
        ok, reason = w.check_and_add(10)
        self.assertFalse(ok)
        self.assertEqual(reason, "out_of_window")
        # seq 37 is diff=63 < size -> accepted
        ok, reason = w.check_and_add(37)
        self.assertTrue(ok)

    def test_malformed_and_non_positive_sequences(self):
        w = ReplayWindow(size=64)
        for bad in (0, -1, -100, None, "1", True, False, 1.5):
            ok, reason = w.check_and_add(bad)
            self.assertFalse(ok, bad)
            self.assertEqual(reason, "invalid_seq")


class TestAuthenticatedLinks(unittest.TestCase):
    def setUp(self):
        self.reg = dev_fleet(["alice", "bob", "mallory"])

    def test_authenticated_frame_accepted(self):
        guard = IngressGuard(signer=self.reg)
        frame = {"t": "hello", "id": "alice", "snd": "alice", "seq": 1}
        body = {k: v for k, v in frame.items() if k != "sig"}
        frame["sig"] = self.reg.sign("alice", canonical(body))

        auth = guard.authenticate_frame("alice", frame)
        self.assertEqual(auth, "alice")
        self.assertEqual(guard.dropped.total(), 0)

    def test_spoofed_sender_rejected(self):
        guard = IngressGuard(signer=self.reg)
        # Mallory tries to claim frame is from alice, but signs with mallory's key
        frame = {"t": "hello", "id": "alice", "snd": "alice", "seq": 1}
        body = {k: v for k, v in frame.items() if k != "sig"}
        frame["sig"] = self.reg.sign("mallory", canonical(body))   # wrong key!

        auth = guard.authenticate_frame("alice", frame)
        self.assertIsNone(auth)
        self.assertEqual(guard.dropped["frame_bad_sig"], 1)

    def test_radio_peer_mismatch_rejected(self):
        guard = IngressGuard(signer=self.reg)
        # Radio layer saw transmission from mallory, but payload claims snd=alice
        frame = {"t": "hello", "id": "alice", "snd": "alice", "seq": 1}
        body = {k: v for k, v in frame.items() if k != "sig"}
        frame["sig"] = self.reg.sign("alice", canonical(body))

        auth = guard.authenticate_frame("mallory", frame)   # radio peer mismatch!
        self.assertIsNone(auth)
        self.assertEqual(guard.dropped["frame_spoofed_sender"], 1)

    def test_replayed_frame_dropped_by_guard(self):
        guard = IngressGuard(signer=self.reg)
        frame = {"t": "hello", "id": "alice", "snd": "alice", "seq": 1}
        body = {k: v for k, v in frame.items() if k != "sig"}
        frame["sig"] = self.reg.sign("alice", canonical(body))

        # First delivery accepted
        self.assertEqual(guard.authenticate_frame("alice", frame), "alice")
        # Replay dropped
        self.assertIsNone(guard.authenticate_frame("alice", frame))
        self.assertEqual(guard.dropped["frame_replayed"], 1)

    def test_rate_limiter_keys_on_authenticated_identity(self):
        # Limiter gives alice budget, but mallory has 0 budget
        lim = IdentityLimiter(self.reg, base_rate=0.0, base_burst=2.0)
        # Alice gets 2 tokens
        self.assertEqual(lim.budget("alice", 0.0)[1], 2.0)
        # Mallory tries to send packet claiming to be alice on wire without valid signature
        guard = IngressGuard(limiter=lim, signer=self.reg)
        raw_msg = Message("m9.case", Priority.DISTRESS, "alice", {"x": 1}, HLC(1, 1, "alice"), ttl_s=3600.0)
        fake_frame = {"t": "data", "m": raw_msg.to_wire(), "tok": None, "snd": "alice", "seq": 1, "sig": "invalid"}

        auth = guard.authenticate_frame("alice", fake_frame)
        self.assertIsNone(auth)
        # Alice's token budget was NOT consumed by the spoof attempt!
        self.assertTrue(lim.allow("alice", 0.0))
        self.assertTrue(lim.allow("alice", 0.0))
        self.assertFalse(lim.allow("alice", 0.0))   # only now exhausted


class TestSignedCBBA(unittest.TestCase):
    def setUp(self):
        self.reg = dev_fleet(["a", "b", "mallory"])

    def test_signed_entries_converge(self):
        util = {"a": {"x": 0.9, "y": 0.5}, "b": {"x": 0.8, "y": 0.6}}
        ba = Bidder("a", util["a"], signer=self.reg)
        bb = Bidder("b", util["b"], signer=self.reg)
        # Both bidders sign entries
        self.assertIsNotNone(ba.table["a"].sig)
        self.assertIsNotNone(bb.table["b"].sig)

        bidders = {"a": ba, "b": bb}
        rounds = gossip(bidders, [("a", "b")])
        self.assertGreater(rounds, 0)
        self.assertEqual(ba.assignment(), {"x": "a", "y": "b"})
        self.assertEqual(bb.assignment(), {"x": "a", "y": "b"})

    def test_forged_high_epoch_entry_rejected(self):
        util = {"a": {"x": 0.9}}
        ba = Bidder("a", util["a"], signer=self.reg)
        # Initial state: a has epoch 0 for self
        self.assertEqual(ba.table["a"].epoch, 0)

        # Mallory crafts a forged entry claiming to be agent 'b' with epoch 9999 and huge bid,
        # but signed by mallory or missing valid signature
        fake_for_b = Entry(epoch=9999, task="x", bid=100.0, sig="bogus_sig")
        # Try to merge into a
        changed = ba.merge({"b": fake_for_b})
        self.assertFalse(changed)
        self.assertNotIn("b", ba.table)

        # Mallory crafts a forged entry claiming to be agent 'a' (author spoofing)
        fake_for_a = Entry(epoch=9999, task="x", bid=100.0,
                           sig=self.reg.sign("mallory", entry_digest("a", 9999, "x", 100.0)))
        changed = ba.merge({"a": fake_for_a})
        self.assertFalse(changed)
        self.assertEqual(ba.table["a"].epoch, 0)  # untainted

    def test_valid_entry_merged_successfully(self):
        util = {"a": {"x": 0.9}, "b": {"y": 0.8}}
        ba = Bidder("a", util["a"], signer=self.reg)
        bb = Bidder("b", util["b"], signer=self.reg)

        # Valid entry from b merged into a
        changed = ba.merge(bb.table)
        self.assertTrue(changed)
        self.assertIn("b", ba.table)
        self.assertEqual(ba.table["b"].task, None)


class TestCryptoProviderIntegrity(unittest.TestCase):
    def test_dev_provider_is_still_the_only_one_used(self):
        provider = crypto.get_provider()
        self.assertEqual(provider.name, "dev-hmac-sha256")
        self.assertIsInstance(provider, crypto.DevProvider)


if __name__ == "__main__":
    unittest.main()
