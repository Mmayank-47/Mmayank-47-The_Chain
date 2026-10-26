package org.thechain.merge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.DispatchState
import org.thechain.core.Hlc
import org.thechain.cryptofake.HmacSigner
import org.thechain.testing.Vectors

class MergeVectorTest {
    private val signer = HmacSigner(ByteArray(32) { it.toByte() })

    @Test
    fun eventHashAndPlanMatchPython() {
        val spec = Vectors.load("merge")
        val ev = Event.make(
            "A",
            0,
            "",
            Hlc(5, 0, "A"),
            "claim",
            mapOf("claim" to "z", "cell" to "0:0", "urgency" to 0.2),
            signer,
        )
        assertEquals(spec.getString("event_hash"), ev.hash)

        val rep = Replica("A", signer, nowMs = { 1_000_000 })
        rep.openClaim("claim-1", "1:2", 0.4)
        val did = rep.dispatch("claim-1", "A")
        rep.setState(did, DispatchState.ACCEPTED)
        rep.heartbeat(did)
        assertEquals(spec.getString("did"), did)
        assertEquals("claim-1|A", rep.plan(1_000_000).winners["claim-1"])

        val other = Replica("B", signer, nowMs = { 1_000_100 })
        val n = other.ingest(rep.store.logs.values.flatten())
        assertEquals(spec.getInt("ingested"), n)
        assertEquals("claim-1|A", other.plan(1_000_100).winners["claim-1"])

        val forged = Event(ev.node, ev.seq, ev.prev, ev.hlc, ev.kind, ev.data, ev.hash, "00")
        assertTrue(other.store.add(forged).isEmpty())
        assertTrue(spec.getBoolean("forged_dropped"))
    }
}
