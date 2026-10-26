package org.thechain.parser

/** Enough of CPython `json` for prompt dumps and `JSONDecoder.raw_decode`. */
internal object PyJson {
    fun dumps(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Byte, is Short, is Int, is Long -> value.toString()
        is String -> quote(value)
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}", separator = ", ") { (k, v) ->
            quote(k.toString()) + ": " + dumps(v)
        }
        is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ", ") { dumps(it) }
        else -> error("cannot dump ${value.javaClass.name}")
    }

    fun quote(s: String): String {
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
                    if (cp < 0x20 || cp > 0x7E) {
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

    /** Parse one JSON value starting at the first non-space, or null if it is not valid JSON. */
    fun rawDecode(raw: String, start: Int): Pair<Any?, Int>? {
        return try {
            parseValue(raw, skipWs(raw, start))
        } catch (_: ParseFail) {
            null
        }
    }

    private class ParseFail : Exception()

    private fun skipWs(s: String, i: Int): Int {
        var p = i
        while (p < s.length) {
            val c = s[p]
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break
            p++
        }
        return p
    }

    private fun parseValue(s: String, i: Int): Pair<Any?, Int> {
        if (i >= s.length) throw ParseFail()
        return when (s[i]) {
            '{' -> parseObject(s, i)
            '[' -> parseArray(s, i)
            '"' -> parseString(s, i)
            't' -> literal(s, i, "true", true)
            'f' -> literal(s, i, "false", false)
            'n' -> literal(s, i, "null", null)
            else -> parseNumber(s, i)
        }
    }

    private fun literal(s: String, i: Int, text: String, value: Any?): Pair<Any?, Int> {
        if (!s.startsWith(text, i)) throw ParseFail()
        return value to (i + text.length)
    }

    private fun parseObject(s: String, i: Int): Pair<LinkedHashMap<String, Any?>, Int> {
        var p = skipWs(s, i + 1)
        val map = LinkedHashMap<String, Any?>()
        if (p < s.length && s[p] == '}') return map to (p + 1)
        while (true) {
            if (p >= s.length || s[p] != '"') throw ParseFail()
            val (key, afterKey) = parseString(s, p)
            p = skipWs(s, afterKey)
            if (p >= s.length || s[p] != ':') throw ParseFail()
            val (value, afterValue) = parseValue(s, skipWs(s, p + 1))
            map[key] = value
            p = skipWs(s, afterValue)
            if (p >= s.length) throw ParseFail()
            when (s[p]) {
                ',' -> p = skipWs(s, p + 1)
                '}' -> return map to (p + 1)
                else -> throw ParseFail()
            }
        }
    }

    private fun parseArray(s: String, i: Int): Pair<List<Any?>, Int> {
        var p = skipWs(s, i + 1)
        val list = ArrayList<Any?>()
        if (p < s.length && s[p] == ']') return list to (p + 1)
        while (true) {
            val (value, after) = parseValue(s, p)
            list.add(value)
            p = skipWs(s, after)
            if (p >= s.length) throw ParseFail()
            when (s[p]) {
                ',' -> p = skipWs(s, p + 1)
                ']' -> return list to (p + 1)
                else -> throw ParseFail()
            }
        }
    }

    private fun parseString(s: String, i: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var p = i + 1
        while (p < s.length) {
            val c = s[p]
            when {
                c == '"' -> return sb.toString() to (p + 1)
                c == '\\' -> {
                    if (p + 1 >= s.length) throw ParseFail()
                    when (val esc = s[p + 1]) {
                        '"', '\\', '/' -> {
                            sb.append(esc)
                            p += 2
                        }
                        'b' -> {
                            sb.append('\b')
                            p += 2
                        }
                        'f' -> {
                            sb.append('\u000C')
                            p += 2
                        }
                        'n' -> {
                            sb.append('\n')
                            p += 2
                        }
                        'r' -> {
                            sb.append('\r')
                            p += 2
                        }
                        't' -> {
                            sb.append('\t')
                            p += 2
                        }
                        'u' -> {
                            val (cp, next) = parseUnicode(s, p)
                            sb.appendCodePoint(cp)
                            p = next
                        }
                        else -> throw ParseFail()
                    }
                }
                c.code < 0x20 -> throw ParseFail()
                else -> {
                    sb.append(c)
                    p++
                }
            }
        }
        throw ParseFail()
    }

    private fun parseUnicode(s: String, backslash: Int): Pair<Int, Int> {
        val first = hex4(s, backslash + 2)
        var next = backslash + 6
        if (first in 0xD800..0xDBFF && next + 5 < s.length && s[next] == '\\' && s[next + 1] == 'u') {
            val second = hex4(s, next + 2)
            if (second in 0xDC00..0xDFFF) {
                return Character.toCodePoint(first.toChar(), second.toChar()) to (next + 6)
            }
        }
        return first to next
    }

    private fun hex4(s: String, i: Int): Int {
        if (i + 4 > s.length) throw ParseFail()
        val hex = s.substring(i, i + 4)
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) throw ParseFail()
        return hex.toInt(16)
    }

    private fun parseNumber(s: String, i: Int): Pair<Number, Int> {
        var p = i
        if (p < s.length && s[p] == '-') p++
        if (p >= s.length || !s[p].isDigit()) throw ParseFail()
        if (s[p] == '0') {
            p++
            if (p < s.length && s[p].isDigit()) throw ParseFail()
        } else {
            while (p < s.length && s[p].isDigit()) p++
        }
        var isFloat = false
        if (p < s.length && s[p] == '.') {
            isFloat = true
            p++
            val frac = p
            while (p < s.length && s[p].isDigit()) p++
            if (p == frac) throw ParseFail()
        }
        if (p < s.length && (s[p] == 'e' || s[p] == 'E')) {
            isFloat = true
            p++
            if (p < s.length && (s[p] == '+' || s[p] == '-')) p++
            val exp = p
            while (p < s.length && s[p].isDigit()) p++
            if (p == exp) throw ParseFail()
        }
        val literal = s.substring(i, p)
        if (isFloat) return literal.toDouble() to p
        val asLong = literal.toLong()
        val number: Number = if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) asLong.toInt() else asLong
        return number to p
    }
}

internal fun extractJsonObject(raw: String?): Map<String, Any?>? {
    if (raw.isNullOrEmpty()) return null
    var from = 0
    while (from < raw.length) {
        val brace = raw.indexOf('{', from)
        if (brace < 0) return null
        val parsed = PyJson.rawDecode(raw, brace)
        val value = parsed?.first
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }
        from = brace + 1
    }
    return null
}

internal fun pyListRepr(items: List<String>): String =
    items.joinToString(prefix = "[", postfix = "]", separator = ", ") { item ->
        "'" + item.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
