package org.thechain.matching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.CanonicalJson
import org.thechain.core.Signer
import org.thechain.core.canonical
import kotlin.math.hypot
import kotlin.random.Random

class MatchingBehaviorTest {
    @Test
    fun pythonRoundIsHalfEvenOnTheExactDecimal() {
        assertEquals(1.2, pythonRound(1.25, 1), 0.0)
        assertEquals(1.4, pythonRound(1.35, 1), 0.0)
        assertEquals(2.67, pythonRound(2.675, 2), 0.0)
        assertEquals(-1.2, pythonRound(-1.25, 1), 0.0)
        assertEquals(0.123456789, pythonRound(0.1234567895, 9), 0.0)
        assertEquals(1.000000001, pythonRound(1.0000000005, 9), 0.0)
        assertEquals(1.23456789, pythonRound(1.2345678905, 9), 0.0)
    }

    @Test
    fun entryDigestIsCanonicalRoundedBid() {
        assertEquals("""["a",1,"t1",0.1]""", utf8(entryDigest("a", 1, "t1", 0.1)))
        assertEquals("""["a",0,null,0.0]""", utf8(entryDigest("a", 0, null, 0.0)))
        assertEquals("""["b",2,null,1.23456789]""", utf8(entryDigest("b", 2, null, 1.2345678905)))
        val rounded = pythonRound(0.9, 9)
        assertEquals(canonical(listOf("a", 3, "t", rounded)).toList(), entryDigest("a", 3, "t", 0.9).toList())
        assertEquals("""["a",3,"t",0.9]""", CanonicalJson.encode(listOf("a", 3, "t", rounded)))
    }

    @Test
    fun startRulesAndExpectantStaysPositive() {
        assertEquals(Category.MINOR, classify(Features(canWalk = true)))
        assertEquals(Category.EXPECTANT, classify(Features(canWalk = false, breathing = false)))
        assertEquals(Category.IMMEDIATE, classify(Features(canWalk = false, breathing = true, respRate = 34.0)))
        assertEquals(
            Category.IMMEDIATE,
            classify(Features(canWalk = false, breathing = true, respRate = 20.0, perfusionOk = false)),
        )
        assertEquals(Category.DELAYED, classify(Features(false, true, 20.0, true, true)))
        assertEquals(Category.UNKNOWN, classify(Features(canWalk = false)))
        val (_, u, g) = scores(Features(canWalk = false, breathing = false))
        assertTrue(u > 0.0)
        assertTrue(g < 0.1)
    }

    @Test
    fun utilityMonotoneAndGateRisesWithDanger() {
        val c = claim("c", urgency = 1.0, need = "medic")
        assertEquals(Double.NEGATIVE_INFINITY, pairUtility(c, resp("a"), 100.0, 0.95, 0.97, 0.0), 0.0)
        val near = pairUtility(c, resp("m", MEDIC), 100.0, 0.95, 0.97, 0.0)
        val far = pairUtility(c, resp("m", MEDIC), 3000.0, 0.95, 0.97, 0.0)
        val risky = pairUtility(c, resp("m", MEDIC), 100.0, 0.5, 0.97, 0.0)
        assertTrue(near > far)
        assertTrue(near > risky)
        assertEquals("dispatch", gate(0.9, 0.95, 0.8, 0.97))
        assertEquals("dispatch", gate(0.9, 0.95, 0.5, 0.97))
        assertEquals("scout", gate(0.9, 0.35, 0.6, 0.97))
        assertEquals("probe", gate(0.4, 0.95, 0.8, 0.97))
        assertEquals("probe", gate(0.3, 0.9, 0.2, 0.97))
        assertEquals("hold", gate(0.65, 0.95, 0.45, 0.4))
    }

    @Test
    fun opportunityCostKeepsTheOnlyMedicOffAMinorCase() {
        val crit = claim("k", urgency = 1.0, need = "medic", p = 0.8)
        val one = prices(listOf(resp("m", MEDIC)), listOf(crit)).getValue("medic")
        val many = prices((0 until 5).map { resp("m$it", MEDIC) }, listOf(crit)).getValue("medic")
        assertTrue(one > many)
        assertEquals(0.0, prices(listOf(resp("m", MEDIC)), emptyList()).getValue("medic"), 0.0)

        val medic = resp("m", MEDIC)
        val critical = claim("k", urgency = 1.0, need = "medic")
        val minor = claim("n", urgency = 0.15)
        val price = mapOf("medic" to 0.5)
        assertEquals(0.0, oppCost(medic, critical, price), 0.0)
        assertEquals(0.5, oppCost(medic, minor, price), 0.0)
        assertEquals(0.0, oppCost(resp("a"), minor, price), 0.0)

        val minorCase = claim("minor", urgency = 0.15, gain = 0.2)
        val rs = listOf(resp("medic", MEDIC), resp("walker"))
        val safe: (Responder, Claim) -> Pair<Double, Double> = { r, c ->
            hypot(r.pos.first - c.pos.first, r.pos.second - c.pos.second) to 0.999
        }
        val reach: (Responder, Double) -> Double = { _, _ -> 0.97 }
        val (util, _) = buildUtilities(listOf(minorCase), rs, safe, reach, extraDemand = mapOf("medic" to 0.7))
        assertFalse("minor" in util.getValue("medic"))
        assertTrue("minor" in util.getValue("walker"))
        val (util2, _) = buildUtilities(listOf(minorCase), rs, safe, reach)
        assertTrue("minor" in util2.getValue("medic"))
    }

    @Test
    fun matcherRecordsDecisionsAndRanksNearestFirst() {
        val c = claim("c", urgency = 1.0)
        val reach: (Responder, Double) -> Double = { _, _ -> 0.97 }
        val (blocked, decisions) = buildUtilities(listOf(c), listOf(resp("a")), { _, _ -> null }, reach)
        assertEquals("no_route", decisions["a" to "c"])
        assertTrue(blocked.getValue("a").isEmpty())

        val shaky = claim("c", urgency = 1.0, p = 0.2)
        val straight: (Responder, Claim) -> Pair<Double, Double> = { r, claim ->
            hypot(r.pos.first - claim.pos.first, r.pos.second - claim.pos.second) to 0.95
        }
        val (hidden, probe) = buildUtilities(listOf(shaky), listOf(resp("a")), straight, reach)
        assertEquals("probe", probe["a" to "c"])
        assertTrue(hidden.getValue("a").isEmpty())

        val rs = listOf(
            resp("far", pos = 900.0 to 0.0),
            resp("near", pos = 50.0 to 0.0),
            resp("mid", pos = 300.0 to 0.0),
        )
        val (util, _) = buildUtilities(listOf(c), rs, straight, reach)
        assertEquals(listOf("near", "mid", "far"), ranking(util, "c"))
    }

    @Test
    fun cbbaAgreesBreaksTiesAndHealsPartitions() {
        val connected = fleet(
            mapOf(
                "a" to mapOf("x" to 0.9, "y" to 0.5),
                "b" to mapOf("x" to 0.8, "y" to 0.6),
                "c" to mapOf("x" to 0.4, "y" to 0.3),
            ),
        )
        gossip(connected, listOf("a" to "b", "b" to "c"))
        conflictFree(connected)
        assertEquals(mapOf("x" to "a", "y" to "b"), connected.getValue("a").assignment())

        val idle = fleet(mapOf("a" to mapOf("x" to 0.5), "b" to emptyMap()))
        gossip(idle, listOf("a" to "b"))
        assertNull(idle.getValue("b").myTask())

        val tied = fleet(mapOf("b" to mapOf("x" to 0.5), "a" to mapOf("x" to 0.5)))
        gossip(tied, listOf("a" to "b"))
        assertEquals(mapOf("x" to "a"), tied.getValue("b").assignment())

        val islands = fleet(
            mapOf(
                "a1" to mapOf("x" to 0.7, "y" to 0.2),
                "a2" to mapOf("x" to 0.3),
                "b1" to mapOf("x" to 0.9, "y" to 0.4),
                "b2" to mapOf("x" to 0.1),
            ),
        )
        gossip(islands, listOf("a1" to "a2", "b1" to "b2"))
        assertEquals("x", islands.getValue("a1").myTask())
        assertEquals("x", islands.getValue("b1").myTask())
        gossip(islands, listOf("a1" to "a2", "b1" to "b2", "a2" to "b1"))
        conflictFree(islands)
        assertEquals("b1", islands.getValue("a1").assignment().getValue("x"))
        assertEquals("y", islands.getValue("a1").myTask())

        val gone = fleet(mapOf("a" to mapOf("x" to 0.9), "b" to mapOf("x" to 0.5)))
        gossip(gone, listOf("a" to "b"))
        assertNull(gone.getValue("b").myTask())
        gone.getValue("b").forget("a")
        gossip(gone, emptyList())
        assertEquals("x", gone.getValue("b").myTask())

        val changed = fleet(mapOf("a" to mapOf("x" to 0.9, "y" to 0.8), "b" to mapOf("x" to 0.5, "y" to 0.4)))
        gossip(changed, listOf("a" to "b"))
        changed.getValue("a").setUtilities(mapOf("x" to -1.0, "y" to 0.8))
        gossip(changed, listOf("a" to "b"))
        assertEquals(mapOf("y" to "a", "x" to "b"), changed.getValue("a").assignment())
    }

    @Test
    fun signedEntriesRejectForgeriesAndStillConverge() {
        val signer = HexSigner()
        val a = Bidder("a", mapOf("x" to 0.9), signer)
        val b = Bidder("b", mapOf("x" to 0.4), signer)
        val forged = Entry(99, "x", 1.0, "deadbeef")
        assertFalse(b.merge(mapOf("a" to forged)))
        assertFalse(b.table.containsKey("a"))
        gossip(linkedMapOf("a" to a, "b" to b), listOf("a" to "b"))
        assertEquals("x", a.myTask())
        assertNull(b.myTask())
        assertEquals(mapOf("x" to "a"), a.assignment())
    }

    @Test
    fun randomConnectedAuctionsStayConflictFree() {
        val rng = Random(7)
        repeat(40) {
            val n = rng.nextInt(2, 9)
            val m = rng.nextInt(1, 9)
            val ids = (0 until n).map { "r$it" }
            val util = LinkedHashMap<String, Map<String, Double>>()
            for (id in ids) {
                util[id] = (0 until m).associate { j -> "t$j" to rng.nextDouble(-0.2, 1.0) }
            }
            val bs = fleet(util)
            val links = ArrayList<Pair<String, String>>()
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    if (rng.nextDouble() < 0.6) links.add(ids[i] to ids[j])
                }
            }
            for (i in 0 until n - 1) links.add(ids[i] to ids[i + 1])
            gossip(bs, links)
            conflictFree(bs)
            val view = bs.getValue(ids.first()).assignment()
            for ((agent, bids) in util) {
                if (bs.getValue(agent).myTask() == null) {
                    for ((task, value) in bids) {
                        if (value > 0.0) {
                            assertTrue(task in view, task)
                            val owner = view.getValue(task)
                            assertTrue(bs.getValue(owner).utilities.getValue(task) + 1e-9 > value)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun escalationTimeoutAckDeclineAndReferral() {
        val timed = Escalation("c", listOf("a", "b"), now = 0.0, ackTimeout = 100.0)
        assertEquals("a", timed.current)
        assertNull(timed.tick(99.0))
        assertEquals("b", timed.tick(100.0))
        assertNull(timed.tick(250.0))
        assertEquals(EXHAUSTED, timed.state)
        assertNull(timed.current)

        val acked = Escalation("c", listOf("a", "b"), 0.0)
        assertTrue(acked.ack("a", 10.0))
        assertNull(acked.tick(10_000.0))
        assertEquals("a", acked.current)

        val declined = Escalation("c", listOf("a", "b", "c"), 0.0)
        assertEquals("a", declined.decline("b", 5.0))
        assertEquals("b", declined.decline("a", 5.0))

        val referred = Escalation("c", listOf("a", "b", "z"), 0.0)
        assertEquals("z", referred.refer("a", "z", 5.0))
        assertEquals(listOf("a", "z", "b"), referred.ranked)

        assertEquals(EXHAUSTED, Escalation("c", emptyList(), 0.0).state)
    }

    private fun conflictFree(bs: Map<String, Bidder>) {
        val views = bs.values.map { it.assignment() }
        assertTrue(views.all { it == views.first() }, views.toString())
        val mine = bs.values.mapNotNull { it.myTask() }
        assertEquals(mine.toSet().size, mine.size)
    }

    private fun fleet(util: Map<String, Map<String, Double>>): LinkedHashMap<String, Bidder> {
        val out = LinkedHashMap<String, Bidder>()
        for ((agent, bids) in util) out[agent] = Bidder(agent, bids)
        return out
    }
}

private class HexSigner : Signer {
    override fun sign(node: String, data: ByteArray): String = node + ":" + hex(data)

    override fun verify(node: String, data: ByteArray, sig: String): Boolean = sig == node + ":" + hex(data)

    private fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
}

private fun utf8(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)

private val MEDIC = setOf("medic")

private fun claim(
    id: String,
    urgency: Double = 0.5,
    need: String? = null,
    p: Double = 0.9,
    gain: Double = 0.5,
    pos: Pos = 0.0 to 0.0,
    trust: Double = 0.8,
) = Claim(id, pos, p, urgency, gain, need, trust)

private fun resp(id: String, skills: Set<String> = emptySet(), pos: Pos = 0.0 to 0.0) =
    Responder(id, pos, skills, 1.0)
