import unittest
from dataclasses import dataclass

from m3_verify import challenge as ch
from m3_verify.p_valid import Verifier

_ids = iter(range(10 ** 6))


@dataclass
class C:
    node: int
    src: int
    pos: tuple
    t: float
    id: int = 0
    urgency: float = 0.5
    need: str = None
    battery: float = 0.5

    def __post_init__(self):
        self.id = next(_ids)


def honest(node, pos=(500.0, 500.0), t=0.0):
    return C(node, node, pos, t)


class TestSignals(unittest.TestCase):
    def test_lone_honest_claim_clears_release(self):
        v = Verifier()
        self.assertGreaterEqual(v.p_valid(honest(1)), 0.6)

    def test_spoofed_identity_rejected(self):
        v = Verifier()
        self.assertLess(v.p_valid(C(node=9, src=1, pos=(0.0, 0.0), t=0.0)), 0.1)

    def test_sybil_ids_from_one_device_rejected_and_cannot_corroborate(self):
        v = Verifier()
        cs = [C(7, 10_000 + j, (800.0, 800.0), 5.0) for j in range(10)]
        for c in cs:
            v.observe(c)
        self.assertTrue(all(v.p_valid(c) < 0.1 for c in cs))
        self.assertEqual(v.score(cs[0]).parts["corroboration"], 0.0)

    def test_one_device_many_places_penalised_after_first(self):
        v = Verifier()
        cs = [C(5, 5, (300.0 * i + 300, 400.0), 10.0 * i) for i in range(4)]
        for c in cs:
            v.observe(c)
        ps = [v.p_valid(c) for c in cs]
        self.assertGreaterEqual(ps[0], 0.6)           # first claim indistinguishable from honest
        self.assertTrue(all(p < ps[0] for p in ps[1:]))
        self.assertLess(ps[-1], 0.2)

    def test_independent_corroboration_raises_score(self):
        v = Verifier()
        a = honest(1, (500.0, 500.0), 0.0)
        base = v.p_valid(a)
        v.observe(honest(2, (540.0, 520.0), 30.0))
        v.observe(honest(3, (480.0, 470.0), 60.0))
        self.assertGreater(v.p_valid(a), base)

    def test_staleness_lowers_but_is_capped(self):
        v = Verifier()
        c = honest(1)
        fresh, old, ancient = v.p_valid(c, 0.0), v.p_valid(c, 1800.0), v.p_valid(c, 10 ** 6)
        self.assertGreater(fresh, old)
        self.assertEqual(old, ancient)

    def test_credential_hook(self):
        good = Verifier(credential=lambda c: True).p_valid(honest(1))
        bad = Verifier(credential=lambda c: False).p_valid(honest(1))
        self.assertGreater(good, 0.9)
        self.assertLess(bad, 0.2)

    def test_explanation_lists_biggest_reason(self):
        v = Verifier()
        d = v.score(C(9, 1, (0.0, 0.0), 0.0))
        self.assertEqual(d.top_reasons(1)[0][0], "identity")

    def test_order_independent(self):
        a, b = honest(1, (500.0, 500.0), 0.0), honest(2, (520.0, 500.0), 20.0)
        v1, v2 = Verifier(), Verifier()
        v1.observe(a); v1.observe(b); v2.observe(b); v2.observe(a)
        self.assertAlmostEqual(v1.p_valid(a, 100.0), v2.p_valid(a, 100.0))


class TestChallenge(unittest.TestCase):
    def setUp(self):
        self.c = ch.make_challenge(claim_id=1, claimant=4, now=0.0, nonce="n1")
        self.known = {5, 6, 7}

    def resp(self, **kw):
        d = dict(cid=self.c.cid, claimant=4, baro_hpa=1004.0, neighbors=frozenset({5, 6}), sent=30.0)
        d.update(kw)
        return ch.Response(**d)

    def test_pass(self):
        self.assertEqual(ch.check_response(self.c, self.resp(), 40.0, 1005.0, self.known), ch.PASS)

    def test_wrong_pressure_or_invented_neighbours_fail(self):
        self.assertEqual(ch.check_response(self.c, self.resp(baro_hpa=990.0), 40.0, 1005.0, self.known), ch.FAIL)
        self.assertEqual(ch.check_response(self.c, self.resp(neighbors=frozenset({91, 92})), 40.0, 1005.0, self.known), ch.FAIL)
        self.assertEqual(ch.check_response(self.c, self.resp(neighbors=frozenset()), 40.0, 1005.0, self.known), ch.FAIL)

    def test_replay_or_relay_rejected(self):
        other = ch.make_challenge(1, 4, 0.0, "n2")
        self.assertEqual(ch.check_response(other, self.resp(), 40.0, 1005.0, self.known), ch.MISMATCH)
        self.assertEqual(ch.check_response(self.c, self.resp(claimant=8), 40.0, 1005.0, self.known), ch.MISMATCH)

    def test_silence_pending_then_weak_negative(self):
        self.assertEqual(ch.check_response(self.c, None, 60.0, 1005.0, self.known), 0.0)
        self.assertEqual(ch.check_response(self.c, None, 500.0, 1005.0, self.known), ch.LATE)
        self.assertGreater(ch.LATE, ch.FAIL)

    def test_challenge_result_moves_score(self):
        v = Verifier()
        c = honest(4)
        base = v.p_valid(c)
        v.record_challenge(c.id, ch.PASS)
        self.assertGreater(v.p_valid(c), base)
        v.record_challenge(c.id, ch.FAIL)
        self.assertLess(v.p_valid(c), base)


if __name__ == "__main__":
    unittest.main()
