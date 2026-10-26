package org.thechain.transport

import org.thechain.core.BloomFilter
import org.thechain.core.CanonicalJson
import org.thechain.core.Message
import org.thechain.core.Priority
import org.thechain.core.Signer
import org.thechain.core.canonical
import org.thechain.core.parseJsonObject
import kotlin.random.Random

/** MeshNode: beacons + neighbor table + gossip engine on top of any Radio. */
class MeshNode(
    val id: String,
    val radio: Radio,
    val engine: GossipEngine,
    val helloInterval: Double = 2.0,
    val neighborTimeout: Double = 6.0,
    val getCell: () -> String? = { null },
    val getBattery: () -> Double = { 1.0 },
    val guard: IngressGuard? = null,
    val signer: Signer? = null,
    val compactWire: Boolean = false,
    profile: RadioProfile? = null,
    airtimeTracker: AirtimeTracker? = null,
) {
    val neighbors: MutableMap<String, NeighborInfo> = linkedMapOf()
    val frameHandlers: MutableMap<String, (String, Map<String, Any?>, Double) -> Unit> = mutableMapOf()
    private var lastHello = -1e9
    private var seq = 0
    val fragmenter = Fragmenter()
    val airtimeTracker: AirtimeTracker? =
        airtimeTracker ?: if (profile != null) AirtimeTracker(profile) else null

    fun originate(msg: Message, now: Double): Boolean = engine.originate(msg, now)

    fun sendFrame(
        to: String?,
        frame: MutableMap<String, Any?>,
        now: Double,
        broadcast: Boolean = false,
        prio: Priority = Priority.CHATTER,
    ) {
        if (signer != null) {
            seq += 1
            frame["snd"] = id
            frame["seq"] = seq
            val body = frame.filterKeys { it != "sig" }
            frame["sig"] = signer.sign(id, canonical(body))
        }

        val packets = if (compactWire) {
            val raw = WireCodec.encode(frame, signer)
            fragmenter.fragment(raw, id)
        } else {
            // CanonicalJson keeps floats as 1.0 (Python json.dumps), so HMAC over the
            // pre-wire map still verifies after JSON round-trip.
            listOf(CanonicalJson.encode(plainJson(frame)).toByteArray(Charsets.UTF_8))
        }

        for (pkt in packets) {
            val tracker = airtimeTracker
            if (tracker != null) {
                if (!tracker.canTransmit(pkt.size, now, priority = prio)) continue
                tracker.recordTx(pkt.size, now)
            }
            if (broadcast) radio.broadcast(pkt, now) else radio.send(to!!, pkt, now)
        }
    }

    fun tick(now: Double, gossip: Boolean = true) {
        if (now - lastHello >= helloInterval) {
            val hello = mutableMapOf<String, Any?>(
                "t" to "hello",
                "id" to id,
                "cell" to getCell(),
                "bat" to getBattery(),
                "bf" to engine.store.bloom().toJson(),
            )
            sendFrame(null, hello, now, broadcast = true, prio = Priority.HAZARD)
            lastHello = now
        }
        for ((frm, data) in radio.poll(now)) {
            handle(frm, data, now)
        }
        val stale = neighbors.filter { (_, n) -> now - n.lastSeen > neighborTimeout }.keys.toList()
        for (pid in stale) neighbors.remove(pid)
        if (gossip) {
            for (tx in engine.step(neighbors, now, getBattery())) {
                val frame = mutableMapOf<String, Any?>(
                    "t" to "data",
                    "m" to tx.msg.toWire(),
                    "tok" to tx.tokens,
                )
                sendFrame(tx.to, frame, now, broadcast = false, prio = tx.msg.priority)
            }
        }
    }

    private fun handle(frm: String, dataIn: ByteArray, now: Double) {
        val g = guard
        val data = fragmenter.reassemble(dataIn, now) ?: return
        if (g != null && !g.frameOk(data)) return
        try {
            val decoded = if (data.isNotEmpty() && (data[0].toInt() and 0xFF) == MAGIC_BYTE) {
                WireCodec.decode(data)
            } else {
                parseJsonObject(String(data, Charsets.UTF_8))?.toMutableMap()
            } ?: return
            if (decoded.isEmpty()) return

            val authSender = if (g != null) g.authenticateFrame(frm, decoded) else frm
            if (authSender == null) return
            val senderId = authSender
            val t = decoded["t"] as? String ?: return

            when (t) {
                "hello" -> {
                    if (g != null) {
                        val h = g.hello(decoded, data.size) ?: return
                        neighbors[senderId] = NeighborInfo(senderId, h.first, h.second, h.third, now)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val bf = BloomFilter.fromJson(decoded["bf"] as List<*>)
                        neighbors[senderId] = NeighborInfo(
                            senderId,
                            bf,
                            decoded["cell"] as String?,
                            (decoded["bat"] as? Number)?.toDouble() ?: 1.0,
                            now,
                        )
                    }
                }
                "data" -> {
                    neighbors[senderId]?.lastSeen = now
                    @Suppress("UNCHECKED_CAST")
                    val wire = decoded["m"] as Map<String, Any?>
                    var msg = Message.fromWire(wire)
                    if (g != null) {
                        if (data.size > g.limits.maxFrameBytes) {
                            g.dropped["frame_size"] = (g.dropped["frame_size"] ?: 0) + 1
                            return
                        }
                        msg = g.admit(msg, senderId, now) ?: return
                    }
                    val tok = when (val raw = decoded["tok"]) {
                        null -> null
                        is Int -> raw
                        is Number -> raw.toInt()
                        else -> null
                    }
                    engine.receive(msg, tok, now, senderId)
                }
                in AE_FRAMES -> {
                    val admitted = if (g != null) {
                        g.aeFrame(decoded, data.size, senderId, now) ?: return
                    } else {
                        decoded
                    }
                    frameHandlers[admitted["t"] as String]?.invoke(senderId, admitted, now)
                }
            }
        } catch (_: Exception) {
            // malformed frame from a hostile peer: drop silently
        }
    }
}

fun buildSimNode(
    nodeId: String,
    medium: SimMedium,
    seed: Int = 0,
    onDeliver: ((Message) -> Unit)? = null,
    signer: Signer? = null,
    guard: IngressGuard? = null,
    helloInterval: Double = 2.0,
    neighborTimeout: Double = 6.0,
    getCell: () -> String? = { null },
    getBattery: () -> Double = { 1.0 },
    compactWire: Boolean = false,
    profile: RadioProfile? = null,
): MeshNode {
    val eng = GossipEngine(
        nodeId,
        rng = Random("$seed:$nodeId".hashCode().toLong()),
        onDeliver = onDeliver,
    )
    return MeshNode(
        nodeId,
        SimRadio(nodeId, medium),
        eng,
        helloInterval = helloInterval,
        neighborTimeout = neighborTimeout,
        getCell = getCell,
        getBattery = getBattery,
        guard = guard,
        signer = signer,
        compactWire = compactWire,
        profile = profile,
    )
}
