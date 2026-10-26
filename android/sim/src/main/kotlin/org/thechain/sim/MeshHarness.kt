package org.thechain.sim

import org.thechain.core.Clock
import org.thechain.core.Signer
import org.thechain.cryptofake.HmacSigner
import org.thechain.node.PhoneNode
import org.thechain.transport.SimMedium
import org.thechain.transport.SimRadio
import kotlin.random.Random

/**
 * JVM multi-node harness over an in-memory [SimMedium].
 * Not packaged into :app.
 */
class MeshHarness(
    val ids: List<String>,
    val signer: Signer = HmacSigner(ByteArray(32) { 1 }),
    mediumIn: SimMedium? = null,
    startMs: Long = 1_000_000L,
    seed: Long = 0L,
    fullyConnected: Boolean = true,
) {
    var nowMs: Long = startMs
        private set

    val clock: Clock = Clock { nowMs }

    val medium: SimMedium = mediumIn ?: SimMedium(rng = Random(seed))

    val nodes: Map<String, PhoneNode> = ids.associateWith { id ->
        PhoneNode(id, SimRadio(id, medium), signer, clock)
    }

    init {
        if (fullyConnected) meshAll()
    }

    fun nowS(): Double = nowMs / 1000.0

    fun advance(ms: Long = 1000) {
        nowMs += ms
    }

    /** Advance the clock and tick every node once. */
    fun tickAll(dtMs: Long = 1000) {
        advance(dtMs)
        val t = nowS()
        for (n in nodes.values) n.tick(t)
    }

    fun run(ticks: Int, dtMs: Long = 1000) {
        repeat(ticks) { tickAll(dtMs) }
    }

    fun meshAll() {
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                medium.link(ids[i], ids[j])
            }
        }
    }

    fun partition(groupA: Collection<String>, groupB: Collection<String>) {
        val a = groupA.toSet()
        val b = groupB.toSet()
        for (left in a) {
            for (right in b) {
                medium.unlink(left, right)
            }
        }
    }

    fun heal(groupA: Collection<String>, groupB: Collection<String>) {
        val a = groupA.toSet()
        val b = groupB.toSet()
        for (left in a) {
            for (right in b) {
                medium.link(left, right)
            }
        }
    }

    fun markBlackhole(id: String) {
        medium.blackholes.add(id)
    }

    fun clearBlackhole(id: String) {
        medium.blackholes.remove(id)
    }

    fun markGreyhole(id: String, dropProb: Double) {
        medium.greyhole[id] = dropProb
    }

    /**
     * Push one anti-entropy summary request from each node to each radio neighbour.
     * Catch-up when epidemic gossip alone is insufficient after a heal.
     */
    fun exchangeAntiEntropy() {
        val t = nowS()
        for (n in nodes.values) {
            for (peer in n.node.neighbors.keys.toList()) {
                val req = n.ae.request(peer, t) ?: continue
                n.node.sendFrame(peer, req.toMutableMap(), t)
            }
        }
        // Deliver AE frames + any follow-up batches.
        tickAll(200)
        tickAll(200)
        tickAll(200)
    }

    /** Per-origin event log heads: nodeId -> (seq, tipHash) for every replica. */
    fun replicaHeads(): Map<String, Map<String, Pair<Int, String>>> =
        nodes.mapValues { (_, pn) ->
            pn.replica.summary().mapValues { (_, v) ->
                (v[0] as Number).toInt() to (v[1] as String)
            }
        }

    fun claimPresent(claimId: String): Set<String> =
        nodes.filter { (_, pn) -> pn.replica.book.claims.get(claimId) != null }.keys
}
