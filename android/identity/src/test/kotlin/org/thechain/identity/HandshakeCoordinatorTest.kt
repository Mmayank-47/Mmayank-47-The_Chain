package org.thechain.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.canonical

class HandshakeCoordinatorTest {
    private val now = 1_700_000_000_000L

    private fun enrolledPair(): Triple<Registry, String, String> {
        val aliceKey = Ed25519DeviceKey.generate()
        val bobKey = Ed25519DeviceKey.generate()
        val root = Ed25519DeviceKey.generate()
        val attestor = Ed25519Attestor(root)
        val reg = Registry { id, att -> attestor.check(id, att) }
        assertTrue(reg.enroll("alice", aliceKey, attestor.attest(aliceKey.keyId)))
        assertTrue(reg.enroll("bob", bobKey, attestor.attest(bobKey.keyId)))
        return Triple(reg, "alice", "bob")
    }

    @Test
    fun mutualHandshakeBindsEndpointToNodeId() {
        val (reg, aliceId, bobId) = enrolledPair()
        val alice = HandshakeCoordinator(aliceId, reg, nowMs = { now })
        val bob = HandshakeCoordinator(bobId, reg, nowMs = { now })

        val aOffer = alice.onConnected("ep-bob")
        val bOffer = bob.onConnected("ep-alice")

        val aReply = alice.onHandshakePayload("ep-bob", bOffer)
        assertNotNull(aReply)
        val bReply = bob.onHandshakePayload("ep-alice", aOffer)
        assertNotNull(bReply)

        alice.onHandshakePayload("ep-bob", bReply!!)
        bob.onHandshakePayload("ep-alice", aReply!!)

        assertEquals(bobId, alice.verifiedNodeId("ep-bob"))
        assertEquals(aliceId, bob.verifiedNodeId("ep-alice"))
        assertEquals(1, alice.verifiedPeers().size)
    }

    @Test
    fun spoofedHandshakeFailsWithoutEnrollment() {
        val (reg, aliceId, _) = enrolledPair()
        val malloryKey = Ed25519DeviceKey.generate()
        // Mallory is NOT enrolled in alice's registry — verify must fail.
        val alice = HandshakeCoordinator(aliceId, reg, nowMs = { now })
        val offer = alice.onConnected("ep-x")
        val decoded = HandshakeWire.decode(offer)!!
        val body = HandshakeTable.body("mallory", "ep-x", "deadbeefcafebabe", decoded.nonceHex, now)
        val spoof = Handshake(
            "mallory",
            "ep-x",
            "deadbeefcafebabe",
            decoded.nonceHex,
            now,
            malloryKey.sign(canonical(body)),
        )
        alice.onHandshakePayload("ep-x", HandshakeWire.encode(spoof))
        assertNull(alice.verifiedNodeId("ep-x"))
    }

    @Test
    fun replayedHandshakeRejected() {
        val (reg, aliceId, bobId) = enrolledPair()
        val shared = HandshakeTable()
        val localNonce = "aa11aa11aa11aa11"
        val bobBody = HandshakeTable.body(bobId, "ep-b", "bb22bb22bb22bb22", localNonce, now)
        val bobHs = Handshake(
            bobId,
            "ep-b",
            "bb22bb22bb22bb22",
            localNonce,
            now,
            reg.sign(bobId, canonical(bobBody)),
        )
        assertEquals(bobId, shared.accept(aliceId, localNonce, bobHs, now, reg))
        assertNull(shared.accept(aliceId, localNonce, bobHs, now, reg))
    }

    @Test
    fun reflectionRejected() {
        val (reg, aliceId, _) = enrolledPair()
        val table = HandshakeTable()
        val offer = table.offer(aliceId, "ep-a", "aa", now, reg)
        val reflected = Handshake(aliceId, "ep-a", "aa", "bb", now, offer.sig)
        assertNull(table.accept("bob", "bb", reflected, now, reg))
    }

    @Test
    fun freshPhonesExchangeKeysAndBind() {
        val aliceKey = Ed25519DeviceKey.generate()
        val bobKey = Ed25519DeviceKey.generate()
        val aliceReg = Registry { _, _ -> true }
        val bobReg = Registry { _, _ -> true }
        assertTrue(aliceReg.enroll("alice", aliceKey, "self", aliceKey.publicKeyset))
        assertTrue(bobReg.enroll("bob", bobKey, "self", bobKey.publicKeyset))
        val alice = HandshakeCoordinator(
            "alice",
            aliceReg,
            nowMs = { now },
            localPublicKey = aliceKey.publicKeyset,
            adoptPeerKey = { id, key -> aliceReg.enrollPublic(id, key, "radio") },
        )
        val bob = HandshakeCoordinator(
            "bob",
            bobReg,
            nowMs = { now },
            localPublicKey = bobKey.publicKeyset,
            adoptPeerKey = { id, key -> bobReg.enrollPublic(id, key, "radio") },
        )
        val aReply = alice.onHandshakePayload("ep-bob", bob.onConnected("ep-alice"))
        val bReply = bob.onHandshakePayload("ep-alice", alice.onConnected("ep-bob"))
        alice.onHandshakePayload("ep-bob", bReply!!)
        bob.onHandshakePayload("ep-alice", aReply!!)
        assertEquals("bob", alice.verifiedNodeId("ep-bob"))
        assertEquals("alice", bob.verifiedNodeId("ep-alice"))
        assertTrue(aliceReg.verify("bob", "ping".toByteArray(), bobKey.sign("ping".toByteArray())))
    }

    @Test
    fun spoofOfEnrolledIdentityWithForeignKeyFails() {
        val aliceKey = Ed25519DeviceKey.generate()
        val bobKey = Ed25519DeviceKey.generate()
        val malloryKey = Ed25519DeviceKey.generate()
        val reg = Registry { _, _ -> true }
        assertTrue(reg.enroll("alice", aliceKey, "self", aliceKey.publicKeyset))
        assertTrue(reg.enrollPublic("bob", bobKey.publicKeyset, "file"))
        val alice = HandshakeCoordinator(
            "alice",
            reg,
            nowMs = { now },
            localPublicKey = aliceKey.publicKeyset,
            adoptPeerKey = { id, key -> reg.enrollPublic(id, key, "radio") },
        )
        val offer = alice.onConnected("ep-x")
        val decoded = HandshakeWire.decode(offer)!!
        val pub = HandshakeTable.encodePub(malloryKey.publicKeyset)
        val body = HandshakeTable.body("bob", "ep-x", "deadbeefcafebabe", decoded.nonceHex, now, pub)
        val spoof = Handshake(
            "bob",
            "ep-x",
            "deadbeefcafebabe",
            decoded.nonceHex,
            now,
            malloryKey.sign(canonical(body)),
            pub,
        )
        alice.onHandshakePayload("ep-x", HandshakeWire.encode(spoof))
        assertNull(alice.verifiedNodeId("ep-x"))
    }

    @Test
    fun enrollPublicAllowsVerifyOnlyPeer() {
        val aliceKey = Ed25519DeviceKey.generate()
        val bobKey = Ed25519DeviceKey.generate()
        val reg = Registry { _, _ -> true }
        assertTrue(reg.enroll("alice", aliceKey, "self", aliceKey.publicKeyset))
        assertTrue(reg.enrollPublic("bob", bobKey.publicKeyset, "debug-file"))
        assertTrue(reg.enrolled("bob"))
        assertTrue(reg.verify("bob", "ping".toByteArray(), bobKey.sign("ping".toByteArray())))
    }
}
