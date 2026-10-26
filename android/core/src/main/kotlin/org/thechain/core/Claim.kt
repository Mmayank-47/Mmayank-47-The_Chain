package org.thechain.core

import kotlin.math.abs

const val MAX_ID_LEN = 64
const val MAX_COORD = 1e7
const val MAX_RADIUS_M = 5000.0
const val MAX_PARSED_BYTES = 900

class ClaimError(message: String) : IllegalArgumentException(message)

private fun asFinite(x: Any?): Double? {
    if (x is Boolean) return null
    val d = when (x) {
        is Double -> x
        is Float -> x.toDouble()
        is Number -> x.toDouble()
        else -> return null
    }
    return if (d.isFinite()) d else null
}

private fun check(node: Any?, src: Any?, pos: Any?, radius: Any?, t: Any?, parsed: Any?) {
    for ((name, v) in listOf("node" to node, "src" to src)) {
        if (v !is String || v.isEmpty() || v.length > MAX_ID_LEN) throw ClaimError("bad $name")
    }
    val coords = when (pos) {
        is List<*> -> pos
        is Pair<*, *> -> listOf(pos.first, pos.second)
        else -> null
    }
    if (coords == null || coords.size != 2 || coords.any { c -> val n = asFinite(c); n == null || abs(n) > MAX_COORD }) {
        throw ClaimError("bad pos")
    }
    val r = asFinite(radius)
    if (r == null || r < 0 || r > MAX_RADIUS_M) throw ClaimError("bad radius")
    val time = asFinite(t)
    if (time == null || time < 0) throw ClaimError("bad t")
    if (parsed !is Map<*, *>) throw ClaimError("parsed must be an object")
    try {
        @Suppress("UNCHECKED_CAST")
        val size = CanonicalJson.encode(parsed as Map<String, Any?>).length
        if (size > MAX_PARSED_BYTES) throw ClaimError("parsed too large")
    } catch (e: ClaimError) {
        throw e
    } catch (e: Exception) {
        throw ClaimError("parsed is not plain JSON")
    }
}

private fun body(node: String, src: String, pos: Pair<Double, Double>, radius: Double, t: Double, parsed: Map<String, Any?>): Map<String, Any?> =
    mapOf(
        "n" to node,
        "s" to src,
        "p" to listOf(pos.first, pos.second),
        "r" to radius,
        "t" to t,
        "d" to parsed,
    )

data class WireClaim(
    val id: String,
    val node: String,
    val src: String,
    val pos: Pair<Double, Double>,
    val radius: Double,
    val t: Double,
    val parsed: Map<String, Any?>,
    val sig: String = "",
) {
    fun derivedId(): String =
        sha256Hex(canonical(body(node, src, pos, radius, t, parsed))).substring(0, 16)

    fun verify(signer: Signer): Boolean {
        return try {
            check(node, src, listOf(pos.first, pos.second), radius, t, parsed)
            id == derivedId() && signer.verify(node, id.toByteArray(Charsets.UTF_8), sig)
        } catch (_: ClaimError) {
            false
        }
    }

    fun toJson(): Map<String, Any?> = mapOf(
        "i" to id,
        "n" to node,
        "s" to src,
        "p" to listOf(pos.first, pos.second),
        "r" to radius,
        "t" to t,
        "d" to parsed,
        "g" to sig,
    )

    companion object {
        fun make(
            node: String,
            pos: Pair<Double, Double>,
            radius: Double,
            t: Double,
            parsed: Map<String, Any?>,
            signer: Signer,
            src: String? = null,
        ): WireClaim {
            val source = src ?: node
            check(node, source, listOf(pos.first, pos.second), radius, t, parsed)
            val b = body(node, source, Pair(pos.first.toDouble(), pos.second.toDouble()), radius.toDouble(), t.toDouble(), parsed)
            @Suppress("UNCHECKED_CAST")
            val p = b["p"] as List<Double>
            val cid = sha256Hex(canonical(b)).substring(0, 16)
            return WireClaim(
                cid,
                node,
                source,
                Pair(p[0], p[1]),
                b["r"] as Double,
                b["t"] as Double,
                parsed,
                signer.sign(node, cid.toByteArray(Charsets.UTF_8)),
            )
        }

        fun fromJson(j: Map<String, Any?>): WireClaim {
            try {
                val node = j["n"]
                val src = j["s"]
                val pos = j["p"]
                val radius = j["r"]
                val t = j["t"]
                val parsed = j["d"]
                check(node, src, pos, radius, t, parsed)
                if (j["i"] !is String || j["g"] !is String) throw ClaimError("bad id or signature")
                val coords = pos as List<*>
                @Suppress("UNCHECKED_CAST")
                return WireClaim(
                    j["i"] as String,
                    node as String,
                    src as String,
                    Pair(asFinite(coords[0])!!, asFinite(coords[1])!!),
                    asFinite(radius)!!,
                    asFinite(t)!!,
                    parsed as Map<String, Any?>,
                    j["g"] as String,
                )
            } catch (e: ClaimError) {
                throw e
            } catch (e: Exception) {
                throw ClaimError("malformed claim: $e")
            }
        }
    }
}
