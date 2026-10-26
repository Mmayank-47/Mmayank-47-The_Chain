package org.thechain.parser

import java.text.Normalizer
import java.util.Locale

internal fun isPySpace(cp: Int): Boolean = when (cp) {
    0x0009, 0x000A, 0x000B, 0x000C, 0x000D,
    0x001C, 0x001D, 0x001E, 0x001F, 0x0020,
    0x0085, 0x00A0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000,
    -> true
    in 0x2000..0x200A -> true
    else -> false
}

internal fun isPyAlpha(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.UPPERCASE_LETTER.toInt(),
    Character.LOWERCASE_LETTER.toInt(),
    Character.TITLECASE_LETTER.toInt(),
    Character.MODIFIER_LETTER.toInt(),
    Character.OTHER_LETTER.toInt(),
    -> true
    else -> false
}

/** Python 3 `\w`: letters, numbers, underscore. */
internal fun isPyWord(cp: Int): Boolean {
    if (cp == '_'.code) return true
    return when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(),
        Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(),
        -> true
        else -> false
    }
}

/** `[\wऀ-ॣ०-ॿ]` from the Python tokenizer. */
internal fun isTokenChar(cp: Int): Boolean {
    if (cp in 0x0900..0x0973 || cp in 0x0966..0x097F) return true
    return isPyWord(cp)
}

internal fun pyLen(s: String): Int = s.codePointCount(0, s.length)

/** Python `str.isdigit` for decimal digits (category Nd). */
internal fun pyIsDigit(s: String): Boolean {
    if (s.isEmpty()) return false
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        if (Character.getType(cp) != Character.DECIMAL_DIGIT_NUMBER.toInt()) return false
        i += Character.charCount(cp)
    }
    return true
}

internal fun pyDecimalInt(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        n = n * 10 + Character.getNumericValue(cp)
        i += Character.charCount(cp)
    }
    return n
}

internal fun pySplit(s: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        if (isPySpace(cp)) {
            if (sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        } else {
            sb.appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}

internal fun pyStrip(s: String): String {
    var start = 0
    var end = s.length
    while (start < end) {
        val cp = s.codePointAt(start)
        if (!isPySpace(cp)) break
        start += Character.charCount(cp)
    }
    while (end > start) {
        val cp = s.codePointBefore(end)
        if (!isPySpace(cp)) break
        end -= Character.charCount(cp)
    }
    return s.substring(start, end)
}

internal fun String.takeCodePoints(n: Int): String {
    if (n <= 0) return ""
    var count = 0
    var i = 0
    while (i < length && count < n) {
        i += Character.charCount(codePointAt(i))
        count++
    }
    return substring(0, i)
}

internal fun phraseKey(toks: List<String>): String =
    if (toks.size == 1) toks[0] else toks.joinToString("\u0000")

internal fun phraseKey(toks: List<String>, start: Int, n: Int): String {
    if (n == 1) return toks[start]
    val sb = StringBuilder()
    for (k in 0 until n) {
        if (k > 0) sb.append('\u0000')
        sb.append(toks[start + k])
    }
    return sb.toString()
}

/** NFKC, casefold via lower, nukta fold, strip apostrophes. Matches `ontology.norm`. */
fun norm(text: String): String {
    var t = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    t = t.replace("\u093C", "")
    t = t.replace("'", "").replace("\u2019", "").replace("`", "")
    return t
}

fun tokenize(text: String): List<String> {
    val n = norm(text)
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < n.length) {
        val cp = n.codePointAt(i)
        if (isTokenChar(cp)) {
            sb.appendCodePoint(cp)
        } else if (sb.isNotEmpty()) {
            out.add(sb.toString())
            sb.setLength(0)
        }
        i += Character.charCount(cp)
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
}
