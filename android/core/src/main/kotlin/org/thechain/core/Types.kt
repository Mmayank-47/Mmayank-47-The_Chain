package org.thechain.core

import kotlin.math.max

typealias NodeId = String

/** Lower number is sent first (distress, then dispatch, hazard, chatter). */
enum class Priority {
    DISTRESS,
    DISPATCH,
    HAZARD,
    CHATTER,
    ;

    val wire: Int get() = ordinal

    companion object {
        fun fromWire(v: Int): Priority = entries.first { it.wire == v }
    }
}

/** Monotonic lattice: a state only moves forward. */
enum class DispatchState {
    PROPOSED,
    ACCEPTED,
    EN_ROUTE,
    ARRIVED,
    RESOLVED,
    ;

    val wire: Int get() = ordinal

    companion object {
        fun fromWire(v: Int): DispatchState = entries.first { it.wire == v }
    }
}

private val EVENT_PRIORITY = mapOf("claim" to Priority.DISTRESS, "hazard" to Priority.HAZARD)

fun eventPriority(kind: Any?): Priority {
    if (kind !is String) return Priority.DISPATCH
    return EVENT_PRIORITY[kind] ?: Priority.DISPATCH
}

data class Hlc(val wall: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    fun toJson(): List<Any> = listOf(wall, counter, node)

    override fun compareTo(other: Hlc): Int {
        val byWall = wall.compareTo(other.wall)
        if (byWall != 0) return byWall
        val byCounter = counter.compareTo(other.counter)
        if (byCounter != 0) return byCounter
        return node.compareTo(other.node)
    }

    companion object {
        fun fromJson(x: List<*>): Hlc = Hlc((x[0] as Number).toLong(), (x[1] as Number).toInt(), x[2].toString())
    }
}

val ZERO_HLC = Hlc(0, 0, "")

const val MAX_DRIFT_MS = 5 * 60 * 1000
const val MAX_COUNTER = 1 shl 20

class ClockDrift(message: String) : IllegalArgumentException(message)

class HlClock(
    val node: String,
    private val nowMs: () -> Long,
    val maxDriftMs: Long? = MAX_DRIFT_MS.toLong(),
) {
    private var wall: Long = 0
    private var counter: Int = 0

    fun driftOk(remote: Hlc): Boolean {
        if (remote.wall < 0 || remote.counter !in 0..MAX_COUNTER) return false
        val bound = maxDriftMs ?: return true
        return remote.wall - nowMs() <= bound
    }

    fun now(): Hlc {
        val pt = nowMs()
        if (pt > wall) {
            wall = pt
            counter = 0
        } else {
            counter += 1
        }
        return Hlc(wall, counter, node)
    }

    fun update(remote: Hlc): Hlc {
        if (!driftOk(remote)) {
            throw ClockDrift("remote HLC ${remote.toJson()} is outside the allowed drift")
        }
        val pt = nowMs()
        val newWall = maxOf(wall, remote.wall, pt)
        val c = when {
            newWall == wall && newWall == remote.wall -> max(counter, remote.counter) + 1
            newWall == wall -> counter + 1
            newWall == remote.wall -> remote.counter + 1
            else -> 0
        }
        wall = newWall
        counter = c
        return Hlc(newWall, c, node)
    }
}

class Message(
    val kind: String,
    val priority: Priority,
    val origin: String,
    val payload: Map<String, Any?>,
    val hlc: Hlc,
    val destCell: String? = null,
    val ttlS: Double = 3600.0,
    var created: Double = 0.0,
) {
    val msgId: String

    init {
        val body = mapOf(
            "k" to kind,
            "o" to origin,
            "d" to payload,
            "h" to hlc.toJson(),
        )
        msgId = sha256Hex(canonical(body)).substring(0, 16)
    }

    fun expired(now: Double): Boolean = now - created > ttlS

    fun toWire(): Map<String, Any?> = mapOf(
        "k" to kind,
        "p" to priority.wire,
        "o" to origin,
        "d" to payload,
        "h" to hlc.toJson(),
        "c" to destCell,
        "ttl" to ttlS,
        "cr" to created,
    )

    fun wireBytes(): ByteArray = canonical(toWire())

    companion object {
        fun fromWire(d: Map<String, Any?>): Message {
            @Suppress("UNCHECKED_CAST")
            val payload = d["d"] as Map<String, Any?>
            return Message(
                kind = d["k"] as String,
                priority = Priority.fromWire((d["p"] as Number).toInt()),
                origin = d["o"] as String,
                payload = payload,
                hlc = Hlc.fromJson(d["h"] as List<*>),
                destCell = d["c"] as String?,
                ttlS = (d["ttl"] as? Number)?.toDouble() ?: 3600.0,
                created = (d["cr"] as? Number)?.toDouble() ?: 0.0,
            )
        }
    }
}
