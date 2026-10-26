package org.thechain.trust

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class TrustBehaviorTest {
    @Test
    fun coldStartAndUpdates() {
        val r = BetaRep()
        assertEquals(0.5, r.mean("x", 0.0), 0.0)
        r.record("x", "verified_arrival", 0.0)
        assertTrue(r.mean("x", 0.0) > 0.5)
    }

    @Test
    fun failureOutweighsSuccess() {
        val r = BetaRep()
        r.record("x", "verified_arrival", 0.0)
        r.record("x", "failed_claim", 0.0)
        assertTrue(r.mean("x", 0.0) < 0.5)
    }

    @Test
    fun decayReturnsToPrior() {
        val r = BetaRep(halfLifeS = 100.0)
        repeat(5) { r.record("x", "verified_arrival", 0.0) }
        val hi = r.mean("x", 0.0)
        val later = r.mean("x", 10_000.0)
        assertTrue(hi > later)
        assertEquals(0.5, later, 5e-4)
        assertTrue(r.evidence("x", 10_000.0) < 0.01)
    }

    @Test
    fun weightCappedAndUnknownEvent() {
        val r = BetaRep()
        r.record("x", "verified_arrival", 0.0, weight = 1e9)
        assertTrue(r.evidence("x", 0.0) <= 3.0)
        assertThrows(IllegalArgumentException::class.java) {
            r.record("x", "made_up", 0.0)
        }
    }

    @Test
    fun uncertaintyShrinksWithEvidence() {
        val r = BetaRep()
        val s0 = r.std("x", 0.0)
        repeat(10) { r.record("x", "verified_arrival", 0.0) }
        assertTrue(r.std("x", 0.0) < s0)
    }

    @Test
    fun rankSumsToOneAndDeterministic() {
        val g = world().first
        assertEquals(1.0, g.rank().values.sum(), 5e-10)
        assertEquals(g.rank(), world().first.rank())
    }

    @Test
    fun rootsFullAndSybilRingFarBelowHonest() {
        val (g, honest, ring) = world()
        val sc = g.scores()
        assertEquals(1.0, sc.getValue("root"), 0.0)
        val h = honest.sumOf { sc.getValue(it) } / honest.size
        val s = ring.sumOf { sc.getValue(it) } / ring.size
        assertTrue(h > 0.5)
        assertTrue(s < h / 3.0)
    }

    @Test
    fun ringWithNoAttackEdgeGetsNothing() {
        val g = VouchGraph(seeds = listOf("root"))
        g.vouch("root", "a")
        for (x in "pqr") {
            for (y in "pqr") g.vouch(x.toString(), y.toString())
        }
        assertEquals(0.0, g.score("p"), 0.0)
    }

    @Test
    fun vouchingForManyDilutes() {
        val a = VouchGraph(seeds = listOf("r"))
        val b = VouchGraph(seeds = listOf("r"))
        a.vouch("r", "x")
        b.vouch("r", "x")
        for (i in 0 until 5) b.vouch("r", "y$i")
        assertTrue(b.score("x") < a.score("x"))
    }

    @Test
    fun selfVouchIgnoredAndStrangerDefault() {
        val g = VouchGraph(seeds = listOf("r"))
        g.vouch("z", "z")
        assertEquals(0.5, g.score("z"), 0.0)
    }

    @Test
    fun noSeedsNoTrust() {
        val g = VouchGraph()
        g.vouch("a", "b")
        assertEquals(0.0, g.score("b"), 0.0)
    }

    @Test
    fun ringFlaggedHonestNot() {
        val (g, honest, ring) = world()
        val flagged = findClusters(g)
        assertEquals(1, flagged.size)
        assertEquals(ring.toSet(), flagged[0])
        val d = discounts(g)
        assertTrue(ring.all { it in d })
        assertTrue((honest + "root").none { it in d })
    }

    @Test
    fun seedInsideClusterProtectsIt() {
        val g = VouchGraph(seeds = listOf("a"))
        for (x in "abc") {
            for (y in "abc") g.vouch(x.toString(), y.toString())
        }
        assertEquals(emptyList<Set<String>>(), findClusters(g))
    }

    @Test
    fun sybilBelowStrangerBelowHonest() {
        val (g, _, _) = world()
        val e = TrustEngine(g)
        assertTrue(e.trust("s3", 0.0) < 0.1)
        assertEquals(0.5, e.trust("nobody", 0.0), 0.0)
        assertTrue(e.trust("h2", 0.0) > 0.5)
    }

    @Test
    fun badBehaviourDropsTrustedNode() {
        val (g, _, _) = world()
        val e = TrustEngine(g)
        val before = e.trust("h2", 0.0)
        repeat(3) { e.rep.record("h2", "failed_claim", 0.0) }
        assertTrue(e.trust("h2", 0.0) < before / 2.0)
    }

    @Test
    fun goodBehaviourLiftsStranger() {
        val e = TrustEngine(VouchGraph(seeds = listOf("r")))
        repeat(6) { e.rep.record("new", "verified_arrival", 0.0) }
        assertTrue(e.trust("new", 0.0) > 0.7)
    }

    @Test
    fun llrSignAndBounds() {
        val (g, _, _) = world()
        val e = TrustEngine(g)
        assertTrue(e.llr("h2", 0.0) > 0.0)
        assertTrue(e.llr("s3", 0.0) < 0.0)
        assertEquals(0.0, e.llr("nobody", 0.0), 5e-8)
        assertTrue(abs(e.llr("root", 0.0)) <= 3.0)
    }
}

private fun community(g: VouchGraph, names: List<String>, root: String) {
    g.vouch(root, names[0])
    for (i in 0 until names.size - 1) {
        val a = names[i]
        val b = names[i + 1]
        g.vouch(a, b)
        g.vouch(b, a)
    }
}

private fun sybilRing(g: VouchGraph, k: Int = 10, attackFrom: String = "h1"): List<String> {
    val ring = (0 until k).map { "s$it" }
    for (a in ring) {
        for (b in ring) g.vouch(a, b)
    }
    g.vouch(attackFrom, ring[0])
    return ring
}

private fun world(): Triple<VouchGraph, List<String>, List<String>> {
    val g = VouchGraph(seeds = listOf("root", "root2"))
    val honest = (1..5).map { "h$it" }
    community(g, honest, "root")
    g.vouch("h1", "h3")
    g.vouch("h2", "h5")
    for ((r, n) in listOf("root" to "h3", "root" to "h5", "root2" to "h2", "root2" to "h4")) {
        g.vouch(r, n)
    }
    return Triple(g, honest, sybilRing(g))
}
