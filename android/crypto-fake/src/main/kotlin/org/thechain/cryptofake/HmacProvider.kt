package org.thechain.cryptofake

import org.thechain.core.Opened
import org.thechain.core.Provider
import org.thechain.core.canonical
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 stand-in matching core.crypto.DevProvider in the Python reference.
 * Not a dependency of :app. Shipping signatures are Ed25519 in :identity.
 */
class HmacProvider : Provider {
    override val name: String = "dev-hmac-sha256"
    private val random = SecureRandom()

    override fun macBytes(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun random(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
}

fun macHex(provider: Provider, key: ByteArray, data: ByteArray): String =
    provider.macBytes(key, data).joinToString("") { "%02x".format(it) }

fun ctEqual(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

fun macOk(provider: Provider, key: ByteArray, data: ByteArray, sig: Any?): Boolean {
    if (sig !is String || !sig.all { it.code < 128 }) return false
    return constantTimeHexEqual(macHex(provider, key, data), sig)
}

private fun constantTimeHexEqual(a: String, b: String): Boolean {
    val aa = a.toByteArray(Charsets.US_ASCII)
    val bb = b.toByteArray(Charsets.US_ASCII)
    return ctEqual(aa, bb)
}

fun kdf(provider: Provider, secret: ByteArray, label: ByteArray): ByteArray =
    provider.macBytes(secret, label)

fun keystream(provider: Provider, key: ByteArray, nonce: ByteArray, n: Int): ByteArray {
    val out = ArrayList<Byte>(n)
    var i = 0
    while (out.size < n) {
        val block = provider.macBytes(key, nonce + i.toBigEndian())
        out.addAll(block.toList())
        i += 1
    }
    return out.take(n).toByteArray()
}

private fun Int.toBigEndian(): ByteArray =
    byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte())

class HmacSigner(private val secret: ByteArray, private val provider: Provider = HmacProvider()) : org.thechain.core.Signer {
    override fun sign(node: String, data: ByteArray): String = macHex(provider, secret, data)

    override fun verify(node: String, data: ByteArray, sig: String): Boolean = macOk(provider, secret, data, sig)
}

const val PACKET = 512
const val NONCE_LEN = 16
private const val TAG_LEN = 32
private const val BODY = PACKET - NONCE_LEN - TAG_LEN
const val MAX_PLAIN = BODY - 2

fun sealHmac(
    provider: Provider,
    boxPub: ByteArray,
    sender: String,
    payload: Any?,
    nonce: ByteArray,
    sig: String?,
): ByteArray {
    require(nonce.size == NONCE_LEN)
    val data = canonical(mapOf("s" to sender, "d" to payload, "g" to sig))
    require(data.size <= MAX_PLAIN) { "payload too large for one packet" }
    val body = ByteArray(BODY)
    body[0] = (data.size ushr 8).toByte()
    body[1] = data.size.toByte()
    data.copyInto(body, 2)
    val ct = xor(body, keystream(provider, kdf(provider, boxPub, "enc".toByteArray()), nonce, BODY))
    val tag = provider.macBytes(kdf(provider, boxPub, "mac".toByteArray()), nonce + ct)
    return nonce + ct + tag
}

fun openHmac(provider: Provider, secret: ByteArray, blob: ByteArray): Opened? {
    if (blob.size != PACKET) return null
    val nonce = blob.copyOfRange(0, NONCE_LEN)
    val ct = blob.copyOfRange(NONCE_LEN, NONCE_LEN + BODY)
    val tag = blob.copyOfRange(NONCE_LEN + BODY, PACKET)
    val expect = provider.macBytes(kdf(provider, secret, "mac".toByteArray()), nonce + ct)
    if (!ctEqual(expect, tag)) return null
    val body = xor(ct, keystream(provider, kdf(provider, secret, "enc".toByteArray()), nonce, BODY))
    val n = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
    if (n > MAX_PLAIN) return null
    return try {
        val text = body.copyOfRange(2, 2 + n).toString(Charsets.UTF_8)
        val obj = org.json.JSONObject(text)
        val payload = if (obj.isNull("d")) null else obj.get("d")
        val g = if (obj.isNull("g")) null else obj.getString("g")
        Opened(obj.getString("s"), payload, g)
    } catch (_: Exception) {
        null
    }
}

private fun xor(a: ByteArray, b: ByteArray): ByteArray = ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }
