package org.thechain.identity

import org.thechain.core.CanonicalJson
import org.thechain.core.Signer
import org.thechain.core.canonical
import org.thechain.core.parseJsonObject
import java.security.SecureRandom

/** Wire encode/decode for [Handshake] Nearby payloads (`t=hs2`). */
object HandshakeWire {
    const val TYPE = "hs2"

    fun encode(hs: Handshake): ByteArray =
        CanonicalJson.encode(
            mapOf(
                "t" to TYPE,
                "nodeId" to hs.nodeId,
                "endpointId" to hs.endpointId,
                "nonceHex" to hs.nonceHex,
                "peerNonceHex" to hs.peerNonceHex,
                "timestampMs" to hs.timestampMs,
                "sig" to hs.sig,
                "pub" to hs.pub,
            ),
        ).toByteArray(Charsets.UTF_8)

    fun decode(data: ByteArray): Handshake? {
        val map = parseJsonObject(String(data, Charsets.UTF_8)) ?: return null
        if (map["t"] != TYPE) return null
        val nodeId = map["nodeId"] as? String ?: return null
        val endpointId = map["endpointId"] as? String ?: return null
        val nonceHex = map["nonceHex"] as? String ?: return null
        val peerNonceHex = map["peerNonceHex"] as? String ?: ""
        val timestampMs = (map["timestampMs"] as? Number)?.toLong() ?: return null
        val sig = map["sig"] as? String ?: return null
        val pub = map["pub"] as? String ?: ""
        return Handshake(nodeId, endpointId, nonceHex, peerNonceHex, timestampMs, sig, pub)
    }

    fun isHandshake(data: ByteArray): Boolean {
        if (data.isEmpty() || data[0] != '{'.code.toByte()) return false
        return decode(data) != null
    }
}

/**
 * Per-endpoint mutual handshake. Local [endpointId] is this phone's Nearby id for the peer.
 * Mesh frames must not be attributed to a peer until [verifiedPeerId] is set.
 */
class LinkHandshake(
    val localNodeId: String,
    val endpointId: String,
    private val signer: Signer,
    private val table: HandshakeTable,
    private val nowMs: () -> Long,
    private val localPub: String = "",
    val localNonceHex: String = randomNonceHex(),
) {
    var verifiedPeerId: String? = null
        private set
    private var peerNonceHex: String? = null
    private var sentComplete = false

    fun initialOffer(): Handshake =
        table.offer(localNodeId, endpointId, localNonceHex, nowMs(), signer, localPub)

    /**
     * Ingest a remote handshake. Returns an outbound reply when we learn the peer nonce
     * and still owe a completed transcript; null otherwise.
     */
    fun onRemote(remote: Handshake): Handshake? {
        if (verifiedPeerId != null) return null
        if (remote.nodeId.isEmpty() || remote.nodeId == localNodeId) return null
        if (remote.nonceHex.isEmpty() || remote.nonceHex == localNonceHex) return null

        if (peerNonceHex == null) {
            peerNonceHex = remote.nonceHex
        }

        if (remote.peerNonceHex == localNonceHex) {
            val accepted = table.accept(localNodeId, localNonceHex, remote, nowMs(), signer)
            if (accepted != null && adopt(accepted, remote.pub)) {
                verifiedPeerId = accepted
            }
        }

        val peer = peerNonceHex ?: return null
        if (sentComplete) return null
        sentComplete = true
        val ts = nowMs()
        val body = HandshakeTable.body(localNodeId, endpointId, localNonceHex, peer, ts, localPub)
        return Handshake(
            localNodeId,
            endpointId,
            localNonceHex,
            peer,
            ts,
            signer.sign(localNodeId, canonical(body)),
            localPub,
        )
    }

    /**
     * Presented key must verify (already done in [HandshakeTable.accept]).
     * An enrolled identity must keep the same key. A new key is adopted as a stranger.
     */
    var adopt: (nodeId: String, pub: String) -> Boolean = { _, _ -> true }

    companion object {
        fun randomNonceHex(bytes: Int = 16): String {
            val buf = ByteArray(bytes)
            SecureRandom().nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Tracks unverified Nearby endpoints and promotes them only after [LinkHandshake] accepts.
 * Pure JVM — unit-test spoof / replay / reflection without Android.
 */
class HandshakeCoordinator(
    private val localNodeId: String,
    private val signer: Signer,
    private val table: HandshakeTable = HandshakeTable(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val localPublicKey: ByteArray = ByteArray(0),
    private val adoptPeerKey: (nodeId: String, publicKeyset: ByteArray) -> Boolean = { _, _ -> true },
) {
    private val localPub: String = HandshakeTable.encodePub(localPublicKey)
    private val sessions = linkedMapOf<String, LinkHandshake>()
    private val endpointToPeer = linkedMapOf<String, String>()
    private val peerToEndpoint = linkedMapOf<String, String>()

    data class PeerBinding(val nodeId: String, val endpointId: String, val verified: Boolean)

    fun verifiedPeers(): List<PeerBinding> =
        endpointToPeer.map { (ep, id) -> PeerBinding(id, ep, verified = true) }

    fun verifiedNodeId(endpointId: String): String? = endpointToPeer[endpointId]

    fun endpointFor(nodeId: String): String? = peerToEndpoint[nodeId]

    fun onConnected(endpointId: String): ByteArray {
        val session = sessions.getOrPut(endpointId) { newSession(endpointId) }
        return HandshakeWire.encode(session.initialOffer())
    }

    /** @return outbound handshake bytes to send, if any */
    fun onHandshakePayload(endpointId: String, data: ByteArray): ByteArray? {
        val remote = HandshakeWire.decode(data) ?: return null
        val session = sessions.getOrPut(endpointId) { newSession(endpointId) }
        val reply = session.onRemote(remote)
        val verified = session.verifiedPeerId
        if (verified != null && endpointToPeer[endpointId] == null) {
            endpointToPeer[endpointId] = verified
            peerToEndpoint[verified] = endpointId
        }
        return reply?.let { HandshakeWire.encode(it) }
    }

    fun onDisconnected(endpointId: String) {
        sessions.remove(endpointId)
        val peer = endpointToPeer.remove(endpointId)
        if (peer != null) peerToEndpoint.remove(peer, endpointId)
    }

    fun clear() {
        sessions.clear()
        endpointToPeer.clear()
        peerToEndpoint.clear()
    }

    private fun newSession(endpointId: String): LinkHandshake =
        LinkHandshake(localNodeId, endpointId, signer, table, nowMs, localPub).also { session ->
            session.adopt = adopt@{ nodeId, pub ->
                if (pub.isEmpty()) return@adopt true
                val key = HandshakeTable.decodePub(pub) ?: return@adopt false
                adoptPeerKey(nodeId, key)
            }
        }
}
