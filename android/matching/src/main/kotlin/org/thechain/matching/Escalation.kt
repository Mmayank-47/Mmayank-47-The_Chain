package org.thechain.matching

const val PROMPTED = "prompted"
const val ACCEPTED = "accepted"
const val EXHAUSTED = "exhausted"

/** One deterministic primary, then automatic escalation on timeout, decline, or referral. */
class Escalation(
    val claimId: String,
    ranked: List<String>,
    now: Double,
    val ackTimeout: Double = 120.0,
) {
    var ranked: MutableList<String> = ranked.toMutableList()
    private var i: Int = 0
    private var tPrompt: Double = now
    var state: String = if (this.ranked.isEmpty()) EXHAUSTED else PROMPTED

    val current: String?
        get() = if (state == PROMPTED || state == ACCEPTED) ranked[i] else null

    private fun advance(now: Double): String? {
        i += 1
        if (i >= ranked.size) {
            state = EXHAUSTED
            return null
        }
        tPrompt = now
        return ranked[i]
    }

    fun ack(responder: String, now: Double): Boolean {
        if (state == PROMPTED && responder == current) {
            state = ACCEPTED
            return true
        }
        return false
    }

    fun decline(responder: String, now: Double): String? {
        if (state == PROMPTED && responder == current) return advance(now)
        return if (state == PROMPTED) current else null
    }

    fun refer(responder: String, to: String, now: Double): String? {
        if (state == PROMPTED && responder == current) {
            val kept = ArrayList<String>()
            for ((k, r) in ranked.withIndex()) {
                if (k <= i || r != to) kept.add(r)
            }
            kept.add(i + 1, to)
            ranked = kept
            return advance(now)
        }
        return if (state == PROMPTED) current else null
    }

    /** Responder newly prompted because of a timeout, or null. */
    fun tick(now: Double): String? {
        if (state == PROMPTED && now - tPrompt >= ackTimeout) return advance(now)
        return null
    }
}
