package org.thechain.privacy

import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Dummy PACKET-byte blobs that fill a byte budget after real traffic is subtracted.
 * They are not sealed envelopes; they only match the fixed length.
 */
class CoverTraffic(
    val rateBps: Double = 256.0,
    burst: Double? = null,
    val maxPerStep: Int = 4,
    private val rng: Random = Random(),
) {
    val burst: Double = burst ?: 4.0 * PACKET
    var tokens: Double = 0.0
    var t: Double? = null

    fun step(now: Double, realSentBytes: Int = 0): List<ByteArray> {
        if (t != null) {
            tokens = min(burst, tokens + rateBps * max(0.0, now - t!!))
        }
        t = now
        tokens = max(0.0, tokens - realSentBytes)
        val n = min(maxPerStep, (tokens / PACKET).toInt())
        tokens -= n * PACKET
        return List(n) { ByteArray(PACKET).also { rng.nextBytes(it) } }
    }
}
