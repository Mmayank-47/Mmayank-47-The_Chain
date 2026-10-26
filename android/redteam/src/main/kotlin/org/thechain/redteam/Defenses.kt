package org.thechain.redteam

import org.thechain.core.TokenBucket
import org.thechain.core.sha256Hex
import org.thechain.cryptofake.HmacProvider
import org.thechain.cryptofake.macHex
import org.thechain.cryptofake.macOk

/**
 * Stand-alone defenses from the M11 table: rate budgets, content-hash dedupe,
 * offline-verifiable credentials with revocation, and local sensor veto.
 */
class RateLimiter(
    val rate: Double = 2.0,
    val burst: Double = 10.0,
    val maxKeys: Int = 4096,
) {
    private val buckets = linkedMapOf<Any, TokenBucket>()

    fun allow(key: Any, now: Double, cost: Double = 1.0): Boolean {
        var b = buckets[key]
        if (b == null) {
            if (buckets.size >= maxKeys) return false
            b = TokenBucket(rate, burst).also { it.t = now }
            buckets[key] = b
        }
        return b.allow(now, cost)
    }
}

/** Drop repeats of identical content for [ttl] seconds. */
class Dedupe(val ttl: Double = 600.0) {
    private val seen = linkedMapOf<String, Double>()

    fun first(payload: ByteArray, now: Double): Boolean {
        val stale = seen.filter { (_, t) -> now - t > ttl }.keys.toList()
        for (h in stale) seen.remove(h)
        val h = sha256Hex(payload)
        if (h in seen) return false
        seen[h] = now
        return true
    }
}

private val hmac = HmacProvider()

fun signCredential(rootKey: ByteArray, node: Any, role: String): String =
    macHex(hmac, rootKey, "$node|$role".toByteArray(Charsets.UTF_8))

fun verifyCredential(
    rootKey: ByteArray,
    node: Any,
    role: String,
    sig: String?,
    revoked: Set<Pair<Any, String>>? = null,
): Boolean {
    if (sig.isNullOrEmpty()) return false
    if (revoked != null && (node to role) in revoked) return false
    return macOk(hmac, rootKey, "$node|$role".toByteArray(Charsets.UTF_8), sig)
}

/**
 * Responder's own sensors outrank the mesh. Water at the responder's feet aborts the run.
 * No sensor reading = no veto.
 */
class SensorVeto(val maxWaterM: Double = 0.15) {
    fun veto(localWaterM: Double?): Boolean =
        localWaterM != null && localWaterM >= maxWaterM

    fun abort(localWaterM: Double?, riskAhead: Double, riskLimit: Double = 0.8): Boolean =
        veto(localWaterM) || riskAhead >= riskLimit
}
