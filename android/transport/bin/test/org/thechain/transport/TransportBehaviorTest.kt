package org.thechain.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.BloomFilter
import org.thechain.core.Hlc
import org.thechain.core.Message
import org.thechain.core.Priority
import org.thechain.core.TokenBucket
import kotlin.random.Random

private fun msg(
    i: Int,
    prio: Priority = Priority.DISTRESS,
    origin: String = "n0",
    cell: String? = null,
    now: Double = 0.0,
    ttl: Double = 3600.0,
): Message = Message(
    "t",
    prio,
    origin,
    mapOf("i" to i),
    Hlc(1, i, origin),
    destCell = cell,
    ttlS = ttl,
    created = now,
)

private fun runNodes(
    nodes: List<MeshNode>,
    t0: Double,
    t1: Double,
    dt: Double = 1.0,
    hook: ((Double) -> Unit)? = null,
): Double {
    var t = t0
    while (t < t1) {
        hook?.invoke(t)
        for (n in nodes) n.tick(t)
        t += dt
    }
    return t
}

/** Minimal enrolled/stranger limiter matching m1 IdentityLimiter behaviour used by test_m0. */
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

class TransportBehaviorTest {
    @Test
    fun priorityOrderDedupeEviction() {
        val q = PriorityOutbox(capacity = 3)
        q.push("c", Priority.CHATTER, "c")
        q.push("h", Priority.HAZARD, "h")
        q.push("d", Priority.DISTRESS, "d")
        assertFalse(q.push("d", Priority.DISTRESS, "dup"))
        assertFalse(q.push("c2", Priority.CHATTER, "c2"))
        assertTrue(q.push("d2", Priority.DISTRESS, "d2"))
        assertEquals(listOf("d", "d2", "h"), List(3) { q.pop() })
        assertNull(q.pop())
    }

    @Test
    fun storeExpiryDedupeAndEviction() {
        val s = CarryStore(capacity = 2)
        assertTrue(s.add(msg(1, Priority.CHATTER, ttl = 10.0), 2, 0.0))
        assertFalse(s.add(msg(1, Priority.CHATTER, ttl = 10.0), 2, 0.0))
        s.add(msg(2, Priority.HAZARD), null, 0.0)
        assertTrue(s.add(msg(3, Priority.DISTRESS), null, 0.0))
        assertEquals(2, s.size)
        s.expire(100.0)
        assertEquals(2, s.size)
        s.expire(4000.0)
        assertEquals(0, s.size)
    }

    @Test
    fun sprayHalvingAndWaitPhase() {
        val c = Carried(msg(1, Priority.CHATTER, cell = "X"), 4, 0.0)
        assertEquals(Triple(true, 2, 2), planHandoff(c, "Y"))
        c.tokens = 1
        assertEquals(Triple(false, 0, 1), planHandoff(c, "Y"))
        assertEquals(Triple(true, 1, 0), planHandoff(c, "X"))
    }

    @Test
    fun tokenClamp() {
        assertEquals(4, SprayPolicy().clamp(Priority.CHATTER, 10_000))
    }

    @Test
    fun bloom() {
        val b = BloomFilter.forCapacity(100)
        for (i in 0 until 100) b.add("id$i")
        assertTrue((0 until 100).all { "id$it" in b })
        val fp = (0 until 1000).count { "x$it" in b }
        assertTrue(fp < 40)
    }

    private fun line(n: Int, loss: Double = 0.0): Triple<SimMedium, List<MeshNode>, MutableMap<String, Message>> {
        val m = SimMedium(loss = loss, rng = Random(3))
        val got = mutableMapOf<String, Message>()
        val nodes = (0 until n).map { i ->
            val nid = "n$i"
            buildSimNode(nid, m, onDeliver = { ms -> got.putIfAbsent(nid, ms) })
        }
        for (i in 0 until n - 1) m.link("n$i", "n${i + 1}")
        return Triple(m, nodes, got)
    }

    @Test
    fun multihopDistress() {
        val (_, nodes, got) = line(6)
        nodes[0].originate(msg(1), 0.0)
        runNodes(nodes, 0.0, 30.0)
        assertTrue("n5" in got)
    }

    @Test
    fun priorityUnderTightBudget() {
        val (_, nodes, _) = line(2)
        nodes[0].engine.byteBudget = 1
        nodes[0].originate(msg(1, Priority.CHATTER), 0.0)
        nodes[0].originate(msg(2, Priority.DISTRESS), 0.0)
        val order = mutableListOf<Priority>()
        nodes[1].engine.onDeliver = { ms -> order.add(ms.priority) }
        runNodes(nodes, 0.0, 12.0)
        assertEquals(Priority.DISTRESS, order[0])
        assertEquals(2, order.size)
    }

    @Test
    fun survivesLoss() {
        val (_, nodes, got) = line(5, loss = 0.4)
        nodes[0].originate(msg(1), 0.0)
        runNodes(nodes, 0.0, 120.0)
        assertTrue("n4" in got)
    }

    @Test
    fun lowBatteryShrinksFanout() {
        val e = buildSimNode("x", SimMedium()).engine
        assertEquals(3, e.fanout(10, 5, 1.0, Priority.HAZARD))
        assertEquals(1, e.fanout(10, 5, 0.1, Priority.HAZARD))
        assertEquals(2, e.fanout(10, 5, 0.1, Priority.DISTRESS))
        assertEquals(2, e.fanout(10, 20, 1.0, Priority.HAZARD))
    }

    @Test
    fun malformedFrameIgnored() {
        val (m, nodes, _) = line(2)
        m.transmit("n0", "n1", "\u00ff not json".toByteArray(Charsets.ISO_8859_1), 0.0)
        runNodes(nodes, 0.0, 3.0)
    }

    @Test
    fun muleCarriesAcrossPartition() {
        val m = SimMedium(rng = Random(5))
        val got = mutableMapOf<String, Message>()
        fun mk(n: String) = buildSimNode(n, m, onDeliver = { ms -> got.putIfAbsent(n, ms) })
        val a1 = mk("a1")
        val a2 = mk("a2")
        val mule = mk("mule")
        val b1 = mk("b1")
        val b2 = mk("b2")
        m.link("a1", "a2")
        m.link("b1", "b2")
        m.link("mule", "a1")
        val nodes = listOf(a1, a2, mule, b1, b2)
        a1.originate(msg(1, Priority.DISTRESS, origin = "a1"), 0.0)
        var t = runNodes(nodes, 0.0, 15.0)
        assertTrue("mule" in got)
        assertTrue("a2" in got)
        assertFalse("b1" in got)
        m.isolate("mule")
        runNodes(nodes, t, t + 10.0)
        assertFalse("b1" in got)
        m.link("mule", "b1")
        runNodes(nodes, t + 10.0, t + 30.0)
        assertTrue("b1" in got)
        assertTrue("b2" in got)
    }

    @Test
    fun originQuotaBlocksExcessAndCannotExceed() {
        val s = CarryStore(capacity = 512, maxPerOrigin = 5, maxPerNeighbour = 10)
        for (i in 0 until 5) {
            assertTrue(s.add(msg(i, Priority.DISTRESS, origin = "attacker"), null, 0.0))
        }
        assertEquals(5, s.originCount("attacker"))
        assertFalse(s.add(msg(5, Priority.DISTRESS, origin = "attacker"), null, 0.0))
        assertEquals(5, s.originCount("attacker"))
        assertEquals(5, s.size)
        assertTrue(s.add(msg(100, Priority.DISTRESS, origin = "honest"), null, 0.0))
        assertEquals(1, s.originCount("honest"))
        assertEquals(6, s.size)
    }

    @Test
    fun originQuotaEvictsLowerPriorityFromSameOrigin() {
        val s = CarryStore(capacity = 512, maxPerOrigin = 3, maxPerNeighbour = 10)
        s.add(msg(1, Priority.CHATTER, origin = "n0"), 2, 0.0)
        s.add(msg(2, Priority.HAZARD, origin = "n0"), null, 0.0)
        s.add(msg(3, Priority.DISTRESS, origin = "n0"), null, 0.0)
        assertEquals(3, s.originCount("n0"))
        assertTrue(s.add(msg(4, Priority.DISTRESS, origin = "n0"), null, 1.0))
        assertEquals(3, s.originCount("n0"))
        assertNull(s.get(msg(1, Priority.CHATTER, origin = "n0").msgId))
        assertNotNull(s.get(msg(4, Priority.DISTRESS, origin = "n0").msgId))
    }

    @Test
    fun neighbourQuotaBlocksPeerExhaustion() {
        val s = CarryStore(capacity = 512, maxPerOrigin = 50, maxPerNeighbour = 4)
        for (i in 0 until 4) {
            assertTrue(
                s.add(
                    msg(i, Priority.DISTRESS, origin = "orig_$i"),
                    null,
                    0.0,
                    fromId = "bad_neighbour",
                ),
            )
        }
        assertEquals(4, s.neighbourCount("bad_neighbour"))
        assertFalse(
            s.add(
                msg(99, Priority.DISTRESS, origin = "orig_99"),
                null,
                0.0,
                fromId = "bad_neighbour",
            ),
        )
        assertEquals(4, s.neighbourCount("bad_neighbour"))
        assertTrue(
            s.add(
                msg(100, Priority.DISTRESS, origin = "orig_100"),
                null,
                0.0,
                fromId = "good_neighbour",
            ),
        )
        assertEquals(1, s.neighbourCount("good_neighbour"))
    }

    @Test
    fun removalAndExpiryFreesQuota() {
        val s = CarryStore(capacity = 512, maxPerOrigin = 2, maxPerNeighbour = 2)
        val m1 = msg(1, Priority.DISTRESS, origin = "n0", ttl = 10.0)
        val m2 = msg(2, Priority.DISTRESS, origin = "n0", ttl = 100.0)
        s.add(m1, null, 0.0, fromId = "p1")
        s.add(m2, null, 0.0, fromId = "p1")
        assertEquals(2, s.originCount("n0"))
        assertEquals(2, s.neighbourCount("p1"))
        assertFalse(s.add(msg(3, Priority.DISTRESS, origin = "n0"), null, 0.0, fromId = "p1"))
        s.expire(20.0)
        assertEquals(1, s.originCount("n0"))
        assertEquals(1, s.neighbourCount("p1"))
        assertTrue(s.add(msg(3, Priority.DISTRESS, origin = "n0"), null, 20.0, fromId = "p1"))
    }

    @Test
    fun badOrEdgeCaseInputs() {
        val s = CarryStore(capacity = 10, maxPerOrigin = 2, maxPerNeighbour = 2)
        assertTrue(s.add(msg(1, origin = "x"), null, 0.0, fromId = null))
        assertEquals(0, s.neighbourCount(""))
        assertFalse(s.add(msg(1, origin = "x"), null, 0.0, fromId = null))
    }

    @Test
    fun strangerDistressAllowedExactlyOnce() {
        val lim = TestLimiter(
            enrolled = setOf("enrolled_peer"),
            baseRate = 1.0,
            baseBurst = 2.0,
            strangerBurst = 1.0,
        )
        val g = IngressGuard(limiter = lim, strangerLane = true)
        val m1 = Message(
            "m9.case",
            Priority.DISTRESS,
            "stranger1",
            mapOf("c" to 1),
            Hlc(1, 1, "stranger1"),
            ttlS = 3600.0,
        )
        val m2 = Message(
            "m9.case",
            Priority.DISTRESS,
            "stranger1",
            mapOf("c" to 2),
            Hlc(1, 2, "stranger1"),
            ttlS = 3600.0,
        )
        assertNotNull(g.admit(m1, "stranger1", 0.0))
        assertNull(g.admit(m2, "stranger1", 0.0))
        assertEquals(1, g.dropped["rate_limited"])
    }

    @Test
    fun strangerNonDistressRejected() {
        val lim = TestLimiter(enrolled = setOf("enrolled_peer"), strangerBurst = 1.0)
        val g = IngressGuard(limiter = lim, strangerLane = true)
        val mDispatch = Message(
            "m9.sealed",
            Priority.DISPATCH,
            "stranger1",
            mapOf("b" to "hex"),
            Hlc(1, 1, "stranger1"),
            ttlS = 3600.0,
        )
        assertNull(g.admit(mDispatch, "stranger1", 0.0))
    }

    @Test
    fun enrolledNodeDoesNotConsumeStrangerLane() {
        val lim = TestLimiter(
            enrolled = setOf("peer"),
            baseRate = 0.0,
            baseBurst = 1.0,
            strangerBurst = 1.0,
        )
        val g = IngressGuard(limiter = lim, strangerLane = true)
        val m1 = Message(
            "m9.case",
            Priority.DISTRESS,
            "peer",
            mapOf("c" to 1),
            Hlc(1, 1, "peer"),
            ttlS = 3600.0,
        )
        val m2 = Message(
            "m9.case",
            Priority.DISTRESS,
            "peer",
            mapOf("c" to 2),
            Hlc(1, 2, "peer"),
            ttlS = 3600.0,
        )
        assertNotNull(g.admit(m1, "peer", 0.0))
        assertNull(g.admit(m2, "peer", 0.0))
    }

    @Test
    fun wireV2RejectsFloatsInSignedBodies() {
        try {
            WireV2.encode(mapOf("x" to 1.5))
            assertTrue(false, "expected float rejection")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("floats"))
        }
    }

    @Test
    fun wireV2CborRoundTripByteExact() {
        val body = linkedMapOf<String, Any?>(
            "bat_milli" to 950,
            "cell" to "1:2",
            "flags" to listOf(1, 2, 3),
            "id" to "node-a",
            "nested" to mapOf("k" to "v", "n" to -3L),
            "nonce" to byteArrayOf(1, 2, 3, 4),
            "ok" to true,
            "pos_udeg" to listOf(19_076_090, 72_877_392),
            "t_ms" to 1_700_000_000_000L,
            "z" to null,
        )
        val enc = WireV2.encode(body)
        val dec = WireV2.decode(enc)
        val re = WireV2.encode(dec)
        assertTrue(enc.contentEquals(re), "encode(decode(x)) must be byte-identical")
        assertEquals("node-a", dec["id"])
        assertEquals(950L, (dec["bat_milli"] as Number).toLong())
        assertEquals(true, dec["ok"])
        assertNull(dec["z"])
    }

    @Test
    fun wireV2MalformedFuzzRejected() {
        val rng = Random(99)
        repeat(40) {
            val junk = ByteArray(rng.nextInt(1, 64)) { rng.nextInt(0, 256).toByte() }
            try {
                WireV2.decode(junk)
            } catch (_: Exception) {
                // expected: never partially trust
            }
        }
        // Explicit float half/float/double simple values
        try {
            WireV2.decode(byteArrayOf(0xf9.toByte(), 0x00, 0x00))
            assertTrue(false, "half-float must be rejected")
        } catch (_: Exception) {
        }
        try {
            WireV2.decode(byteArrayOf(0xa1.toByte(), 0x61, 'a'.code.toByte(), 0xf5.toByte(), 0x00))
            assertTrue(false, "trailing bytes must be rejected")
        } catch (_: Exception) {
        }
    }
}
