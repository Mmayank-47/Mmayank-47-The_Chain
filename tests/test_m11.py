import unittest

from m11_redteam.attack_suite import run_all
from m11_redteam.defenses import (Dedupe, RateLimiter, SensorVeto, TokenBucket, sign_credential,
                                  verify_credential)


class TestDefenses(unittest.TestCase):
    def test_bucket_burst_then_refill(self):
        b = TokenBucket(rate=1.0, burst=3)
        self.assertEqual([b.allow(0.0) for _ in range(4)], [True, True, True, False])
        self.assertTrue(b.allow(2.0))

    def test_rate_limiter_isolates_keys_and_caps_key_count(self):
        r = RateLimiter(rate=0.0, burst=1, max_keys=2)
        self.assertTrue(r.allow("a", 0)); self.assertFalse(r.allow("a", 1))
        self.assertTrue(r.allow("b", 0))
        self.assertFalse(r.allow("c", 0))

    def test_dedupe_ttl(self):
        d = Dedupe(ttl=10)
        self.assertTrue(d.first(b"x", 0)); self.assertFalse(d.first(b"x", 5)); self.assertTrue(d.first(b"x", 20))

    def test_credentials(self):
        k = b"k"
        sig = sign_credential(k, 1, "medic")
        self.assertTrue(verify_credential(k, 1, "medic", sig))
        self.assertFalse(verify_credential(k, 2, "medic", sig))
        self.assertFalse(verify_credential(k, 1, "nurse", sig))
        self.assertFalse(verify_credential(k, 1, "medic", None))
        self.assertFalse(verify_credential(k, 1, "medic", sig, {(1, "medic")}))
        self.assertFalse(verify_credential(b"other", 1, "medic", sig))

    def test_sensor_veto(self):
        v = SensorVeto()
        self.assertTrue(v.veto(0.5)); self.assertFalse(v.veto(0.0)); self.assertFalse(v.veto(None))
        self.assertTrue(v.abort(None, 0.9)); self.assertFalse(v.abort(None, 0.2))


class TestSuite(unittest.TestCase):
    def test_every_attack_defended_and_succeeds_without_defense(self):
        for r in run_all():
            with self.subTest(r.name):
                self.assertGreater(r.undefended, r.defended, r)
                self.assertLessEqual(r.defended, r.max_ok, r)
                self.assertTrue(r.ok)


if __name__ == "__main__":
    unittest.main()
