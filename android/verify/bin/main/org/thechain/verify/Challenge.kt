package org.thechain.verify

import java.security.MessageDigest
import kotlin.math.abs

const val PASS = 2.5
const val FAIL = -3.0
const val LATE = -1.0
const val MISMATCH = -4.0

data class Challenge(
    val cid: String,
    val claimId: Any,
    val claimant: Any,
    val issued: Double,
    val deadline: Double,
)

data class Response<N>(
    val cid: String,
    val claimant: N,
    val baroHpa: Double,
    val neighbors: Set<N>,
    val sent: Double,
)

/** [nonce] must be fresh and unpredictable. The caller supplies it so tests stay deterministic. */
fun makeChallenge(claimId: Any, claimant: Any, now: Double, nonce: String, ttl: Double = 120.0): Challenge {
    val cid = sha256Hex("$claimId|$claimant|$nonce").substring(0, 16)
    return Challenge(cid, claimId, claimant, now, now + ttl)
}

/**
 * LLR for a challenge response. Null while the deadline has not passed is still pending (0).
 * Silence after the deadline is only a weak negative.
 */
fun <N> checkResponse(
    ch: Challenge,
    resp: Response<N>?,
    now: Double,
    localBaroHpa: Double,
    knownNodes: Set<N>,
    baroTol: Double = 3.0,
    minKnownFrac: Double = 0.5,
): Double {
    if (resp == null) return if (now > ch.deadline) LATE else 0.0
    if (resp.cid != ch.cid || resp.claimant != ch.claimant) return MISMATCH
    if (resp.sent > ch.deadline) return LATE
    val nb = resp.neighbors.filter { it != ch.claimant }
    val baroOk = abs(resp.baroHpa - localBaroHpa) <= baroTol
    val known = nb.count { it in knownNodes }
    val nbOk = nb.isNotEmpty() && known.toDouble() / nb.size >= minKnownFrac
    return if (baroOk && nbOk) PASS else FAIL
}

private fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append(hex[v ushr 4])
        sb.append(hex[v and 0xF])
    }
    return sb.toString()
}
