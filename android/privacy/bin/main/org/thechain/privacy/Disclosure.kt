package org.thechain.privacy

import org.thechain.core.Hlc
import org.thechain.core.Message
import org.thechain.core.Priority
import org.thechain.core.Sealer
import org.thechain.core.canonical
import org.thechain.core.cellOf
import java.math.BigDecimal
import java.math.RoundingMode

/** Every sealed blob a relay sees is this many bytes. The cipher itself is not in this module. */
const val PACKET = 512

const val KIND_CASE = "m9.case"
const val KIND_SEALED = "m9.sealed"

val CRITICAL = setOf("not_breathing", "severe_bleeding", "unconscious")

private val GENERALISE = mapOf("insulin" to "medicine", "oxygen" to "medicine", "dialysis" to "medicine")

private val PUBLIC_TAGS = setOf(
    "medic", "medicine", "blood", "boat", "lifejacket", "rope", "stretcher", "water", "food",
    "shelter", "vehicle", "fuel", "power",
)

private val SKILL_TAGS = setOf("medic", "boat")

private val CAT_OF = mapOf(
    "medic" to "medical", "medicine" to "medical", "blood" to "medical",
    "boat" to "rescue", "lifejacket" to "rescue", "rope" to "rescue", "stretcher" to "rescue",
    "water" to "supply", "food" to "supply", "fuel" to "supply", "power" to "supply", "vehicle" to "supply",
    "shelter" to "shelter",
)

private val RANK = listOf("medical", "rescue", "supply", "shelter", "other")

private val MEDICAL_FEATURES = CRITICAL + setOf("medication_dependent", "pregnant")

class DisclosureDenied(message: String) : SecurityException(message)

fun publicTags(tags: List<String>): List<String> {
    val out = mutableListOf<String>()
    for (raw in tags) {
        val tag = GENERALISE[raw] ?: raw
        if (tag in PUBLIC_TAGS && tag !in out) out.add(tag)
    }
    return out
}

fun categoryOf(tags: List<String>, features: List<String>): String {
    val cats = publicTags(tags).map { CAT_OF.getValue(it) }.toMutableSet()
    if (features.any { it in MEDICAL_FEATURES }) cats.add("medical")
    if ("trapped" in features) cats.add("rescue")
    return RANK.firstOrNull { it in cats } ?: "other"
}

/**
 * Stage 1 is a coarse cell and category. Exact position, free text and quantity stay in [privatePayload]
 * until [Discloser.release] seals them to one accepted responder.
 */
class Case(
    val id: String,
    val pos: Pair<Double, Double>,
    val radiusM: Double,
    val tags: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val locationText: String = "",
    val quantity: Int? = null,
    val chain: PseudonymChain? = null,
    val cellM: Double = 500.0,
) {
    val cell: String get() = cellOf(pos, cellM)

    fun publicPayload(): Map<String, Any?> {
        val pub = publicTags(tags)
        val need = if (features.any { it in CRITICAL } || "medic" in pub) {
            "medic"
        } else {
            pub.firstOrNull { it in SKILL_TAGS }
        }
        return mapOf(
            "c" to id,
            "cell" to cell,
            "cat" to categoryOf(tags, features),
            "need" to need,
            "tag" to pub.firstOrNull(),
        )
    }

    fun privatePayload(now: Double): Map<String, Any?> = mapOf(
        "c" to id,
        "pos" to listOf(round1(pos.first), round1(pos.second)),
        "r" to round1(radiusM),
        "feat" to features.toList(),
        "loc" to locationText,
        "qty" to quantity,
        "pid" to chain?.pid(now),
    )
}

fun publicMessage(case: Case, pseudonym: String, hlc: Hlc, now: Double, ttlS: Double = 3600.0): Message {
    val pub = case.publicPayload()
    return Message(
        KIND_CASE,
        Priority.DISTRESS,
        pseudonym,
        pub,
        hlc,
        destCell = pub["cell"] as String?,
        ttlS = ttlS,
        created = now,
    )
}

fun sealedMessage(blob: ByteArray, pseudonym: String, cell: String, hlc: Hlc, now: Double): Message =
    Message(
        KIND_SEALED,
        Priority.DISPATCH,
        pseudonym,
        mapOf("b" to blob.toHex()),
        hlc,
        destCell = cell,
        created = now,
    )

class Discloser(
    val case: Case,
    val directory: Map<String, ByteArray>,
    private val sealer: Sealer,
    val maxResponders: Int = 2,
) {
    val accepted: MutableSet<String> = linkedSetOf()
    private val sent: MutableMap<String, ByteArray> = linkedMapOf()

    fun accept(responder: String): Boolean {
        if (responder !in directory) return false
        if (responder !in accepted && accepted.size >= maxResponders) return false
        accepted.add(responder)
        return true
    }

    /** Sealed details for an accepted responder. The same blob is returned if asked again. */
    fun release(
        responder: String,
        now: Double,
        sign: ((ByteArray) -> String)? = null,
        nonce: ByteArray? = null,
    ): ByteArray {
        if (responder !in accepted) {
            throw DisclosureDenied("details are released only after acceptance")
        }
        sent[responder]?.let { return it }
        val payload = case.privatePayload(now)
        val sig = sign?.invoke(canonical(listOf(case.id, payload)))
        val sender = case.chain?.pid(now) ?: case.id
        val blob = sealer.seal(directory.getValue(responder), sender, payload, nonce, sig)
        require(blob.size == PACKET) { "sealed envelope must be $PACKET bytes" }
        sent[responder] = blob
        return blob
    }
}

private fun round1(x: Double): Double =
    BigDecimal(x.toString()).setScale(1, RoundingMode.HALF_EVEN).toDouble()
