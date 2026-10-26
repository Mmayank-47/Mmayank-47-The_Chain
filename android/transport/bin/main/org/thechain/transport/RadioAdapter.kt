package org.thechain.transport

import kotlin.random.Random

/** Radio abstraction (BLE / Wi-Fi Aware / LoRa plug in here). */
interface Radio {
    fun broadcast(data: ByteArray, now: Double)
    fun send(to: String, data: ByteArray, now: Double): Boolean
    fun poll(now: Double): List<Pair<String, ByteArray>>
}

/** Shared air. Links are undirected; loss and latency are per frame. */
class SimMedium(
    val loss: Double = 0.0,
    val latencyS: Double = 0.0,
    val rng: Random = Random(0),
) {
    val links: MutableMap<String, MutableSet<String>> = mutableMapOf()
    /** Senders that accept TX accounting but never deliver (blackhole). */
    val blackholes: MutableSet<String> = mutableSetOf()
    /** Extra per-sender drop probability on top of [loss] (greyhole). */
    val greyhole: MutableMap<String, Double> = mutableMapOf()
    private val q = mutableListOf<Quad>()
    var framesSent: Int = 0
        private set
    var bytesSent: Int = 0
        private set
    var framesDroppedHostile: Int = 0
        private set

    private data class Quad(val t: Double, val to: String, val frm: String, val data: ByteArray)

    fun link(a: String, b: String) {
        links.getOrPut(a) { mutableSetOf() }.add(b)
        links.getOrPut(b) { mutableSetOf() }.add(a)
    }

    fun unlink(a: String, b: String) {
        links[a]?.remove(b)
        links[b]?.remove(a)
    }

    fun isolate(a: String) {
        for (b in links[a]?.toList().orEmpty()) unlink(a, b)
    }

    fun transmit(frm: String, to: String, data: ByteArray, now: Double): Boolean {
        if (to !in (links[frm] ?: emptySet())) return false
        framesSent += 1
        bytesSent += data.size
        if (frm in blackholes) {
            framesDroppedHostile += 1
            return true
        }
        val hostile = greyhole[frm] ?: 0.0
        val dropP = maxOf(loss, hostile)
        if (rng.nextDouble() < dropP) {
            if (hostile > 0.0) framesDroppedHostile += 1
            return true
        }
        q.add(Quad(now + latencyS, to, frm, data))
        return true
    }

    fun popFor(node: String, now: Double): List<Pair<String, ByteArray>> {
        val ready = mutableListOf<Quad>()
        val rest = mutableListOf<Quad>()
        for (e in q) {
            if (e.to == node && e.t <= now) ready.add(e) else rest.add(e)
        }
        q.clear()
        q.addAll(rest)
        return ready.sortedBy { it.t }.map { it.frm to it.data }
    }
}

class SimRadio(val id: String, val medium: SimMedium) : Radio {
    override fun broadcast(data: ByteArray, now: Double) {
        for (peer in medium.links[id]?.toList().orEmpty()) {
            medium.transmit(id, peer, data, now)
        }
    }

    override fun send(to: String, data: ByteArray, now: Double): Boolean =
        medium.transmit(id, to, data, now)

    override fun poll(now: Double): List<Pair<String, ByteArray>> = medium.popFor(id, now)
}
