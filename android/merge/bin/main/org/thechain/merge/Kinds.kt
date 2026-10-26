package org.thechain.merge

import kotlin.math.abs

const val HAZARD = "hazard"
const val VOUCH = "vouch"
const val REVOKE = "revoke"
const val CHALLENGE = "challenge"
const val REPUTATION = "reputation"

val NEW_KINDS: Set<String> = setOf(HAZARD, VOUCH, REVOKE, CHALLENGE, REPUTATION)

private const val MAX_STR = 64
private const val MAX_COORD = 1e7
private val HAZARD_KINDS = setOf("hazard", "safe")

val DEFAULT_REPUTATION_EVENTS: Set<String> = setOf(
    "verified_arrival",
    "confirmed_hazard",
    "corroborated_claim",
    "failed_claim",
    "failed_challenge",
    "false_hazard",
)

fun interface HazardReporter {
    fun report(reporter: String, pos: Pair<Double, Double>, kind: String, t: Double, weight: Double): Boolean
}

fun interface VouchSink {
    fun vouch(from: String, to: String, w: Double)
}

fun interface RevocationSink {
    fun ingest(items: Iterable<Map<String, Any?>>): Int
}

fun interface ReputationSink {
    fun record(node: String, event: String, now: Double, weight: Double)
}

private fun requireStr(x: Any?, name: String): String {
    if (x !is String || x.isEmpty() || x.length > MAX_STR) throw IllegalArgumentException("bad $name")
    return x
}

private fun requireUnit(x: Any?, name: String = "w"): Double {
    if (x is Boolean || x !is Number) throw IllegalArgumentException("bad $name")
    val d = x.toDouble()
    if (!d.isFinite()) throw IllegalArgumentException("bad $name")
    return d.coerceIn(0.0, 1.0)
}

private fun requirePos(p: Any?): Pair<Double, Double> {
    val coords = when (p) {
        is List<*> -> p
        is Pair<*, *> -> listOf(p.first, p.second)
        else -> null
    }
    if (coords == null || coords.size != 2) throw IllegalArgumentException("bad pos")
    val out = coords.map { c ->
        if (c is Boolean || c !is Number) throw IllegalArgumentException("bad pos")
        val d = c.toDouble()
        if (!d.isFinite() || abs(d) > MAX_COORD) throw IllegalArgumentException("bad pos")
        d
    }
    return out[0] to out[1]
}

fun hazardData(pos: Pair<Double, Double>, kind: String): Map<String, Any?> {
    if (kind !in HAZARD_KINDS) throw IllegalArgumentException("kind must be 'hazard' or 'safe'")
    val p = requirePos(listOf(pos.first, pos.second))
    return mapOf("pos" to listOf(p.first, p.second), "kind" to kind)
}

fun vouchData(to: String, w: Double = 1.0): Map<String, Any?> =
    mapOf("to" to requireStr(to, "to"), "w" to requireUnit(w))

fun revokeData(revocationJson: Map<String, Any?>): Map<String, Any?> = HashMap(revocationJson)

fun challengeData(claimId: String, claimant: String, cid: String, deadline: Double): Map<String, Any?> =
    mapOf(
        "claim" to requireStr(claimId, "claim"),
        "claimant" to requireStr(claimant, "claimant"),
        "cid" to requireStr(cid, "cid"),
        "deadline" to deadline,
    )

fun reputationData(node: String, event: String, w: Double = 1.0): Map<String, Any?> =
    mapOf("node" to requireStr(node, "node"), "event" to requireStr(event, "event"), "w" to requireUnit(w))

/**
 * Applies new-kind events to local state. Targets may be null (then that kind is a no-op).
 * Report / reputation weights come from [weightFn] (M5 trust x M1 co-presence); the reporter is
 * always the signing node.
 */
class Projector(
    val hazards: HazardReporter? = null,
    val graph: VouchSink? = null,
    val revocations: RevocationSink? = null,
    val reputation: ReputationSink? = null,
    val weightFn: (String) -> Double = { 0.5 },
    val reputationEvents: Set<String> = DEFAULT_REPUTATION_EVENTS,
    val maxChallenges: Int = 1024,
) {
    val challenges: MutableMap<String, Map<String, Any?>> = linkedMapOf()
    val rejected: MutableMap<String, Int> = mutableMapOf()

    fun attach(replica: Replica): Projector {
        replica.onApply.add { apply(it) }
        return this
    }

    fun apply(ev: Event) {
        val ok = try {
            when (ev.kind) {
                HAZARD -> hazard(ev)
                VOUCH -> vouch(ev)
                REVOKE -> revoke(ev)
                CHALLENGE -> challenge(ev)
                REPUTATION -> reputation(ev)
                else -> return
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) rejected[ev.kind] = (rejected[ev.kind] ?: 0) + 1
    }

    private fun w(node: String): Double = requireUnit(weightFn(node))

    private fun hazard(ev: Event): Boolean {
        val d = ev.data
        val pos = requirePos(d["pos"])
        val kind = d["kind"] as? String ?: return false
        val weight = w(ev.node)
        if (kind !in HAZARD_KINDS || weight <= 0.0) return false
        hazards?.report(ev.node, pos, kind, ev.hlc.wall / 1000.0, weight)
        return true
    }

    private fun vouch(ev: Event): Boolean {
        val to = requireStr(ev.data["to"], "to")
        val weight = requireUnit(ev.data["w"] ?: 1.0)
        if (to == ev.node || weight <= 0.0) return false
        graph?.vouch(ev.node, to, weight)
        return true
    }

    private fun revoke(ev: Event): Boolean {
        val rev = revocations ?: return true
        return rev.ingest(listOf(ev.data)) > 0
    }

    private fun challenge(ev: Event): Boolean {
        val d = ev.data
        val cid = requireStr(d["cid"], "cid")
        val deadline = d["deadline"]
        if (deadline is Boolean || deadline !is Number || !deadline.toDouble().isFinite()) return false
        if (cid in challenges || challenges.size >= maxChallenges) return false
        challenges[cid] = mapOf(
            "verifier" to ev.node,
            "claim" to requireStr(d["claim"], "claim"),
            "claimant" to requireStr(d["claimant"], "claimant"),
            "deadline" to deadline.toDouble(),
        )
        return true
    }

    private fun reputation(ev: Event): Boolean {
        val d = ev.data
        val subject = requireStr(d["node"], "node")
        val event = requireStr(d["event"], "event")
        if (subject == ev.node || event !in reputationEvents) return false
        val weight = w(ev.node) * requireUnit(d["w"] ?: 1.0)
        if (weight <= 0.0) return false
        reputation?.record(subject, event, ev.hlc.wall / 1000.0, weight)
        return true
    }
}
