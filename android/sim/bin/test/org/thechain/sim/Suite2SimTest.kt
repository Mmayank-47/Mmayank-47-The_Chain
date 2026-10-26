package org.thechain.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.Hlc
import org.thechain.core.MAX_DRIFT_MS
import org.thechain.core.Message
import org.thechain.core.Priority
import org.thechain.core.canonical
import org.thechain.cryptofake.HmacSigner
import org.thechain.identity.Handshake
import org.thechain.identity.HandshakeTable
import org.thechain.matching.Bidder
import org.thechain.matching.gossip
import org.thechain.merge.Event
import org.thechain.transport.GossipEngine
import org.thechain.transport.NeighborInfo
import org.thechain.transport.ReputationSink
import org.thechain.transport.Watchdog
import kotlin.random.Random

/**
 * Suite 2 — JVM multi-node sim (logic only). Never claim phone proof.
 */
class Suite2SimTest {
    @Test
    fun partitionHealConvergesClaimsAndHeads() {
        val left = listOf("A", "B")
        val right = listOf("C", "D")
        val harness = MeshHarness(left + right, seed = 42L)
        harness.run(4)

        harness.partition(left, right)
        harness.nodes.getValue("A").openClaim("claim-part-1", "0:1", 0.8)
        harness.nodes.getValue("C").openClaim("claim-part-2", "2:3", 0.6)
        harness.run(10)

        assertTrue("A" in harness.claimPresent("claim-part-1"))
        assertTrue("B" in harness.claimPresent("claim-part-1"))
        assertFalse("C" in harness.claimPresent("claim-part-1"))
        assertTrue("C" in harness.claimPresent("claim-part-2"))
        assertFalse("A" in harness.claimPresent("claim-part-2"))

        harness.heal(left, right)
        harness.run(6)
        harness.exchangeAntiEntropy()
        harness.run(8)

        assertEquals(4, harness.claimPresent("claim-part-1").size)
        assertEquals(4, harness.claimPresent("claim-part-2").size)

        val heads = harness.replicaHeads()
        val tipA = heads.getValue("A")
        for (id in harness.ids) {
            assertEquals(tipA, heads.getValue(id), "replica heads must converge after heal+AE ($id)")
        }
    }

    @Test
    fun cbbaDuplicateDispatchOneWinnerAfterHeal() {
        // CBBA: two islands both assign the same task; after heal, exactly one winner.
        val islands = linkedMapOf(
            "r1" to Bidder("r1", mapOf("task-x" to 0.7, "task-y" to 0.2)),
            "r2" to Bidder("r2", mapOf("task-x" to 0.4)),
            "r3" to Bidder("r3", mapOf("task-x" to 0.9, "task-y" to 0.3)),
            "r4" to Bidder("r4", mapOf("task-x" to 0.1, "task-y" to 0.5)),
        )
        gossip(islands, listOf("r1" to "r2", "r3" to "r4"))
        assertEquals("task-x", islands.getValue("r1").myTask())
        assertEquals("task-x", islands.getValue("r3").myTask())

        gossip(islands, listOf("r1" to "r2", "r3" to "r4", "r2" to "r3"))
        val assign = islands.getValue("r1").assignment()
        assertEquals(assign, islands.getValue("r3").assignment())
        assertEquals("r3", assign["task-x"], "higher bid must win after heal")
        assertNull(islands.getValue("r1").myTask(), "loser must not keep duplicate accept")
        assertEquals("task-x", islands.getValue("r3").myTask())
        val mine = islands.values.mapNotNull { it.myTask() }
        assertEquals(mine.toSet().size, mine.size, "no double-accept after heal")

        // DispatchBook lease path on the mesh: two responders propose; plan keeps one winner.
        val harness = MeshHarness(listOf("V", "R1", "R2"), seed = 7L)
        harness.run(3)
        harness.nodes.getValue("V").openClaim("c-lease", "1:1", 0.9)
        harness.run(6)
        harness.nodes.getValue("R1").replica.dispatch("c-lease", "R1")
        harness.nodes.getValue("R2").replica.dispatch("c-lease", "R2")
        harness.run(8)
        harness.exchangeAntiEntropy()
        harness.run(4)

        val winnerDid = harness.nodes.getValue("V").replica.plan().winners.getValue("c-lease")
        assertEquals("c-lease|R1", winnerDid, "earlier/lexicographic lease wins")
        for (id in listOf("V", "R1", "R2")) {
            val plan = harness.nodes.getValue(id).replica.plan()
            assertEquals(1, plan.winners.size, "node $id must pick exactly one live winner")
            assertEquals(winnerDid, plan.winners["c-lease"])
            assertTrue(plan.redirect.contains("c-lease|R2"), "loser dispatch redirected")
        }
    }

    @Test
    fun blackholeGreyholeWatchdogFailover() {
        val sink = CountingRep()
        val wd = Watchdog(sink, ackTimeoutS = 6.0, trustThreshold = 0.35)
        val engine = GossipEngine(
            "sender",
            watchdog = wd,
            baseFanout = 1,
            rng = Random(11),
            trustFn = { n, now -> sink.mean(n, now) },
        )
        // CHATTER keeps fanout at baseFanout=1 so the first hop is deterministic (Python phase-6).
        val msg = Message(
            "m8.event",
            Priority.CHATTER,
            "sender",
            mapOf("c" to "bh-1"),
            Hlc(1, 1, "sender"),
            created = 0.0,
        )
        assertTrue(engine.originate(msg, 0.0))
        val neighbors = mapOf(
            "bad" to NeighborInfo("bad", battery = 1.0),
            "good" to NeighborInfo("good", battery = 0.7),
        )
        val out1 = engine.step(neighbors, 0.0)
        assertEquals(listOf("bad"), out1.map { it.to })
        assertTrue(wd.isTrusted("bad", 0.0), "peer starts trusted before deadline")

        val out2 = engine.step(neighbors, 7.0)
        assertTrue(sink.failures("bad") >= 1, "watchdog must record forward_failure")
        assertFalse(wd.isTrusted("bad", 7.0), "blackhole peer must drop below trust threshold")
        assertTrue(out2.any { it.to == "good" }, "traffic must failover to trusted alternate")

        // Greyhole on SimMedium: partial drops still advance hostile counter; mesh stays alive.
        val harness = MeshHarness(listOf("A", "BH", "C"), seed = 19L, fullyConnected = false)
        harness.medium.link("A", "BH")
        harness.medium.link("BH", "C")
        harness.medium.link("A", "C")
        harness.markGreyhole("BH", 0.85)
        harness.run(4)
        harness.nodes.getValue("A").openClaim("claim-gh", "0:0", 0.95)
        harness.run(20)
        assertNotNull(harness.nodes.getValue("C").replica.book.claims.get("claim-gh"))
        assertTrue(harness.medium.framesDroppedHostile > 0)
        // Direct A–C path keeps the mesh alive despite greyhole BH.
        assertTrue(harness.nodes.getValue("A").node.neighbors.isNotEmpty())
    }

    @Test
    fun replayForgedOversizeFramesDropped() {
        val signer = HmacSigner(ByteArray(32) { 3 })
        val harness = MeshHarness(listOf("A", "B"), signer = signer, seed = 3L)
        harness.run(4)
        val a = harness.nodes.getValue("A")
        val b = harness.nodes.getValue("B")
        val beforeB = b.guard.dropped.toMap()

        // Oversize raw frame before decode.
        val huge = ByteArray(20_000) { 0x41 }
        assertFalse(b.guard.frameOk(huge))
        assertTrue((b.guard.dropped["frame_size"] ?: 0) > (beforeB["frame_size"] ?: 0))

        // Forge: spoofed sender / bad signature.
        val forged = mapOf(
            "t" to "hello",
            "id" to "A",
            "snd" to "A",
            "seq" to 999_001,
            "sig" to "deadbeef",
            "bat" to 1.0,
            "bf" to listOf(128, 3, "0"),
            "cell" to null,
        )
        assertNull(b.guard.authenticateFrame("A", forged))
        assertTrue((b.guard.dropped["frame_bad_sig"] ?: 0) >= 1)

        // Replay: authenticate once, then same seq again.
        val body = mutableMapOf<String, Any?>(
            "t" to "hello",
            "id" to "A",
            "snd" to "A",
            "seq" to 42,
            "bat" to 1.0,
            "bf" to listOf(128, 3, "0"),
            "cell" to null,
        )
        body["sig"] = signer.sign("A", canonical(body.filterKeys { it != "sig" }))
        assertEquals("A", b.guard.authenticateFrame("A", body))
        assertNull(b.guard.authenticateFrame("A", body))
        assertTrue((b.guard.dropped["frame_replayed"] ?: 0) >= 1)

        // Mesh still alive: claim propagates after hostile frames.
        a.openClaim("claim-alive", "9:9", 0.5)
        harness.run(10)
        assertNotNull(b.replica.book.claims.get("claim-alive"))
        assertTrue(a.node.neighbors.containsKey("B"))
        assertTrue(b.node.neighbors.containsKey("A"))
    }

    @Test
    fun clockDriftAttackRejected() {
        val harness = MeshHarness(listOf("A", "B"), seed = 5L)
        harness.run(3)
        val victim = harness.nodes.getValue("B")
        val before = victim.replica.rejectedDrift

        val farFuture = harness.nowMs + MAX_DRIFT_MS + 60_000
        val driftEv = Event.make(
            "evil",
            0,
            "",
            Hlc(farFuture, 0, "evil"),
            "claim",
            mapOf("claim" to "skew-claim", "cell" to "0:0", "urgency" to 0.1),
            harness.signer,
        )
        assertEquals(0, victim.replica.ingest(listOf(driftEv)))
        assertTrue(victim.replica.rejectedDrift > before)
        assertNull(victim.replica.book.claims.get("skew-claim"))

        // Handshake skew beyond 5-minute window.
        val table = HandshakeTable(skewMs = 5 * 60 * 1000)
        val now = harness.nowMs
        val body = HandshakeTable.body("peer", "ep", "aa11aa11aa11aa11", "bb22bb22bb22bb22", now + 10 * 60 * 1000)
        val hs = Handshake(
            "peer",
            "ep",
            "aa11aa11aa11aa11",
            "bb22bb22bb22bb22",
            now + 10 * 60 * 1000,
            harness.signer.sign("peer", canonical(body)),
        )
        assertNull(table.accept("local", "bb22bb22bb22bb22", hs, now, harness.signer))
    }

    @Test
    fun fiftyNodeRandomizedSoak() {
        val n = 50
        val seed = 20261026L
        val rng = Random(seed)
        val ids = (0 until n).map { "n$it" }
        val harness = MeshHarness(ids, seed = seed, fullyConnected = false)

        // Sparse random connected graph (bounded degree) for CI time.
        for (i in 0 until n - 1) harness.medium.link(ids[i], ids[i + 1])
        repeat(80) {
            val a = ids[rng.nextInt(n)]
            val b = ids[rng.nextInt(n)]
            if (a != b) harness.medium.link(a, b)
        }

        harness.run(6)
        val claimCount = 12
        for (i in 0 until claimCount) {
            val origin = ids[rng.nextInt(n)]
            harness.nodes.getValue(origin).openClaim("soak-$i", "${i % 7}:${i % 5}", 0.3 + (i % 5) * 0.1)
            harness.run(2)
        }
        harness.run(40)
        // Occasional AE catch-up rounds.
        repeat(3) { harness.exchangeAntiEntropy() }
        harness.run(10)

        var present = 0
        for (i in 0 until claimCount) {
            present += harness.claimPresent("soak-$i").size
        }
        val avgCoverage = present.toDouble() / (claimCount * n)
        assertTrue(avgCoverage >= 0.85, "soak coverage $avgCoverage must show eventual progress")
        assertTrue(harness.medium.framesSent > 0)
        // No crash: every node still ticks and has a stable id.
        assertEquals(n, harness.nodes.size)
        harness.tickAll()
        for (id in ids) {
            assertEquals(id, harness.nodes.getValue(id).id)
        }
    }

    private class CountingRep : ReputationSink {
        private val fails = mutableMapOf<String, Int>()
        private val ok = mutableMapOf<String, Int>()

        override fun record(relay: String, kind: String, now: Double) {
            when (kind) {
                "forward_failure" -> fails[relay] = (fails[relay] ?: 0) + 1
                "forward_success" -> ok[relay] = (ok[relay] ?: 0) + 1
            }
        }

        override fun mean(relay: String, now: Double): Double {
            val f = fails[relay] ?: 0
            val s = ok[relay] ?: 0
            // Mirror BetaRep cold-start prior (1,1) with heavy failure weight.
            val a = 1.0 + 0.5 * s
            val b = 1.0 + 3.0 * f
            return a / (a + b)
        }

        fun failures(relay: String): Int = fails[relay] ?: 0
    }
}
