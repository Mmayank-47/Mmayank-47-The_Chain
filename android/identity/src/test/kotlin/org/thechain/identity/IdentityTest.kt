package org.thechain.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
class IdentityTest {
    @Test
    fun ed25519SignsAndRejectsTamper() {
        val key = Ed25519DeviceKey.generate()
        val sig = key.sign("hello".toByteArray())
        assertTrue(key.verify("hello".toByteArray(), sig))
        assertFalse(key.verify("hello!".toByteArray(), sig))
        val again = Ed25519DeviceKey.fromPrivateKeyset(key.privateKeyset())
        assertEquals(key.keyId, again.keyId)
        assertTrue(again.verify("hello".toByteArray(), sig))
    }

    @Test
    fun oneHardwareKeyCannotBeTwoNodes() {
        val root = Ed25519DeviceKey.generate()
        val attestor = Ed25519Attestor(root)
        val reg = Registry { id, att -> attestor.check(id, att) }
        val phone = Ed25519DeviceKey.generate()
        assertTrue(reg.enroll("a", phone, attestor.attest(phone.keyId)))
        assertFalse(reg.enroll("b", phone, attestor.attest(phone.keyId)))
        val other = Ed25519DeviceKey.generate()
        assertFalse(reg.enroll("a", other, attestor.attest(other.keyId)))
        assertFalse(reg.enroll("c", other, "not-a-signature"))
    }

    @Test
    fun handshakeRejectsReflectionReplayAndBadTime() {
        val aliceKey = Ed25519DeviceKey.generate()
        val bobKey = Ed25519DeviceKey.generate()
        val root = Ed25519DeviceKey.generate()
        val attestor = Ed25519Attestor(root)
        val reg = Registry { id, att -> attestor.check(id, att) }
        reg.enroll("alice", aliceKey, attestor.attest(aliceKey.keyId))
        reg.enroll("bob", bobKey, attestor.attest(bobKey.keyId))
        val table = HandshakeTable()
        val now = 1_700_000_000_000
        val alice = table.offer("alice", "ep-a", "aa", now, reg)
        val bobBody = HandshakeTable.body("bob", "ep-b", "bb", "aa", now)
        val bob = Handshake("bob", "ep-b", "bb", "aa", now, reg.sign("bob", org.thechain.core.canonical(bobBody)))
        assertEquals("bob", table.accept("alice", "aa", bob, now, reg))
        assertNull(table.accept("alice", "aa", bob, now, reg))
        val reflected = Handshake("alice", "ep-a", "aa", "", now, alice.sig)
        assertNull(table.accept("bob", "bb", reflected, now, reg))
        val staleBody = HandshakeTable.body("bob", "ep-b", "cc", "aa", now - 10 * 60 * 1000)
        val stale = Handshake("bob", "ep-b", "cc", "aa", now - 10 * 60 * 1000, reg.sign("bob", org.thechain.core.canonical(staleBody)))
        assertNull(HandshakeTable().accept("alice", "aa", stale, now, reg))
    }
}
