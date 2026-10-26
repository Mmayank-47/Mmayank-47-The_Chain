package org.thechain.core

/**
 * Pluggable primitives. The shipping app uses Ed25519 and X25519+ChaCha20-Poly1305 (see :identity).
 * The HMAC stand-in that matches the Python reference lives in :crypto-fake and is not on the
 * app classpath.
 */
interface Signer {
    fun sign(node: String, data: ByteArray): String
    fun verify(node: String, data: ByteArray, sig: String): Boolean
}

data class Opened(val sender: String, val payload: Any?, val sig: String?)

interface Box {
    fun publicBytes(): ByteArray
    fun open(blob: ByteArray): Opened?
}

interface Provider {
    val name: String
    fun macBytes(key: ByteArray, data: ByteArray): ByteArray
    fun random(n: Int): ByteArray
}

/** Encrypt a canonical payload to one recipient. Output is exactly [packetBytes] bytes. */
fun interface Sealer {
    fun seal(
        recipientPublic: ByteArray,
        sender: String,
        payload: Any?,
        nonce: ByteArray?,
        sig: String?,
    ): ByteArray
}
