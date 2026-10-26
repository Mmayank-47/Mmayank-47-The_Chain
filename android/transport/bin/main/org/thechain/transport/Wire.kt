package org.thechain.transport

import org.json.JSONArray
import org.json.JSONObject
import org.thechain.core.Signer
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

const val MAGIC_BYTE: Int = 0xC1
const val WIRE_VERSION: Int = 1

const val TYPE_HELLO: Int = 1
const val TYPE_DATA: Int = 2
const val TYPE_AE_SUM: Int = 3
const val TYPE_AE_EV: Int = 4
const val TYPE_ACK: Int = 5
const val TYPE_FRAG: Int = 6
const val TYPE_FALLBACK_JSON: Int = 7

const val MAX_LORA_MTU: Int = 255

/**
 * Wire v1 compact binary / JSON frames for simulation.
 * Phones on the air use [WireV2] (CBOR) — see docs/WIRE.md.
 */
object WireCodec {
    fun encode(frame: Map<String, Any?>, signer: Signer? = null): ByteArray {
        val tStr = frame["t"] as? String
        val tCode = when (tStr) {
            "hello" -> TYPE_HELLO
            "data" -> TYPE_DATA
            "ae.sum" -> TYPE_AE_SUM
            "ae.ev" -> TYPE_AE_EV
            "ack" -> TYPE_ACK
            else -> TYPE_FALLBACK_JSON
        }
        val sender = (frame["snd"] ?: frame["id"] ?: "").toString()
        val senderBytes = sender.toByteArray(Charsets.UTF_8)
        val seq = (frame["seq"] as? Number)?.toInt() ?: 0
        val sigHex = frame["sig"] as? String ?: ""
        val sigBytes = if (sigHex.isNotEmpty()) {
            try {
                hexToBytes(sigHex)
            } catch (_: Exception) {
                sigHex.toByteArray(Charsets.UTF_8)
            }
        } else {
            ByteArray(0)
        }

        if (tCode == TYPE_HELLO) {
            val bat = (frame["bat"] as? Number)?.toDouble() ?: 1.0
            val batByte = round(max(0.0, min(1.0, bat)) * 255).toInt()
            val cell = (frame["cell"] as? String ?: "").toByteArray(Charsets.UTF_8)
            @Suppress("UNCHECKED_CAST")
            val bf = frame["bf"] as? List<*> ?: listOf(128, 3, "0")
            val bfM = (bf.getOrNull(0) as? Number)?.toInt() ?: 128
            val bfK = (bf.getOrNull(1) as? Number)?.toInt() ?: 3
            val bfHex = (bf.getOrNull(2)?.toString() ?: "0").toByteArray(Charsets.UTF_8)

            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(MAGIC_BYTE.toByte(), WIRE_VERSION.toByte(), TYPE_HELLO.toByte(), senderBytes.size.toByte()))
            out.write(senderBytes)
            val body = ByteBuffer.allocate(2 + 1 + 1 + cell.size + 2 + 1 + 2 + bfHex.size + 1 + sigBytes.size)
                .order(ByteOrder.BIG_ENDIAN)
            body.putShort(seq.toShort())
            body.put(batByte.toByte())
            body.put(cell.size.toByte())
            body.put(cell)
            body.putShort(bfM.toShort())
            body.put(bfK.toByte())
            body.putShort(bfHex.size.toShort())
            body.put(bfHex)
            body.put(sigBytes.size.toByte())
            body.put(sigBytes)
            out.write(body.array())
            return out.toByteArray()
        }

        val bodyDict = frame.filterKeys { it !in setOf("snd", "seq", "sig") }
        val bodyJson = JSONObject(jsonMap(bodyDict)).toString().toByteArray(Charsets.UTF_8)
        val compressed = zlibCompress(bodyJson)
        val out = ByteArrayOutputStream()
        val hdr = ByteBuffer.allocate(3 + 1 + 2 + 1).order(ByteOrder.BIG_ENDIAN)
        hdr.put(MAGIC_BYTE.toByte())
        hdr.put(WIRE_VERSION.toByte())
        hdr.put(tCode.toByte())
        hdr.put(senderBytes.size.toByte())
        hdr.putShort(seq.toShort())
        hdr.put(sigBytes.size.toByte())
        out.write(hdr.array())
        out.write(senderBytes)
        out.write(sigBytes)
        val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        lenBuf.putShort(compressed.size.toShort())
        out.write(lenBuf.array())
        out.write(compressed)
        return out.toByteArray()
    }

    fun decode(raw: ByteArray, signer: Signer? = null): Map<String, Any?>? {
        if (raw.isEmpty()) return null
        if (raw[0].toInt() and 0xFF != MAGIC_BYTE) {
            return try {
                jsonToMap(JSONObject(String(raw, Charsets.UTF_8)))
            } catch (_: Exception) {
                null
            }
        }
        if (raw.size < 4) return null
        val magic = raw[0].toInt() and 0xFF
        val ver = raw[1].toInt() and 0xFF
        val tCode = raw[2].toInt() and 0xFF
        if (magic != MAGIC_BYTE || ver != WIRE_VERSION) return null

        if (tCode == TYPE_HELLO) {
            val senderLen = raw[3].toInt() and 0xFF
            var offset = 4
            val sender = String(raw, offset, senderLen, Charsets.UTF_8)
            offset += senderLen
            val seq = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
            val batByte = raw[offset + 2].toInt() and 0xFF
            val cellLen = raw[offset + 3].toInt() and 0xFF
            offset += 4
            val bat = kotlin.math.round(batByte / 255.0 * 1000) / 1000.0
            val cellStr = String(raw, offset, cellLen, Charsets.UTF_8)
            val cell = cellStr.ifEmpty { null }
            offset += cellLen
            val bfM = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
            val bfK = raw[offset + 2].toInt() and 0xFF
            val bfHexLen = ((raw[offset + 3].toInt() and 0xFF) shl 8) or (raw[offset + 4].toInt() and 0xFF)
            offset += 5
            val bfHex = String(raw, offset, bfHexLen, Charsets.UTF_8)
            offset += bfHexLen
            val sigLen = raw[offset].toInt() and 0xFF
            offset += 1
            val sigBytes = raw.copyOfRange(offset, offset + sigLen)
            val sig = if (sigLen == 32) bytesToHex(sigBytes) else String(sigBytes, Charsets.UTF_8)
            return linkedMapOf(
                "t" to "hello",
                "id" to sender,
                "snd" to sender,
                "seq" to seq,
                "bat" to bat,
                "cell" to cell,
                "bf" to listOf(bfM, bfK, bfHex),
                "sig" to sig,
            )
        }

        val hdrSize = 3 + 1 + 2 + 1
        if (raw.size < hdrSize) return null
        val senderLen = raw[3].toInt() and 0xFF
        val seq = ((raw[4].toInt() and 0xFF) shl 8) or (raw[5].toInt() and 0xFF)
        val sigLen = raw[6].toInt() and 0xFF
        var offset = hdrSize
        val sender = String(raw, offset, senderLen, Charsets.UTF_8)
        offset += senderLen
        val sigBytes = raw.copyOfRange(offset, offset + sigLen)
        offset += sigLen
        val sig = if (sigLen == 32) bytesToHex(sigBytes) else
            if (sigLen > 0) String(sigBytes, Charsets.UTF_8) else ""
        val bodyLen = ((raw[offset].toInt() and 0xFF) shl 8) or (raw[offset + 1].toInt() and 0xFF)
        offset += 2
        val compressed = raw.copyOfRange(offset, offset + bodyLen)
        val bodyDict = jsonToMap(JSONObject(String(zlibDecompress(compressed), Charsets.UTF_8)))
        val typeStr = when (tCode) {
            TYPE_DATA -> "data"
            TYPE_AE_SUM -> "ae.sum"
            TYPE_AE_EV -> "ae.ev"
            TYPE_ACK -> "ack"
            else -> "unknown"
        }
        val out = linkedMapOf<String, Any?>()
        out.putAll(bodyDict)
        out["t"] = typeStr
        out["snd"] = sender
        out["seq"] = seq
        if (sig.isNotEmpty()) out["sig"] = sig
        return out
    }

    private fun zlibCompress(data: ByteArray): ByteArray {
        val def = Deflater(9)
        def.setInput(data)
        def.finish()
        val buf = ByteArray(data.size + 64)
        val out = ByteArrayOutputStream()
        while (!def.finished()) {
            val n = def.deflate(buf)
            out.write(buf, 0, n)
        }
        def.end()
        return out.toByteArray()
    }

    private fun zlibDecompress(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val buf = ByteArray(4096)
        val out = ByteArrayOutputStream()
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && inf.needsInput()) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }
}

/**
 * Wire v2 deterministic CBOR for signed on-air bodies (docs/WIRE.md).
 *
 * Major types: ints / bytes / text / arrays / maps. Map keys sorted by encoded key bytes.
 * No floats, tags, or indefinite lengths. `encode(decode(x))` is byte-identical for valid input.
 */
object WireV2 {
    fun assertNoFloats(value: Any?) {
        when (value) {
            null, is Boolean, is Byte, is Short, is Int, is Long, is String, is ByteArray -> Unit
            is Float, is Double -> error("WireV2 signed bodies must not contain floats (docs/WIRE.md)")
            is Map<*, *> -> value.values.forEach { assertNoFloats(it) }
            is Iterable<*> -> value.forEach { assertNoFloats(it) }
            is Array<*> -> value.forEach { assertNoFloats(it) }
            else -> error("WireV2 unsupported type ${value::class.java.name}")
        }
    }

    fun encode(body: Map<String, Any?>): ByteArray {
        assertNoFloats(body)
        val out = ByteArrayOutputStream()
        writeValue(out, body)
        return out.toByteArray()
    }

    fun decode(raw: ByteArray): Map<String, Any?> {
        val reader = CborReader(raw)
        val v = reader.readValue()
        if (!reader.exhausted()) error("WireV2 trailing bytes")
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: error("WireV2 top-level must be a map")
    }

    private fun writeValue(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xf6)
            is Boolean -> out.write(if (value) 0xf5 else 0xf4)
            is Byte -> writeInt(out, value.toLong())
            is Short -> writeInt(out, value.toLong())
            is Int -> writeInt(out, value.toLong())
            is Long -> writeInt(out, value)
            is String -> {
                val b = value.toByteArray(Charsets.UTF_8)
                writeHeader(out, 3, b.size.toLong())
                out.write(b)
            }
            is ByteArray -> {
                writeHeader(out, 2, value.size.toLong())
                out.write(value)
            }
            is Map<*, *> -> {
                val entries = value.entries.map { (k, v) ->
                    require(k is String) { "WireV2 map keys must be strings" }
                    val keyBytes = ByteArrayOutputStream().also { writeValue(it, k) }.toByteArray()
                    keyBytes to v
                }.sortedWith { a, b -> compareUnsigned(a.first, b.first) }
                writeHeader(out, 5, entries.size.toLong())
                for ((kb, v) in entries) {
                    out.write(kb)
                    writeValue(out, v)
                }
            }
            is Iterable<*> -> {
                val list = value.toList()
                writeHeader(out, 4, list.size.toLong())
                for (item in list) writeValue(out, item)
            }
            is Array<*> -> {
                writeHeader(out, 4, value.size.toLong())
                for (item in value) writeValue(out, item)
            }
            else -> error("WireV2 unsupported type ${value::class.java.name}")
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, n: Long) {
        if (n >= 0) {
            writeHeader(out, 0, n)
        } else {
            writeHeader(out, 1, -(n + 1))
        }
    }

    private fun writeHeader(out: ByteArrayOutputStream, major: Int, arg: Long) {
        require(arg >= 0)
        val ai = when {
            arg < 24 -> arg.toInt()
            arg < 0x100 -> 24
            arg < 0x10000 -> 25
            arg < 0x100000000L -> 26
            else -> 27
        }
        out.write((major shl 5) or ai)
        when (ai) {
            24 -> out.write(arg.toInt() and 0xff)
            25 -> {
                out.write((arg.toInt() ushr 8) and 0xff)
                out.write(arg.toInt() and 0xff)
            }
            26 -> {
                out.write(((arg ushr 24) and 0xff).toInt())
                out.write(((arg ushr 16) and 0xff).toInt())
                out.write(((arg ushr 8) and 0xff).toInt())
                out.write((arg and 0xff).toInt())
            }
            27 -> {
                for (shift in 56 downTo 0 step 8) {
                    out.write(((arg ushr shift) and 0xff).toInt())
                }
            }
        }
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    private class CborReader(private val data: ByteArray) {
        private var i = 0

        fun exhausted(): Boolean = i >= data.size

        fun readValue(): Any? {
            if (i >= data.size) error("WireV2 truncated")
            val ib = data[i].toInt() and 0xff
            i += 1
            val major = ib ushr 5
            val ai = ib and 0x1f
            if (ai == 31) error("WireV2 indefinite lengths forbidden")
            val arg = readArg(ai)
            return when (major) {
                0 -> arg
                1 -> -1L - arg
                2 -> {
                    val n = arg.toInt()
                    require(n >= 0 && i + n <= data.size) { "WireV2 bad bstr" }
                    val slice = data.copyOfRange(i, i + n)
                    i += n
                    slice
                }
                3 -> {
                    val n = arg.toInt()
                    require(n >= 0 && i + n <= data.size) { "WireV2 bad tstr" }
                    val s = String(data, i, n, Charsets.UTF_8)
                    i += n
                    s
                }
                4 -> {
                    val n = arg.toInt()
                    require(n >= 0) { "WireV2 bad array" }
                    List(n) { readValue() }
                }
                5 -> {
                    val n = arg.toInt()
                    require(n >= 0) { "WireV2 bad map" }
                    val out = linkedMapOf<String, Any?>()
                    var prevKey: ByteArray? = null
                    repeat(n) {
                        val keyStart = i
                        val key = readValue() as? String ?: error("WireV2 map key must be text")
                        val keyBytes = data.copyOfRange(keyStart, i)
                        if (prevKey != null && compareUnsigned(prevKey!!, keyBytes) >= 0) {
                            error("WireV2 map keys not sorted")
                        }
                        prevKey = keyBytes
                        out[key] = readValue()
                    }
                    out
                }
                7 -> when (ai) {
                    20 -> false
                    21 -> true
                    22 -> null
                    25, 26, 27 -> error("WireV2 floats forbidden")
                    else -> error("WireV2 simple/tag $ai unsupported")
                }
                6 -> error("WireV2 tags forbidden")
                else -> error("WireV2 major $major unsupported")
            }
        }

        private fun readArg(ai: Int): Long = when {
            ai < 24 -> ai.toLong()
            ai == 24 -> readBe(1)
            ai == 25 -> readBe(2)
            ai == 26 -> readBe(4)
            ai == 27 -> readBe(8)
            else -> error("WireV2 reserved ai=$ai")
        }

        private fun readBe(n: Int): Long {
            require(i + n <= data.size) { "WireV2 truncated arg" }
            var v = 0L
            repeat(n) {
                v = (v shl 8) or (data[i].toInt() and 0xff).toLong()
                i += 1
            }
            return v
        }
    }
}

/** Splits frames exceeding MTU into fragments <= 255 bytes and reassembles them. */
class Fragmenter(val maxPacketSize: Int = MAX_LORA_MTU) {
    private var msgSeq = 0
    private val rxBuffers = mutableMapOf<Pair<String, Int>, FragBuf>()

    private data class FragBuf(
        val chunks: MutableMap<Int, ByteArray>,
        val total: Int,
        var expiry: Double,
    )

    fun fragment(rawBytes: ByteArray, sender: String = ""): List<ByteArray> {
        if (rawBytes.size <= maxPacketSize) return listOf(rawBytes)
        msgSeq = (msgSeq + 1) and 0xFFFF
        val fragId = msgSeq
        val senderBytes = sender.toByteArray(Charsets.UTF_8)
        val hdrPrefixLen = 3 + 1 + senderBytes.size + 2 + 1 + 1
        var chunkSize = maxPacketSize - hdrPrefixLen
        if (chunkSize <= 0) chunkSize = 128
        val chunks = rawBytes.toList().chunked(chunkSize).map { it.toByteArray() }
        val total = chunks.size
        return chunks.mapIndexed { idx, chunk ->
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(MAGIC_BYTE.toByte(), WIRE_VERSION.toByte(), TYPE_FRAG.toByte(), senderBytes.size.toByte()))
            out.write(senderBytes)
            val mid = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            mid.putShort(fragId.toShort())
            mid.put(idx.toByte())
            mid.put(total.toByte())
            out.write(mid.array())
            out.write(chunk)
            val pkt = out.toByteArray()
            check(pkt.size <= maxPacketSize) { "Fragment ${pkt.size} exceeded $maxPacketSize" }
            pkt
        }
    }

    fun reassemble(pkt: ByteArray, now: Double): ByteArray? {
        val expired = rxBuffers.filter { (_, buf) -> now > buf.expiry }.keys.toList()
        for (k in expired) rxBuffers.remove(k)

        if (pkt.isEmpty() || (pkt[0].toInt() and 0xFF) != MAGIC_BYTE || pkt.size < 4 ||
            (pkt[2].toInt() and 0xFF) != TYPE_FRAG
        ) {
            return pkt
        }
        return try {
            val senderLen = pkt[3].toInt() and 0xFF
            var offset = 4
            val sender = String(pkt, offset, senderLen, Charsets.UTF_8)
            offset += senderLen
            val fragId = ((pkt[offset].toInt() and 0xFF) shl 8) or (pkt[offset + 1].toInt() and 0xFF)
            val idx = pkt[offset + 2].toInt() and 0xFF
            val total = pkt[offset + 3].toInt() and 0xFF
            offset += 4
            val chunkData = pkt.copyOfRange(offset, pkt.size)
            val key = sender to fragId
            val buf = rxBuffers.getOrPut(key) {
                FragBuf(mutableMapOf(), total, now + 15.0)
            }
            buf.chunks[idx] = chunkData
            if (buf.chunks.size == buf.total) {
                rxBuffers.remove(key)
                ByteArrayOutputStream().use { out ->
                    for (i in 0 until buf.total) out.write(buf.chunks[i]!!)
                    out.toByteArray()
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0)
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

internal fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

internal fun jsonMap(map: Map<String, Any?>): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    for ((k, v) in map.entries.sortedBy { it.key }) {
        out[k] = jsonValue(v)
    }
    return out
}

/** Plain maps/lists/nulls for [org.thechain.core.CanonicalJson] (no org.json wrappers). */
internal fun plainJson(map: Map<String, Any?>): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    for ((k, v) in map) out[k] = plainValue(v)
    return out
}

private fun plainValue(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is Map<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        plainJson(v as Map<String, Any?>)
    }
    is List<*> -> v.map { plainValue(it) }
    is JSONArray -> List(v.length()) { plainValue(v.get(it)) }
    is JSONObject -> plainJson(jsonToMap(v))
    else -> v
}

private fun jsonValue(v: Any?): Any? = when (v) {
    null -> JSONObject.NULL
    is Map<*, *> -> {
        @Suppress("UNCHECKED_CAST")
        JSONObject(jsonMap(v as Map<String, Any?>))
    }
    is List<*> -> JSONArray(v.map { jsonValue(it) })
    else -> v
}

@Suppress("UNCHECKED_CAST")
internal fun jsonToMap(obj: JSONObject): MutableMap<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    for (key in obj.keys()) {
        out[key] = jsonFrom(obj.get(key))
    }
    return out
}

private fun jsonFrom(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> jsonToMap(v)
    is JSONArray -> List(v.length()) { jsonFrom(v.get(it)) }
    is Number -> normalizeJsonNumber(v)
    is Boolean, is String -> v
    else -> v
}

/**
 * Map org.json Number flavors to Int or Double for CanonicalJson MAC verification.
 * JSON floats (including 1.0) stay Double; JSON ints become Int when they fit.
 */
private fun normalizeJsonNumber(v: Number): Any {
    when (v) {
        is Double, is Float -> return v.toDouble()
        is Int, is Short, is Byte -> return v.toInt()
        is Long -> return if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) v.toInt() else v
        is java.math.BigInteger -> {
            return if (v in BigInteger.valueOf(Int.MIN_VALUE.toLong())..BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
                v.toInt()
            } else {
                v.toLong()
            }
        }
        is java.math.BigDecimal -> {
            val stripped = v.stripTrailingZeros()
            return if (stripped.scale() > 0) {
                v.toDouble()
            } else if (stripped.toBigInteger() in BigInteger.valueOf(Int.MIN_VALUE.toLong())..BigInteger.valueOf(Int.MAX_VALUE.toLong())) {
                stripped.toInt()
            } else {
                stripped.toLong()
            }
        }
        else -> return v.toDouble()
    }
}
