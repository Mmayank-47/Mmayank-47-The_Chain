package org.thechain.matching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.thechain.testing.Vectors

class MatchingVectorTest {
    private val spec = Vectors.load("matching")

    @Test
    fun matchesPythonVectors() {
        val feats = listOf(
            Features(canWalk = true),
            Features(canWalk = false, breathing = false),
            Features(canWalk = false, breathing = true, respRate = 34.0),
            Features(canWalk = false, breathing = true, respRate = 20.0, perfusionOk = false),
            Features(false, true, 20.0, true, true),
            Features(canWalk = false),
        )
        val categories = spec.getJSONArray("categories")
        assertEquals(categories.length(), feats.size)
        for (i in feats.indices) assertEquals(categories.getString(i), classify(feats[i]).wire)

        val rows = spec.getJSONArray("claim_scores")
        val inputs = listOf(
            Triple(listOf("not_breathing"), 0.3, "need"),
            Triple(listOf("elderly"), 0.4, "need"),
            Triple(emptyList<String>(), 0.3, "offer"),
        )
        for (i in inputs.indices) {
            val (features, urgency, kind) = inputs[i]
            val (cat, u, g) = claimScores(features, urgency, kind, 600.0)
            val row = rows.getJSONObject(i)
            assertEquals(row.getString("cat"), cat.wire)
            assertEquals(row.getDouble("u"), u, 1e-9)
            assertEquals(row.getDouble("g"), g, 1e-9)
        }

        val bidders = linkedMapOf(
            "a" to Bidder("a", mapOf("t1" to 0.9, "t2" to 0.2)),
            "b" to Bidder("b", mapOf("t1" to 0.4, "t2" to 0.8)),
            "c" to Bidder("c", mapOf("t1" to 0.5, "t2" to 0.5)),
        )
        assertEquals(spec.getInt("cbba_rounds"), gossip(bidders, listOf("a" to "b", "b" to "c")))
        val tasks = spec.getJSONObject("cbba_tasks")
        assertEquals("t1", bidders.getValue("a").myTask())
        assertEquals("t2", bidders.getValue("b").myTask())
        assertNull(bidders.getValue("c").myTask())
        assertEquals(tasks.getString("a"), bidders.getValue("a").myTask())
        assertEquals(tasks.getString("b"), bidders.getValue("b").myTask())
        assertEquals(true, tasks.isNull("c"))

        val claim = Claim("c1", 0.0 to 0.0, 0.9, 0.8, 0.8, "medic", 0.7)
        val responder = Responder("r1", 10.0 to 0.0, setOf("medic"), 0.9)
        assertEquals(spec.getDouble("utility"), pairUtility(claim, responder, 100.0, 0.95, 0.99, 0.0), 1e-9)
        assertEquals(spec.getString("gate"), gate(0.9, 0.95, 0.7, 0.99))
        assertEquals(spec.getString("gate_low"), gate(0.2, 0.95, 0.9, 0.99))
    }
}
