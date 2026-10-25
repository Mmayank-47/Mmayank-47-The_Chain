import unittest
from dataclasses import dataclass

from m1_identity.copresence import CoPresence, Encounter
from m1_identity.credentials import Authority, CredentialVerifier
from m1_identity.keys import AttestationRoot, Keystore, Registry
from m1_identity.ratelimit import IdentityLimiter
from m1_identity.revocation import Revocation, RevocationList

ROOT = AttestationRoot(b"maker-root")


def fleet(*names):
    reg = Registry(ROOT)
    for n in names:
        ks = Keystore()
        assert reg.enroll(n, ks, ROOT.attest(ks.key_id))
    return reg


@dataclass
class C:
    node: str
    t: float = 0.0
    need: str = "medic"


class TestKeys(unittest.TestCase):
    def test_sign_verify_and_wrong_node(self):
        reg = fleet("a", "b")
        sig = reg.sign("a", b"x")
        self.assertTrue(reg.verify("a", b"x", sig))
        self.assertFalse(reg.verify("b", b"x", sig))
        self.assertFalse(reg.verify("a", b"y", sig))
        self.assertFalse(reg.verify("ghost", b"x", sig))
        self.assertFalse(reg.verify("a", b"x", None))

    def test_unattested_or_forged_attestation_rejected(self):
        reg = Registry(ROOT)
        ks = Keystore()
        self.assertFalse(reg.enroll("a", ks, None))
        self.assertFalse(reg.enroll("a", ks, "f" * 64))
        self.assertFalse(reg.enroll("a", ks, AttestationRoot(b"other").attest(ks.key_id)))
        self.assertFalse(reg.enrolled("a"))

    def test_one_hardware_key_one_identity(self):
        reg = Registry(ROOT)
        ks = Keystore()
        att = ROOT.attest(ks.key_id)
        self.assertTrue(reg.enroll("a", ks, att))
        self.assertTrue(reg.enroll("a", ks, att))            # idempotent
        self.assertFalse(reg.enroll("sybil", ks, att))       # same phone, second identity
        ks2 = Keystore()
        self.assertFalse(reg.enroll("a", ks2, ROOT.attest(ks2.key_id)))   # identity cannot hop keys

    def test_forty_identities_one_phone_only_one_enrolls(self):
        reg = Registry(ROOT)
        ks = Keystore()
        att = ROOT.attest(ks.key_id)
        self.assertEqual(sum(reg.enroll(f"n{i}", ks, att) for i in range(40)), 1)


class TestCredentials(unittest.TestCase):
    def setUp(self):
        self.council = Authority("council", b"k1", {"medic", "nurse"})
        self.fire = Authority("fire", b"k2", {"rescuer"})
        roots = {a.name: a.root() for a in (self.council, self.fire)}
        self.v = CredentialVerifier(roots)

    def test_valid_expired_tampered(self):
        c = self.council.issue("a", "medic", 100.0)
        self.assertTrue(self.v.verify(c, 50.0))
        self.assertFalse(self.v.verify(c, 101.0))
        self.assertFalse(self.v.verify(type(c)("b", c.role, c.issuer, c.expires, c.sig), 50.0))
        self.assertFalse(self.v.verify(None, 0.0))

    def test_authority_scope_and_unknown_issuer(self):
        with self.assertRaises(ValueError):
            self.fire.issue("a", "medic", 100.0)
        rogue = Authority("rogue", b"k3", {"medic"})
        self.assertFalse(self.v.verify(rogue.issue("a", "medic", 100.0), 0.0))
        liar = Authority("fire", b"k2", {"medic"})            # right name and key, role outside its scope
        self.assertFalse(self.v.verify(liar.issue("a", "medic", 100.0), 0.0))

    def test_revocation_gossip_blocks_credential(self):
        c = self.council.issue("a", "medic", 100.0)
        other = RevocationList(self.v.roots)
        other.add(self.council.revoke("a", "medic", 10.0))
        self.v.revoked.merge(other)
        self.assertFalse(self.v.verify(c, 20.0))

    def test_claim_hook(self):
        creds = {"1": self.council.issue("1", "nurse", 100.0),
                 "3": self.council.issue("3", "medic", 100.0)}
        self.v.revoked.add(self.council.revoke("3", "medic", 5.0))
        chk = self.v.claim_check(creds)
        self.assertIs(chk(C("1")), True)                      # nurse satisfies medic need
        self.assertIsNone(chk(C("2")))                        # no credential: no evidence
        self.assertIs(chk(C("3")), False)                     # revoked
        self.assertIsNone(chk(C("1", need=None)))
        self.assertIs(chk(C("1", t=500.0)), False)            # expired by claim time
        self.assertIs(chk(C("1", need="rescuer")), False)     # wrong role


class TestRevocation(unittest.TestCase):
    def setUp(self):
        self.a = Authority("council", b"k1", {"medic"})
        self.roots = {"council": self.a.root()}

    def test_forged_and_out_of_scope_ignored(self):
        rl = RevocationList(self.roots)
        good = self.a.revoke("x", "medic", 1.0)
        self.assertFalse(rl.add(Revocation("council", "y", "medic", 1.0, "0" * 64)))
        self.assertFalse(rl.add(Revocation("ghost", "y", "medic", 1.0, good.sig)))
        self.assertTrue(rl.add(good))
        self.assertFalse(rl.add(good))
        self.assertTrue(rl.is_revoked("x", "medic"))
        self.assertFalse(rl.is_revoked("x", "nurse"))

    def test_merge_converges_any_order_and_json_roundtrip(self):
        r1, r2, r3, r4 = (RevocationList(self.roots) for _ in range(4))
        r1.add(self.a.revoke("x", "medic", 1.0))
        r2.add(self.a.revoke("y", "medic", 2.0))
        r3.merge(r1); r3.merge(r2); r4.merge(r2); r4.merge(r1)
        self.assertEqual(r3.to_json(), r4.to_json())
        r5 = RevocationList(self.roots)
        self.assertEqual(r5.ingest(r3.to_json() + [{"bad": 1}, "junk"]), 2)
        self.assertEqual(len(r5), 2)


class TestCoPresence(unittest.TestCase):
    def setUp(self):
        self.reg = fleet("t", *[f"p{i}" for i in range(6)])
        self.cp = CoPresence(self.reg, k_full=5, max_contacts=4)

    def test_weight_grows_with_distinct_partners_and_caps_at_one(self):
        self.assertEqual(self.cp.weight("t", 0.0), 0.0)
        for i in range(3):
            self.assertTrue(self.cp.add(self.cp.make("t", f"p{i}", 10.0 * i)))
        self.assertAlmostEqual(self.cp.weight("t", 100.0), 0.6)
        self.assertTrue(self.cp.add(self.cp.make("t", "p3", 100.0)))
        self.assertFalse(self.cp.add(self.cp.make("t", "p4", 120.0)))   # contact cap per epoch
        self.assertEqual(self.cp.weight("t", 200.0), 0.8)

    def test_repeat_encounters_do_not_stack(self):
        e = self.cp.make("t", "p0", 5.0)
        self.assertTrue(self.cp.add(e))
        self.assertFalse(self.cp.add(e))
        self.assertFalse(self.cp.add(self.cp.make("p0", "t", 6.0)))     # same pair, same epoch
        self.assertEqual(len(self.cp.partners("t", 10.0)), 1)

    def test_forged_one_sided_self_and_unenrolled_rejected(self):
        e = self.cp.make("t", "p0", 5.0)
        self.assertFalse(self.cp.add(Encounter(e.a, e.b, e.epoch, e.sig_a, "0" * 64)))
        self.assertFalse(self.cp.add(Encounter(e.a, e.b, e.epoch + 1, e.sig_a, e.sig_b)))
        self.assertFalse(self.cp.add(Encounter("t", "t", 0, "x", "x")))
        self.assertFalse(self.cp.add(Encounter("t", "ghost", 0, "x", "x")))
        self.assertEqual(self.cp.weight("t", 10.0), 0.0)

    def test_window_expiry(self):
        self.cp.add(self.cp.make("t", "p0", 0.0))
        self.assertGreater(self.cp.weight("t", 100.0), 0)
        self.assertEqual(self.cp.weight("t", 300.0 * 10), 0.0)

    def test_sybil_swarm_gets_no_weight(self):
        reg = Registry(ROOT)
        real = Keystore()
        att = ROOT.attest(real.key_id)
        ids = [f"s{i}" for i in range(40)]
        enrolled = [i for i in ids if reg.enroll(i, real, att)]
        cp = CoPresence(reg)
        for a in ids:
            for b in ids:
                if a < b:
                    try:
                        cp.add(cp.make(a, b, 0.0))
                    except KeyError:
                        pass
        self.assertEqual(len(enrolled), 1)
        self.assertTrue(all(cp.weight(i, 10.0) == 0.0 for i in ids))


class TestLimiter(unittest.TestCase):
    def test_unenrolled_blocked_and_pseudonyms_share_budget(self):
        reg = fleet("a")
        lim = IdentityLimiter(reg, base_rate=0.0, base_burst=3.0)
        self.assertFalse(lim.allow("ghost", 0.0))
        self.assertEqual([lim.allow("a", 0.0) for _ in range(4)], [True, True, True, False])

    def test_copresence_raises_budget(self):
        reg = fleet("a", "b", *[f"p{i}" for i in range(5)])
        cp = CoPresence(reg, k_full=5)
        lim = IdentityLimiter(reg, cp, base_rate=0.0, base_burst=2.0, bonus_rate=0.0, bonus_burst=6.0)
        self.assertEqual(sum(lim.allow("b", 0.0) for _ in range(20)), 2)
        for i in range(5):
            cp.add(cp.make("a", f"p{i}", 1.0))
        self.assertEqual(sum(lim.allow("a", 2.0) for _ in range(20)), 8)

    def test_key_table_bounded(self):
        reg = fleet("a", "b")
        lim = IdentityLimiter(reg, max_keys=1)
        self.assertTrue(lim.allow("a", 0.0))
        self.assertFalse(lim.allow("b", 0.0))


if __name__ == "__main__":
    unittest.main()
