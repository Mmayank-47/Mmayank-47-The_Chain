package org.thechain.identity

import org.thechain.core.Signer
import org.thechain.core.canonical

/**
 * Signed challenge-response that binds a Nearby endpoint id to a node id.
 * Both nonces are covered. A reflected transcript (same nonce both sides, or our own node id)
 * is rejected. Timestamp must sit inside [skewMs] of local time. Seen nonces are remembered.
 */
data class Handshake(
    val nodeId: String,
    val endpointId: String,
    val nonceHex: String,
    val peerNonceHex: String,
    val timestampMs: Long,
    val sig: String,
    /** Base64 public keyset. Empty only for tests that already enrolled both sides. */
    val pub: String = "",
)

class HandshakeTable(private val skewMs: Long = 5 * 60 * 1000, private val maxSeen: Int = 4096) {
    private val seen = ArrayDeque<String>()
    private val seenSet = HashSet<String>()

    fun offer(
        nodeId: String,
        endpointId: String,
        nonceHex: String,
        timestampMs: Long,
        signer: Signer,
        pub: String = "",
    ): Handshake {
        val body = body(nodeId, endpointId, nonceHex, "", timestampMs, pub)
        return Handshake(nodeId, endpointId, nonceHex, "", timestampMs, signer.sign(nodeId, canonical(body)), pub)
    }

    fun accept(
        localNodeId: String,
        localNonceHex: String,
        remote: Handshake,
        nowMs: Long,
        signer: Signer,
    ): String? {
        if (remote.nodeId.isEmpty() || remote.nodeId == localNodeId) return null
        if (remote.nonceHex.isEmpty() || remote.nonceHex == localNonceHex) return null
        if (kotlin.math.abs(nowMs - remote.timestampMs) > skewMs) return null
        val transcript = "$localNonceHex|${remote.nonceHex}|${remote.endpointId}"
        if (transcript in seenSet) return null
        val body = body(remote.nodeId, remote.endpointId, remote.nonceHex, localNonceHex, remote.timestampMs, remote.pub)
        val signed = canonical(body)
        val ok = if (remote.pub.isNotEmpty()) {
            val key = decodePub(remote.pub) ?: return null
            Ed25519DeviceKey.verifyPublic(key, signed, remote.sig)
        } else {
            signer.verify(remote.nodeId, signed, remote.sig)
        }
        if (!ok) return null
        remember(transcript)
        return remote.nodeId
    }

    private fun remember(transcript: String) {
        if (seenSet.add(transcript)) {
            seen.addLast(transcript)
            while (seen.size > maxSeen) seenSet.remove(seen.removeFirst())
        }
    }

    companion object {
        fun body(
            nodeId: String,
            endpointId: String,
            nonce: String,
            peerNonce: String,
            timestampMs: Long,
            pub: String = "",
        ): List<Any> = listOf("hs2", nodeId, endpointId, nonce, peerNonce, timestampMs, pub)

        fun encodePub(key: ByteArray): String =
            if (key.isEmpty()) "" else java.util.Base64.getEncoder().encodeToString(key)

        fun decodePub(text: String): ByteArray? = try {
            if (text.isEmpty()) null else java.util.Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
