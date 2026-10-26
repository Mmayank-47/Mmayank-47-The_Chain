package org.thechain.privacy

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

/**
 * Epoch ids are HMAC-SHA256(seed, label "|" epoch). This is a KDF for rotating ids, not a signature.
 * The sealed-envelope cipher stays in :crypto-fake.
 */
class PseudonymChain(seed: ByteArray, val epochS: Double = 600.0) {
    private val seed = seed.copyOf()

    init {
        require(epochS > 0.0) { "epoch_s must be positive" }
    }

    fun epochOf(t: Double): Int = floor(t / epochS).toInt()

    fun pid(t: Double): String = h("pid".toByteArray(StandardCharsets.US_ASCII), epochOf(t)).copyOf(6).toHex()

    fun bleAddr(t: Double): String {
        val bytes = h("ble".toByteArray(StandardCharsets.US_ASCII), epochOf(t)).copyOf(6)
        bytes[0] = ((bytes[0].toInt() and 0xFC) or 0x02).toByte()
        return bytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun owns(pid: String, t: Double, slack: Int = 1): Boolean {
        val epoch = epochOf(t)
        return (epoch - slack..epoch + slack).any { k ->
            constantTimeEquals(h("pid".toByteArray(StandardCharsets.US_ASCII), k).copyOf(6).toHex(), pid)
        }
    }

    private fun h(label: ByteArray, epoch: Int): ByteArray {
        val msg = label + PIPE + epoch.toString().toByteArray(StandardCharsets.US_ASCII)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(seed, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    private companion object {
        val PIPE: ByteArray = byteArrayOf('|'.code.toByte())
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private fun constantTimeEquals(a: String, b: String): Boolean {
    val left = a.toByteArray(StandardCharsets.US_ASCII)
    val right = b.toByteArray(StandardCharsets.US_ASCII)
    if (left.size != right.size) return false
    var diff = 0
    for (i in left.indices) diff = diff or (left[i].toInt() xor right[i].toInt())
    return diff == 0
}
