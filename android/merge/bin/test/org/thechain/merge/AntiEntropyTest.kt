package org.thechain.merge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.cryptofake.HmacSigner

class AntiEntropyTest {
    private val signer = HmacSigner(ByteArray(32) { 3 })

    @Test
    fun summaryExchangeBackfillsMissingEvents() {
        val a = Replica("A", signer, nowMs = { 1_000_000 })
        val b = Replica("B", signer, nowMs = { 1_000_100 })
        a.openClaim("c1", "0:0", 0.5)
        a.openClaim("c2", "1:1", 0.7)

        val aeA = AntiEntropy(a, minInterval = 1.0)
        val aeB = AntiEntropy(b, minInterval = 1.0)

        val sumB = aeB.request("A", 1.0)
        assertNotNull(sumB)
        assertEquals("ae.sum", sumB!!["t"])

        val batchFromA = aeA.onSummary("B", sumB, 1.0)
        assertNotNull(batchFromA)
        assertEquals("ae.ev", batchFromA!!["t"])
        @Suppress("UNCHECKED_CAST")
        val events = batchFromA["e"] as List<*>
        assertEquals(2, events.size)

        val ingested = aeB.onEvents("A", batchFromA, 1.0)
        assertEquals(2, ingested)
        assertNotNull(b.book.claims.get("c1"))
        assertNotNull(b.book.claims.get("c2"))

        // Rate limit: second request within minInterval is suppressed
        assertNull(aeB.request("A", 1.5))
        assertNotNull(aeB.request("A", 3.0))
        assertTrue(expiresAt(1_000L, 60_000L) == 61_000L)
    }
}
