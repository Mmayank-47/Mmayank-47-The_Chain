package org.thechain.transport

data class PendingForward(
    val relay: String,
    val mid: String,
    val sentAt: Double,
    val deadline: Double,
    val isCritical: Boolean,
)

/** Evidence sink compatible with M5 BetaRep.record / .mean. */
interface ReputationSink {
    fun record(relay: String, kind: String, now: Double)
    fun mean(relay: String, now: Double): Double = 0.0
}

/**
 * Watchdog and hop acknowledgement engine for M0 transport.
 * Defends against blackhole / greyhole by expecting next-hop forwards.
 */
class Watchdog(
    val rep: ReputationSink? = null,
    val ackTimeoutS: Double = 8.0,
    val trustThreshold: Double = 0.35,
) {
    private val pending = mutableMapOf<Pair<String, String>, PendingForward>()
    private val penalized = mutableSetOf<String>()

    fun track(relay: String, mid: String, now: Double, isCritical: Boolean = false) {
        pending[relay to mid] = PendingForward(
            relay = relay,
            mid = mid,
            sentAt = now,
            deadline = now + ackTimeoutS,
            isCritical = isCritical,
        )
    }

    fun observeForward(relay: String, mid: String, now: Double): Boolean {
        val key = relay to mid
        if (key !in pending) return false
        pending.remove(key)
        rep?.record(relay, "forward_success", now)
        return true
    }

    /** Returns failed (relay, mid, isCritical) for multipath rerouting. */
    fun tick(now: Double): List<Triple<String, String, Boolean>> {
        val failed = mutableListOf<Triple<String, String, Boolean>>()
        val expired = pending.filter { (_, p) -> now >= p.deadline }.keys.toList()
        for (k in expired) {
            val p = pending.remove(k)!!
            failed.add(Triple(p.relay, p.mid, p.isCritical))
            penalized.add(p.relay)
            rep?.record(p.relay, "forward_failure", now)
        }
        return failed
    }

    fun isTrusted(relay: String, now: Double): Boolean {
        if (relay !in penalized) return true
        val r = rep ?: return false
        return r.mean(relay, now) >= trustThreshold
    }
}
