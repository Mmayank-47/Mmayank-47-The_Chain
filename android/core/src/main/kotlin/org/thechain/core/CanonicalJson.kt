package org.thechain.core

import java.nio.charset.StandardCharsets

/**
 * Python `json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True)`.
 * Content ids (message, claim, event) are SHA-256 of these bytes, so the encoding has to
 * match CPython 3.12 byte for byte. Floats use the same shortest round-trip as `repr`.
 */
object CanonicalJson {
    fun bytes(value: Any?): ByteArray = encode(value).toByteArray(StandardCharsets.UTF_8)

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> pythonFloat(value.toDouble())
        is Double -> pythonFloat(value)
        is String -> pythonString(value)
        is Map<*, *> -> {
            val keys = value.keys.map { it as String }.sorted()
            keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
                pythonString(key) + ":" + encode(value[key])
            }
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { encode(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { encode(it) }
        else -> error("cannot canonicalise ${value::class.java.name}")
    }

    fun pythonString(s: String): String {
        val out = StringBuilder(s.length + 2)
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    val cp = ch.code
                    if (cp < 0x20 || cp == 0x7F || cp > 0x7E) {
                        out.append("\\u")
                        out.append(cp.toString(16).padStart(4, '0'))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    /**
     * Match CPython `json.dumps` for a finite float: shortest round-trip, fixed point when
     * the exponent is in [-4, 15], otherwise scientific with a signed two-digit exponent.
     */
    fun pythonFloat(d: Double): String {
        require(d.isFinite()) { "non-finite float" }
        if (d == 0.0) {
            return if (1.0 / d < 0.0) "-0.0" else "0.0"
        }
        val negative = d < 0.0
        val (digits, exp) = significand(kotlin.math.abs(d))
        val body = if (exp >= 16 || exp <= -5) scientific(digits, exp) else fixed(digits, exp)
        return if (negative) "-$body" else body
    }

    private fun significand(abs: Double): Pair<String, Int> {
        val java = java.lang.Double.toString(abs)
        val eIdx = java.indexOf('E')
        val mantissa: String
        val expAdj: Int
        if (eIdx >= 0) {
            mantissa = java.substring(0, eIdx)
            expAdj = java.substring(eIdx + 1).toInt()
        } else {
            mantissa = java
            expAdj = 0
        }
        val parts = mantissa.split('.')
        val whole = parts[0]
        val frac = if (parts.size > 1) parts[1] else ""
        val firstExp = if (whole.trimStart('0').isEmpty()) {
            val lead = frac.indexOfFirst { it != '0' }
            -(lead + 1) + expAdj
        } else {
            whole.trimStart('0').length - 1 + expAdj
        }
        val raw = (whole + frac).trimStart('0').trimEnd('0')
        val digits = raw.ifEmpty { "0" }
        return digits to firstExp
    }

    private fun scientific(digits: String, exp: Int): String {
        val mant = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        val sign = if (exp >= 0) "+" else "-"
        val mag = kotlin.math.abs(exp).toString().padStart(2, '0')
        return "${mant}e$sign$mag"
    }

    private fun fixed(digits: String, exp: Int): String {
        return if (exp >= 0) {
            if (exp >= digits.length - 1) {
                digits + "0".repeat(exp - (digits.length - 1)) + ".0"
            } else {
                digits.substring(0, exp + 1) + "." + digits.substring(exp + 1)
            }
        } else {
            "0." + "0".repeat(-exp - 1) + digits
        }
    }
}

fun canonical(value: Any?): ByteArray = CanonicalJson.bytes(value)

fun sha256(data: ByteArray): ByteArray =
    java.security.MessageDigest.getInstance("SHA-256").digest(data)

fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }
