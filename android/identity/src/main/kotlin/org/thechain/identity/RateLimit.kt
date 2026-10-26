package org.thechain.identity

import org.thechain.core.TokenBucket

/**
 * Per-identity token buckets keyed by hardware key id.
 * Unenrolled senders get nothing from [allow]; [allowStranger] is the distress stranger lane.
 */
class IdentityLimiter(
    private val keyIdOf: (String) -> String?,
    private val weightOf: (String, Double) -> Double = { _, _ -> 0.0 },
    val baseRate: Double = 0.2,
    val baseBurst: Double = 3.0,
    val bonusRate: Double = 1.8,
    val bonusBurst: Double = 7.0,
    val maxKeys: Int = 4096,
    val strangerRate: Double = 0.0,
    val strangerBurst: Double = 0.0,
    val maxStrangers: Int = 1024,
) {
    private val buckets = linkedMapOf<String, TokenBucket>()
    private val strangers = linkedMapOf<String, TokenBucket>()

    constructor(
        registry: Registry,
        baseRate: Double = 0.2,
        baseBurst: Double = 3.0,
        strangerRate: Double = 0.0,
        strangerBurst: Double = 0.0,
    ) : this(
        keyIdOf = { registry.keyIdOf(it) },
        baseRate = baseRate,
        baseBurst = baseBurst,
        strangerRate = strangerRate,
        strangerBurst = strangerBurst,
    )

    fun budget(node: String, now: Double): Pair<Double, Double> {
        val w = weightOf(node, now)
        return baseRate + bonusRate * w to baseBurst + bonusBurst * w
    }

    fun isEnrolled(node: String): Boolean = keyIdOf(node) != null

    fun allow(node: String, now: Double, cost: Double = 1.0): Boolean {
        val kid = keyIdOf(node) ?: return false
        val (rate, burst) = budget(node, now)
        var b = buckets[kid]
        if (b == null) {
            if (buckets.size >= maxKeys) return false
            b = TokenBucket(rate, burst).also { it.t = now }
            buckets[kid] = b
        }
        b.rate = rate
        b.burst = burst
        return b.allow(now, cost)
    }

    fun allowStranger(node: String, now: Double, cost: Double = 1.0): Boolean {
        if (keyIdOf(node) != null) return false
        if (strangerBurst <= 0.0 && strangerRate <= 0.0) return false
        var b = strangers[node]
        if (b == null) {
            if (strangers.size >= maxStrangers) return false
            b = TokenBucket(strangerRate, strangerBurst).also { it.t = now }
            strangers[node] = b
        }
        b.rate = strangerRate
        b.burst = strangerBurst
        return b.allow(now, cost)
    }
}
