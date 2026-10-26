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
        is java.math.BigInteger -> value.toString()
        is java.math.BigDecimal -> {
            val stripped = value.stripTrailingZeros()
            if (stripped.scale() <= 0) stripped.toBigInteger().toString() else pythonFloat(value.toDouble())
        }
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

/**
 * Parse JSON with CPython-like number types: `1.0` stays Double, `1` becomes Int.
 * org.json collapses `1.0` to Integer which breaks HMAC over signed mesh frames.
 */
fun parseJson(text: String): Any? = JsonParse.parse(text)

fun parseJsonObject(text: String): Map<String, Any?>? {
    val v = parseJson(text) ?: return null
    @Suppress("UNCHECKED_CAST")
    return v as? Map<String, Any?>
}

private object JsonParse {
    fun parse(text: String): Any? {
        return try {
            val (v, end) = parseValue(text, skipWs(text, 0))
            if (skipWs(text, end) != text.length) null else v
        } catch (_: Exception) {
            null
        }
    }

    private fun skipWs(s: String, i: Int): Int {
        var p = i
        while (p < s.length && s[p] in " \t\n\r") p++
        return p
    }

    private fun parseValue(s: String, i: Int): Pair<Any?, Int> {
        if (i >= s.length) error("eof")
        return when (s[i]) {
            '{' -> parseObject(s, i)
            '[' -> parseArray(s, i)
            '"' -> parseString(s, i)
            't' -> lit(s, i, "true", true)
            'f' -> lit(s, i, "false", false)
            'n' -> lit(s, i, "null", null)
            else -> parseNumber(s, i)
        }
    }

    private fun lit(s: String, i: Int, text: String, value: Any?): Pair<Any?, Int> {
        require(s.startsWith(text, i))
        return value to (i + text.length)
    }

    private fun parseObject(s: String, i: Int): Pair<Map<String, Any?>, Int> {
        var p = i + 1
        val out = linkedMapOf<String, Any?>()
        p = skipWs(s, p)
        if (p < s.length && s[p] == '}') return out to (p + 1)
        while (true) {
            p = skipWs(s, p)
            val (key, afterKey) = parseString(s, p)
            p = skipWs(s, afterKey)
            require(p < s.length && s[p] == ':')
            p = skipWs(s, p + 1)
            val (v, afterVal) = parseValue(s, p)
            out[key] = v
            p = skipWs(s, afterVal)
            when {
                p < s.length && s[p] == ',' -> p++
                p < s.length && s[p] == '}' -> return out to (p + 1)
                else -> error("bad object")
            }
        }
    }

    private fun parseArray(s: String, i: Int): Pair<List<Any?>, Int> {
        var p = i + 1
        val out = ArrayList<Any?>()
        p = skipWs(s, p)
        if (p < s.length && s[p] == ']') return out to (p + 1)
        while (true) {
            val (v, after) = parseValue(s, skipWs(s, p))
            out.add(v)
            p = skipWs(s, after)
            when {
                p < s.length && s[p] == ',' -> p++
                p < s.length && s[p] == ']' -> return out to (p + 1)
                else -> error("bad array")
            }
        }
    }

    private fun parseString(s: String, i: Int): Pair<String, Int> {
        require(s[i] == '"')
        var p = i + 1
        val out = StringBuilder()
        while (p < s.length) {
            val c = s[p++]
            when (c) {
                '"' -> return out.toString() to p
                '\\' -> {
                    require(p < s.length)
                    when (val e = s[p++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(p + 4 <= s.length)
                            out.append(s.substring(p, p + 4).toInt(16).toChar())
                            p += 4
                        }
                        else -> error("bad escape")
                    }
                }
                else -> out.append(c)
            }
        }
        error("unterminated string")
    }

    private fun parseNumber(s: String, i: Int): Pair<Number, Int> {
        var p = i
        if (p < s.length && s[p] == '-') p++
        require(p < s.length && s[p].isDigit())
        if (s[p] == '0') {
            p++
            require(p >= s.length || !s[p].isDigit())
        } else {
            while (p < s.length && s[p].isDigit()) p++
        }
        var isFloat = false
        if (p < s.length && s[p] == '.') {
            isFloat = true
            p++
            val frac = p
            while (p < s.length && s[p].isDigit()) p++
            require(p > frac)
        }
        if (p < s.length && (s[p] == 'e' || s[p] == 'E')) {
            isFloat = true
            p++
            if (p < s.length && (s[p] == '+' || s[p] == '-')) p++
            val exp = p
            while (p < s.length && s[p].isDigit()) p++
            require(p > exp)
        }
        val lit = s.substring(i, p)
        if (isFloat) return lit.toDouble() to p
        val asLong = lit.toLong()
        return (if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) asLong.toInt() else asLong) to p
    }
}

fun sha256(data: ByteArray): ByteArray =
    java.security.MessageDigest.getInstance("SHA-256").digest(data)

fun sha256Hex(data: ByteArray): String = sha256(data).joinToString("") { "%02x".format(it) }
