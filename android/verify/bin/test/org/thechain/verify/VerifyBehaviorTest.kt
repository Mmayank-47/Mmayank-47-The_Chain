package org.thechain.verify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerifyBehaviorTest {
    private var ids = 0

    private fun claim(node: Int, src: Int, pos: Pair<Double, Double>, t: Double) =
        ObservedClaim(ids++, node, src, pos, t)

    private fun honest(node: Int, pos: Pair<Double, Double> = 500.0 to 500.0, t: Double = 0.0) =
        claim(node, node, pos, t)

    @Test
    fun loneHonestClaimClearsRelease() {
        val v = Verifier<Int, Int>()
        assertTrue(v.pValid(honest(1)) >= 0.6)
    }

    @Test
    fun spoofedIdentityRejected() {
        val v = Verifier<Int, Int>()
        assertTrue(v.pValid(claim(9, 1, 0.0 to 0.0, 0.0)) < 0.1)
    }

    @Test
    fun sybilIdsFromOneDeviceRejectedAndCannotCorroborate() {
        val v = Verifier<Int, Int>()
        val cs = (0 until 10).map { j -> claim(7, 10_000 + j, 800.0 to 800.0, 5.0) }
        for (c in cs) v.observe(c)
        assertTrue(cs.all { v.pValid(it) < 0.1 })
        assertEquals(0.0, v.score(cs[0]).parts.getValue("corroboration"), 0.0)
    }

    @Test
    fun oneDeviceManyPlacesPenalisedAfterFirst() {
        val v = Verifier<Int, Int>()
        val cs = (0 until 4).map { i -> claim(5, 5, (300.0 * i + 300.0) to 400.0, 10.0 * i) }
        for (c in cs) v.observe(c)
        val ps = cs.map { v.pValid(it) }
        assertTrue(ps[0] >= 0.6)
        assertTrue(ps.drop(1).all { it < ps[0] })
        assertTrue(ps.last() < 0.2)
    }

    @Test
    fun independentCorroborationRaisesScore() {
        val v = Verifier<Int, Int>()
        val a = honest(1, 500.0 to 500.0, 0.0)
        val base = v.pValid(a)
        v.observe(honest(2, 540.0 to 520.0, 30.0))
        v.observe(honest(3, 480.0 to 470.0, 60.0))
        assertTrue(v.pValid(a) > base)
    }

    @Test
    fun stalenessLowersButIsCapped() {
        val v = Verifier<Int, Int>()
        val c = honest(1)
        val fresh = v.pValid(c, 0.0)
        val old = v.pValid(c, 1800.0)
        val ancient = v.pValid(c, 1e6)
        assertTrue(fresh > old)
        assertEquals(old, ancient, 0.0)
    }

    @Test
    fun credentialHook() {
        val good = Verifier<Int, Int>(credential = { true }).pValid(honest(1))
        val bad = Verifier<Int, Int>(credential = { false }).pValid(honest(1))
        assertTrue(good > 0.9)
        assertTrue(bad < 0.2)
    }

    @Test
    fun explanationListsBiggestReason() {
        val v = Verifier<Int, Int>()
        val d = v.score(claim(9, 1, 0.0 to 0.0, 0.0))
        assertEquals("identity", d.topReasons(1)[0].first)
    }

    @Test
    fun orderIndependent() {
        val a = honest(1, 500.0 to 500.0, 0.0)
        val b = honest(2, 520.0 to 500.0, 20.0)
        val v1 = Verifier<Int, Int>()
        val v2 = Verifier<Int, Int>()
        v1.observe(a)
        v1.observe(b)
        v2.observe(b)
        v2.observe(a)
        assertEquals(v1.pValid(a, 100.0), v2.pValid(a, 100.0), 5e-8)
    }
}

class ChallengeBehaviorTest {
    private val challenge = makeChallenge(claimId = 1, claimant = 4, now = 0.0, nonce = "n1")
    private val known = setOf(5, 6, 7)

    private fun resp(
        cid: String = challenge.cid,
        claimant: Int = 4,
        baroHpa: Double = 1004.0,
        neighbors: Set<Int> = setOf(5, 6),
        sent: Double = 30.0,
    ) = Response(cid, claimant, baroHpa, neighbors, sent)

    @Test
    fun pass() {
        assertEquals(PASS, checkResponse(challenge, resp(), 40.0, 1005.0, known), 0.0)
    }

    @Test
    fun wrongPressureOrInventedNeighboursFail() {
        assertEquals(FAIL, checkResponse(challenge, resp(baroHpa = 990.0), 40.0, 1005.0, known), 0.0)
        assertEquals(FAIL, checkResponse(challenge, resp(neighbors = setOf(91, 92)), 40.0, 1005.0, known), 0.0)
        assertEquals(FAIL, checkResponse(challenge, resp(neighbors = emptySet()), 40.0, 1005.0, known), 0.0)
    }

    @Test
    fun replayOrRelayRejected() {
        val other = makeChallenge(1, 4, 0.0, "n2")
        assertEquals(MISMATCH, checkResponse(other, resp(), 40.0, 1005.0, known), 0.0)
        assertEquals(MISMATCH, checkResponse(challenge, resp(claimant = 8), 40.0, 1005.0, known), 0.0)
    }

    @Test
    fun silencePendingThenWeakNegative() {
        assertEquals(0.0, checkResponse(challenge, null, 60.0, 1005.0, known), 0.0)
        assertEquals(LATE, checkResponse(challenge, null, 500.0, 1005.0, known), 0.0)
        assertTrue(LATE > FAIL)
    }

    @Test
    fun challengeResultMovesScore() {
        val v = Verifier<Int, Int>()
        val c = ObservedClaim(0, 4, 4, 500.0 to 500.0, 0.0)
        val base = v.pValid(c)
        v.recordChallenge(c.id, PASS)
        assertTrue(v.pValid(c) > base)
        v.recordChallenge(c.id, FAIL)
        assertTrue(v.pValid(c) < base)
    }
}
