package org.thechain.core

import kotlin.math.max
import kotlin.math.min

/** The one rate primitive shared across the stack. */
class TokenBucket(var rate: Double, var burst: Double) {
    var tokens: Double = burst
    var t: Double = 0.0

    fun allow(now: Double, cost: Double = 1.0): Boolean {
        tokens = min(burst, tokens + rate * max(0.0, now - t))
        t = max(t, now)
        if (tokens >= cost) {
            tokens -= cost
            return true
        }
        return false
    }
}
