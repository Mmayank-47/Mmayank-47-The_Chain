package org.thechain.verify

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private const val CLAMP = 12.0

private fun logit(p: Double): Double = ln(p / (1.0 - p))

private fun sigmoid(x: Double): Double {
    val clamped = max(-CLAMP, min(CLAMP, x))
    return 1.0 / (1.0 + exp(-clamped))
}

/** p_valid plus the per-signal log-likelihood ratios. */
data class Verdict(
    val p: Double,
    val parts: Map<String, Double>,
) {
    fun topReasons(n: Int = 3): List<Pair<String, Double>> =
        parts.entries
            .filter { it.value != 0.0 }
            .sortedByDescending { abs(it.value) }
            .take(n)
            .map { it.key to it.value }
}

class Verifier<Id : Comparable<Id>, N>(
    val prior: Double = 0.7,
    val staleTau: Double = 1800.0,
    val credential: ((ObservedClaim<Id, N>) -> Boolean?)? = null,
) {
    val ledger = Ledger<Id, N>()
    private val challenge = HashMap<Id, Double>()

    init {
        require(prior > 0.0 && prior < 1.0) { "prior must be in (0, 1)" }
    }

    fun observe(c: ObservedClaim<Id, N>): Boolean = ledger.add(c)

    /** Feed in [checkResponse]. Replaces any earlier result for that claim. */
    fun recordChallenge(claimId: Id, llr: Double) {
        challenge[claimId] = llr
    }

    fun score(c: ObservedClaim<Id, N>, now: Double? = null): Verdict {
        ledger.add(c)
        val t = now ?: max(ledger.latest, c.t)
        val parts = linkedMapOf(
            "identity" to identityBinding(c),
            "node_spread" to nodeSpread(ledger, c),
            "id_fanout" to identityFanout(ledger, c),
            "dup_spot" to duplicateSpot(ledger, c),
            "corroboration" to corroboration(ledger, c),
            "staleness" to staleness(c, t, staleTau),
            "credential" to credential(c, credential),
            "challenge" to (challenge[c.id] ?: 0.0),
        )
        var sum = 0.0
        for (v in parts.values) sum += v
        return Verdict(sigmoid(logit(prior) + sum), parts)
    }

    fun pValid(c: ObservedClaim<Id, N>, now: Double? = null): Double = score(c, now).p
}
