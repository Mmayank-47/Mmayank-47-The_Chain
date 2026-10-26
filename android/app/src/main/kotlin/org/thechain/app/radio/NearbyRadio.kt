package org.thechain.app.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.thechain.core.Signer
import org.thechain.identity.HandshakeCoordinator
import org.thechain.identity.HandshakeWire
import org.thechain.transport.Radio
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/**
 * Google Nearby Connections radio ([Strategy.P2P_CLUSTER]).
 *
 * After a Nearby connection succeeds, a signed [HandshakeCoordinator] exchange binds
 * endpointId → verified nodeId. Mesh frames are delivered only for verified peers;
 * advertising names alone are never trusted as node ids.
 *
 * Service id: [SERVICE_ID]. Simultaneous advertise + discover, auto-accept,
 * deterministic tie-break (lower [nodeId] keeps initiator), exponential backoff + jitter.
 */
class NearbyRadio(
    context: Context,
    private val nodeId: String,
    private val signer: Signer,
    private val localPublicKey: ByteArray = ByteArray(0),
    private val adoptPeerKey: (nodeId: String, publicKeyset: ByteArray) -> Boolean = { _, _ -> true },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext),
) : Radio {
    private val main = Handler(Looper.getMainLooper())
    private val inbox = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    private val handshake = HandshakeCoordinator(
        nodeId,
        signer,
        nowMs = nowMs,
        localPublicKey = localPublicKey,
        adoptPeerKey = adoptPeerKey,
    )
    private val pendingNames = ConcurrentHashMap<String, String>()
    private val outgoingAttempts = ConcurrentHashMap<String, Boolean>()
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val started = AtomicBoolean(false)
    private val backoffMs = AtomicLong(1_000L)
    private val lastDiscoveryRestartMs = AtomicLong(0L)

    @Volatile
    var peerCount: Int = 0
        private set

    @Volatile
    var verifiedPeerCount: Int = 0
        private set

    @Volatile
    var advertising: Boolean = false
        private set

    @Volatile
    var discovering: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun isStarted(): Boolean = started.get()

    /** Advertised names we have seen and not yet bound with a verified handshake. */
    fun seenUnverified(): List<String> =
        pendingNames.entries
            .filter { handshake.verifiedNodeId(it.key) == null }
            .map { it.value }
            .distinct()

    fun verifiedPeers(): List<HandshakeCoordinator.PeerBinding> = handshake.verifiedPeers()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        startAdvertising()
        startDiscovery()
    }

    fun stop() {
        started.set(false)
        main.removeCallbacksAndMessages(null)
        runCatching { client.stopAllEndpoints() }
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        handshake.clear()
        pendingNames.clear()
        connectedEndpoints.clear()
        peerCount = 0
        verifiedPeerCount = 0
        advertising = false
        discovering = false
    }

    override fun broadcast(data: ByteArray, now: Double) {
        val peers = handshake.verifiedPeers()
        if (peers.isEmpty()) return
        // Nearby consumes a Payload on send. One object shared across endpoints
        // reaches only the first phone, so the rest of the mesh never sees it.
        Log.i(TAG, "mesh broadcast ${data.size}b to ${peers.size}")
        for (binding in peers) {
            client.sendPayload(binding.endpointId, Payload.fromBytes(data.copyOf()))
                .addOnFailureListener { e -> Log.w(TAG, "broadcast fail ${binding.endpointId}", e) }
        }
    }

    override fun send(to: String, data: ByteArray, now: Double): Boolean {
        val endpointId = handshake.endpointFor(to) ?: return false
        Log.i(TAG, "mesh forward to $to ${data.size}b")
        client.sendPayload(endpointId, Payload.fromBytes(data.copyOf()))
            .addOnFailureListener { e -> Log.w(TAG, "send fail $to", e) }
        return true
    }

    override fun poll(now: Double): List<Pair<String, ByteArray>> {
        if (inbox.isEmpty()) return emptyList()
        val out = ArrayList(inbox)
        inbox.clear()
        return out
    }

    private fun refreshCounts() {
        peerCount = connectedEndpoints.size
        verifiedPeerCount = handshake.verifiedPeers().size
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(nodeId, SERVICE_ID, lifecycleCallback, options)
            .addOnSuccessListener {
                advertising = true
                backoffMs.set(1_000L)
                Log.i(TAG, "advertising as $nodeId")
            }
            .addOnFailureListener { e ->
                if (statusOf(e) == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) {
                    advertising = true
                    return@addOnFailureListener
                }
                advertising = false
                noteError("advertise", e)
                scheduleRestart("advertise")
            }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener {
                discovering = true
                backoffMs.set(1_000L)
                Log.i(TAG, "discovering")
            }
            .addOnFailureListener { e ->
                if (statusOf(e) == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) {
                    discovering = true
                    return@addOnFailureListener
                }
                discovering = false
                noteError("discover", e)
                scheduleRestart("discover")
            }
    }

    private fun scheduleRestart(reason: String) {
        if (!started.get()) return
        val delay = backoffMs.get()
        val jitter = Random.nextLong(0, delay / 2 + 1)
        Log.i(TAG, "backoff ${delay + jitter}ms after $reason")
        main.postDelayed({
            if (!started.get()) return@postDelayed
            backoffMs.updateAndGet { min(it * 2, 60_000L) }
            when (reason) {
                "advertise" -> startAdvertising()
                "discover" -> startDiscovery()
                else -> {
                    startAdvertising()
                    startDiscovery()
                }
            }
        }, delay + jitter)
    }

    private fun requestConnection(endpointId: String, peerName: String) {
        if (connectedEndpoints.contains(endpointId) || handshake.endpointFor(peerName) != null) return
        if (outgoingAttempts.putIfAbsent(endpointId, true) != null) return
        // Lower nodeId keeps initiator: only we request if lexicographically lower.
        if (nodeId > peerName) {
            outgoingAttempts.remove(endpointId)
            return
        }
        pendingNames[endpointId] = peerName
        Log.i(TAG, "connecting to $peerName ($endpointId) local=$nodeId")
        client.requestConnection(nodeId, endpointId, lifecycleCallback)
            .addOnSuccessListener {
                backoffMs.set(1_000L)
                Log.i(TAG, "requested $peerName ($endpointId)")
            }
            .addOnFailureListener { e ->
                outgoingAttempts.remove(endpointId)
                handleConnectFailure(e, "request $peerName", endpointId)
            }
    }

    private fun beginHandshake(endpointId: String) {
        val offer = handshake.onConnected(endpointId)
        client.sendPayload(endpointId, Payload.fromBytes(offer))
            .addOnSuccessListener { Log.i(TAG, "hs offer sent $endpointId") }
            .addOnFailureListener { e -> Log.w(TAG, "hs offer fail $endpointId", e) }
    }

    private fun handleHandshakeBytes(endpointId: String, bytes: ByteArray) {
        val reply = handshake.onHandshakePayload(endpointId, bytes)
        val verified = handshake.verifiedNodeId(endpointId)
        if (verified != null) {
            refreshCounts()
            Log.i(TAG, "hs verified $verified ($endpointId)")
        }
        if (reply != null) {
            client.sendPayload(endpointId, Payload.fromBytes(reply))
                .addOnFailureListener { e -> Log.w(TAG, "hs reply fail $endpointId", e) }
        }
    }

    private fun handleConnectFailure(e: Exception, label: String, endpointId: String? = null) {
        val status = (e as? ApiException)?.statusCode
        when (status) {
            ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT ->
                Log.i(TAG, "$label already connected")
            ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                // Keep advertising and discovering. A radio restart drops the peer we just found.
                lastError = "rejected by peer — install this same build on the other phone"
                Log.w(TAG, "$label rejected (8004); other phone must run this build")
                endpointId?.let { retryLater(it) }
            }
            else -> {
                noteError(label, e)
                if (endpointId != null) retryLater(endpointId) else scheduleRestart("reconnect")
            }
        }
    }

    private fun retryLater(endpointId: String) {
        val name = pendingNames[endpointId] ?: return
        val delay = backoffMs.get().coerceAtMost(8_000L)
        main.postDelayed({
            if (!started.get() || connectedEndpoints.contains(endpointId)) return@postDelayed
            outgoingAttempts.remove(endpointId)
            requestConnection(endpointId, name)
        }, delay)
    }

    /**
     * Nearby does not call onEndpointFound again for an advert it already reported.
     * After a real disconnect the lower nodeId must request that same endpoint,
     * and discovery has to start over if that endpoint id is already dead.
     */
    private fun reconnectAfterDrop(endpointId: String) {
        val name = pendingNames[endpointId]
        Log.i(TAG, "reconnect after drop $endpointId name=$name")
        if (!name.isNullOrBlank()) retryLater(endpointId)
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastDiscoveryRestartMs.get()
        if (now - previous < 8_000L) return
        if (!lastDiscoveryRestartMs.compareAndSet(previous, now)) return
        client.stopDiscovery()
        main.postDelayed({
            if (!started.get()) return@postDelayed
            discovering = false
            startDiscovery()
        }, 400)
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peer = info.endpointName
            if (peer == nodeId) return
            pendingNames[endpointId] = peer
            Log.i(TAG, "found advertised=$peer (unverified until hs)")
            requestConnection(endpointId, peer)
        }

        override fun onEndpointLost(endpointId: String) {
            // Nearby fires this when the BLE advert blinks. The Wi-Fi/Bluetooth
            // connection is often still up. Wiping the handshake here makes every
            // later frame look unverified, so the two phones stop talking.
            if (connectedEndpoints.contains(endpointId)) {
                Log.i(TAG, "advertisement lost for connected $endpointId; keeping the link")
                return
            }
            outgoingAttempts.remove(endpointId)
            pendingNames.remove(endpointId)
            Log.i(TAG, "lost endpoint $endpointId")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            if (HandshakeWire.isHandshake(bytes) || HandshakeWire.decode(bytes) != null) {
                handleHandshakeBytes(endpointId, bytes)
                return
            }
            val peer = handshake.verifiedNodeId(endpointId)
            if (peer == null) {
                Log.w(TAG, "drop mesh frame from unverified $endpointId")
                return
            }
            Log.i(TAG, "mesh rx ${bytes.size}b from $peer")
            inbox.add(peer to bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peer = info.endpointName
            pendingNames[endpointId] = peer
            val incoming = info.isIncomingConnection
            // Lower nodeId keeps the initiator role. Reject only the duplicate leg.
            if (!incoming && nodeId > peer) {
                client.rejectConnection(endpointId)
                Log.i(TAG, "tie-break drop outgoing to lower id $peer")
                return
            }
            if (incoming && nodeId < peer) {
                client.rejectConnection(endpointId)
                Log.i(TAG, "tie-break drop incoming from $peer; we initiate")
                return
            }
            Log.i(TAG, "accept $peer incoming=$incoming")
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> Log.w(TAG, "accept failed $peer", e) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            outgoingAttempts.remove(endpointId)
            if (resolution.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                refreshCounts()
                backoffMs.set(1_000L)
                val advertised = pendingNames[endpointId] ?: "?"
                Log.i(TAG, "connected endpoint=$endpointId advertised=$advertised (hs pending)")
                beginHandshake(endpointId)
            } else {
                handleConnectFailure(ApiException(resolution.status), "result $endpointId", endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            outgoingAttempts.remove(endpointId)
            connectedEndpoints.remove(endpointId)
            handshake.onDisconnected(endpointId)
            refreshCounts()
            Log.i(TAG, "disconnected $endpointId")
            // Keep pendingNames. Discovery will not report this phone again,
            // and the lower id is the only side allowed to requestConnection.
            reconnectAfterDrop(endpointId)
        }
    }

    private fun statusOf(e: Exception): Int? = (e as? ApiException)?.statusCode

    private fun noteError(label: String, e: Exception) {
        val code = statusOf(e)
        lastError = "$label ${code ?: e.message}"
        Log.w(TAG, "$label failed status=$code", e)
    }

    companion object {
        const val SERVICE_ID = "org.thechain.mesh.aid"
        private const val TAG = "NearbyRadio"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
