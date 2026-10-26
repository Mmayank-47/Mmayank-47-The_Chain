package org.thechain.transport

import org.thechain.core.BloomFilter
import org.thechain.core.Message
import org.thechain.core.Priority
import org.thechain.core.Signer
import org.thechain.core.canonical
import org.thechain.core.eventPriority
import java.math.BigInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Allowlist: kind -> priority. null = derived from the payload (see [m8Priority]).
 * Hazards and vouches ride m8.event; they are not standalone kinds here.
 */
val KIND_PRIORITY: Map<String, Priority?> = mapOf(
    "m8.event" to null,
    "m9.case" to Priority.DISTRESS,
    "m9.pin" to Priority.DISTRESS,
    "m9.card" to Priority.CHATTER,
    "m9.chat" to Priority.CHATTER,
    "m9.ack" to Priority.DISTRESS,
    "m9.sealed" to Priority.DISPATCH,
    "m1.revoke" to Priority.DISPATCH,
    "m3.challenge" to Priority.DISPATCH,
    "m3.response" to Priority.DISPATCH,
)

const val AE_SUM = "ae.sum"
const val AE_EVENTS = "ae.ev"
val AE_FRAMES: Set<String> = setOf(AE_SUM, AE_EVENTS)

fun m8Priority(payload: Map<String, Any?>): Priority = eventPriority(payload["k"])

/** Per-sender / stranger rate budget. Production uses M1 IdentityLimiter. */
interface Limiter {
    fun allow(node: String, now: Double, cost: Double = 1.0): Boolean
}

/** Optional stranger lane used when [Limiter.allow] rejects an unenrolled sender. */
interface StrangerLaneLimiter : Limiter {
    fun allowStranger(node: String, now: Double, cost: Double = 1.0): Boolean
}

/** Sliding replay window for a single sender (IPsec RFC 4303 style). */
class ReplayWindow(val size: Int = 64) {
    var maxSeq: Int = 0
        private set
    private var seen: MutableSet<Int> = mutableSetOf()

    /** Returns (accepted, reason). */
    fun checkAndAdd(seq: Int): Pair<Boolean, String> {
        if (seq <= 0) return false to "invalid_seq"
        if (seq > maxSeq) {
            maxSeq = seq
            seen.add(seq)
            val cutoff = maxSeq - size
            seen = seen.filter { it > cutoff }.toMutableSet()
            return true to "ok"
        }
        val diff = maxSeq - seq
        if (diff >= size) return false to "out_of_window"
        if (seq in seen) return false to "replayed"
        seen.add(seq)
        return true to "ok"
    }
}

data class IngressLimits(
    val maxTtlS: Double = 24 * 3600.0,
    val maxWireBytes: Int = 2048,
    val maxFrameBytes: Int = 4096,
    val maxHelloBytes: Int = 8192,
    val maxBloomBits: Int = 16384,
    val maxBloomHashes: Int = 16,
    val minBloomBits: Int = 64,
    val maxOriginLen: Int = 64,
    val maxCellLen: Int = 32,
    val maxAeBytes: Int = 16384,
    val maxAeNodes: Int = 512,
    val maxAeEvents: Int = 64,
)

class IngressGuard(
    val limits: IngressLimits = IngressLimits(),
    val limiter: Limiter? = null,
    allowed: Map<String, Priority?>? = null,
    val strangerLane: Boolean = true,
    val signer: Signer? = null,
    val replayWindowSize: Int = 64,
) {
    val allowed: MutableMap<String, Priority?> =
        (allowed ?: KIND_PRIORITY).toMutableMap()
    private val replayWindows = mutableMapOf<String, ReplayWindow>()
    val dropped: MutableMap<String, Int> = mutableMapOf()

    private fun <T> drop(why: String): T? {
        dropped[why] = (dropped[why] ?: 0) + 1
        return null
    }

    fun getReplayWindow(sender: String): ReplayWindow =
        replayWindows.getOrPut(sender) { ReplayWindow(replayWindowSize) }

    /** A new Nearby session starts seq at 1. Drop the window from the previous process. */
    fun resetReplay(sender: String) {
        replayWindows.remove(sender)
    }

    /**
     * Authenticate link frame and check replay protection.
     * Returns the authenticated sender ID, or null if spoofed / unauthenticated / replayed.
     */
    fun authenticateFrame(frm: String, f: Map<String, Any?>): String? {
        if (signer == null) return frm
        val snd = f["snd"]
        val seqRaw = f["seq"]
        val sig = f["sig"]
        val seq = when (seqRaw) {
            is Int -> seqRaw
            is Long -> seqRaw.toInt()
            else -> null
        }
        if (snd !is String || snd.isEmpty() || seq == null || sig !is String) {
            return drop("frame_unauthenticated")
        }
        if (frm.isNotEmpty() && frm != snd) return drop("frame_spoofed_sender")
        val body = f.filterKeys { it != "sig" }
        if (!signer.verify(snd, canonical(body), sig)) return drop("frame_bad_sig")
        val (ok, reason) = getReplayWindow(snd).checkAndAdd(seq)
        if (!ok) return drop("frame_$reason")
        return snd
    }

    /** Cheap pre-parse check before JSON/CBOR decode. */
    fun frameOk(data: Any?): Boolean {
        if (data !is ByteArray) {
            drop<Unit>("frame_type")
            return false
        }
        val L = limits
        if (data.size > max(L.maxFrameBytes, max(L.maxHelloBytes, L.maxAeBytes))) {
            drop<Unit>("frame_size")
            return false
        }
        return true
    }

    /** -> (bloom, cell, battery) or null if the beacon is malformed or oversized. */
    fun hello(f: Map<String, Any?>, rawLen: Int): Triple<BloomFilter, String?, Double>? {
        val L = limits
        if (rawLen > L.maxHelloBytes) return drop("hello_size")
        val bf = f["bf"]
        if (bf !is List<*> || bf.size != 3) return drop("bloom_shape")
        val m = (bf[0] as? Number)?.toInt()
        val k = (bf[1] as? Number)?.toInt()
        val hexbits = bf[2]
        // Reject booleans (Number in Python sense excludes bool; Kotlin keeps them separate).
        if (bf[0] is Boolean || bf[1] is Boolean || m == null || k == null || hexbits !is String) {
            return drop("bloom_types")
        }
        if (m !in L.minBloomBits..L.maxBloomBits || k !in 1..L.maxBloomHashes) {
            return drop("bloom_bounds")
        }
        if (hexbits.length > m / 4 + 1) return drop("bloom_hex_len")
        val bits = try {
            BigInteger(hexbits, 16)
        } catch (_: NumberFormatException) {
            return drop("bloom_hex")
        }
        if (bits.signum() < 0 || bits.bitLength() > m) return drop("bloom_hex")
        val cell = f["cell"]
        if (cell != null && (cell !is String || cell.length > L.maxCellLen)) {
            return drop("hello_cell")
        }
        val batRaw = f["bat"] ?: 1.0
        val bat = when (batRaw) {
            is Boolean -> return drop("hello_bat")
            is Number -> batRaw.toDouble()
            else -> return drop("hello_bat")
        }
        if (!bat.isFinite()) return drop("hello_bat")
        return Triple(
            BloomFilter(m, k, bits),
            cell as String?,
            min(1.0, max(0.0, bat)),
        )
    }

    /** Return the sanitised message to hand to the engine, or null to drop it. */
    fun admit(msg: Message, fromId: String, now: Double): Message? {
        val L = limits
        if (msg.kind !in allowed) return drop("kind_not_allowed")
        // payload is typed Map on Message; still reject empty-origin / oversize fields below
        if (msg.origin.isEmpty() || msg.origin.length > L.maxOriginLen) return drop("origin")
        val dest = msg.destCell
        if (dest != null && dest.length > L.maxCellLen) return drop("dest_cell")
        if (msg.wireBytes().size > L.maxWireBytes) return drop("wire_size")
        val ttl = msg.ttlS
        if (!ttl.isFinite() || ttl <= 0) return drop("ttl")
        val created = msg.created
        if (!created.isFinite()) return drop("created")
        var prio = allowed[msg.kind]
        if (prio == null) prio = m8Priority(msg.payload)

        val lim = limiter
        if (lim != null && !lim.allow(fromId, now, 1.0)) {
            val strangerOk = strangerLane &&
                prio == Priority.DISTRESS &&
                lim is StrangerLaneLimiter &&
                lim.allowStranger(fromId, now, 1.0)
            if (!strangerOk) return drop("rate_limited")
        }

        return Message(
            kind = msg.kind,
            priority = prio!!,
            origin = msg.origin,
            payload = msg.payload,
            hlc = msg.hlc,
            destCell = msg.destCell,
            ttlS = min(ttl, L.maxTtlS),
            created = min(created, now),
        )
    }

    /**
     * An ae.sum / ae.ev frame from a radio neighbour -> the frame to hand to M8, or null.
     * Sender budget is charged before shape checks so junk still costs.
     */
    fun aeFrame(f: Any?, rawLen: Int, fromId: String, now: Double): Map<String, Any?>? {
        val L = limits
        val map = f as? Map<*, *> ?: return drop("ae_type")
        @Suppress("UNCHECKED_CAST")
        val frame = map as Map<String, Any?>
        val t = frame["t"]
        if (t !is String || t !in AE_FRAMES) return drop("ae_type")
        if (limiter != null && !limiter.allow(fromId, now, 1.0)) return drop("rate_limited")
        if (rawLen > L.maxAeBytes) return drop("ae_size")
        val (body, cap) = if (t == AE_SUM) {
            frame["s"] to L.maxAeNodes
        } else {
            frame["e"] to L.maxAeEvents
        }
        val ok = if (t == AE_SUM) body is Map<*, *> else body is List<*>
        val len = when (body) {
            is Map<*, *> -> body.size
            is List<*> -> body.size
            else -> -1
        }
        if (!ok || len > cap) return drop("ae_shape")
        return frame
    }
}
