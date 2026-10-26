package org.thechain.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class MeshHarnessTest {
    @Test
    fun threeNodesPropagateOpenClaim() {
        val harness = MeshHarness(listOf("A", "B", "C"))
        // Neighbour discovery via hello beacons
        harness.run(4)

        assertNotNull(harness.nodes.getValue("A").node.neighbors["B"])
        assertNotNull(harness.nodes.getValue("B").node.neighbors["A"])

        harness.nodes.getValue("A").openClaim("claim-sim-1", "1:2", 0.9)
        assertNotNull(harness.nodes.getValue("A").replica.book.claims.get("claim-sim-1"))

        harness.run(8)

        val onB = harness.nodes.getValue("B").replica.book.claims.get("claim-sim-1")
        assertNotNull(onB, "claim opened on A must appear on B after ticks")
        @Suppress("UNCHECKED_CAST")
        val payload = onB as Map<String, Any?>
        assertEquals("1:2", payload["cell"])
        assertEquals(0.9, (payload["urgency"] as Number).toDouble(), 1e-12)

        val onC = harness.nodes.getValue("C").replica.book.claims.get("claim-sim-1")
        assertNotNull(onC, "claim should also reach C on a fully-linked mesh")
    }
}
