package org.thechain.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.Hlc
import org.thechain.core.Message
import org.thechain.core.Priority
import org.thechain.core.TokenBucket
import org.thechain.testing.Vectors
import org.thechain.testing.objects

/**
 * Ports store_flooding numbers from vectors/redteam.json (defended 0.064 = 32/500).
 */
class TransportRedteamTest {
    private class TestLimiter(
        private val enrolled: Set<String>,
        private val baseRate: Double = 0.2,
        private val baseBurst: Double = 3.0,
        private val strangerRate: Double = 0.0,
        private val strangerBurst: Double = 0.0,
    ) : StrangerLaneLimiter {
        private val buckets = mutableMapOf<String, TokenBucket>()
        private val strangers = mutableMapOf<String, TokenBucket>()

        override fun allow(node: String, now: Double, cost: Double): Boolean {
            if (node !in enrolled) return false
            val b = buckets.getOrPut(node) {
                TokenBucket(baseRate, baseBurst).also { it.t = now }
            }
            b.rate = baseRate
            b.burst = baseBurst
            return b.allow(now, cost)
        }

        override fun allowStranger(node: String, now: Double, cost: Double): Boolean {
            if (node in enrolled) return false
            if (strangerBurst <= 0.0 && strangerRate <= 0.0) return false
            val b = strangers.getOrPut(node) {
                TokenBucket(strangerRate, strangerBurst).also { it.t = now }
            }
            b.rate = strangerRate
            b.burst = strangerBurst
            return b.allow(now, cost)
        }
    }

    @Test
    fun storeFloodingMatchesRedteamVector() {
        val spec = Vectors.load("redteam")
            .getJSONArray("attacks")
            .objects()
            .first { it.getString("name") == "store_flooding" }

        val undefStore = CarryStore(capacity = 512, maxPerOrigin = null, maxPerNeighbour = null)
        for (i in 0 until 500) {
            val m = Message(
                "m9.case",
                Priority.DISTRESS,
                "flooder",
                mapOf("seq" to i),
                Hlc(1, i, "flooder"),
                ttlS = 3600.0,
            )
            undefStore.add(m, null, 0.0, fromId = "evil_peer")
        }
        val flooderUndef = undefStore.size

        val defStore = CarryStore(capacity = 512, maxPerOrigin = 32, maxPerNeighbour = 64)
        for (i in 0 until 500) {
            val m = Message(
                "m9.case",
                Priority.DISTRESS,
                "flooder",
                mapOf("seq" to i),
                Hlc(1, i, "flooder"),
                ttlS = 3600.0,
            )
            defStore.add(m, null, 0.0, fromId = "evil_peer")
        }
        val flooderDef = defStore.size
        assertTrue(defStore.originCount("flooder") <= 32)
        assertTrue(flooderDef <= 32)

        val lim = TestLimiter(
            enrolled = setOf("honest_node"),
            baseRate = 1.0,
            baseBurst = 5.0,
            strangerBurst = 1.0,
        )
        val guard = IngressGuard(limiter = lim, strangerLane = true)
        var victimAdmitted = 0
        for (i in 0 until 5) {
            val m = Message(
                "m9.case",
                Priority.DISTRESS,
                "victim_stranger",
                mapOf("seq" to i),
                Hlc(1, i, "victim_stranger"),
                ttlS = 3600.0,
            )
            if (guard.admit(m, "victim_stranger", 0.0) != null) victimAdmitted++
        }
        assertEquals(1, victimAdmitted)

        val chatter = Message(
            "m8.event",
            Priority.CHATTER,
            "victim_stranger",
            mapOf("k" to "beat"),
            Hlc(1, 10, "victim_stranger"),
            ttlS = 3600.0,
        )
        assertNull(guard.admit(chatter, "victim_stranger", 1.0))

        val off = flooderUndef / 500.0
        val on = flooderDef / 500.0
        assertEquals(1.0, off, 1e-12)
        assertEquals(0.064, on, 1e-12)
        assertEquals(spec.getDouble("undefended"), off, 1e-12)
        assertEquals(spec.getDouble("defended"), on, 1e-12)
        assertEquals(32, flooderDef)
        assertTrue(on <= spec.getDouble("max_ok"))
    }
}
