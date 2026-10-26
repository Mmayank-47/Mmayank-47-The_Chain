package org.thechain.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.thechain.app.map.OfflineMap
import java.util.concurrent.atomic.AtomicBoolean
import org.thechain.app.keystore.AndroidKeystore
import org.thechain.app.radio.NearbyRadio
import org.thechain.app.storage.FileStorage
import org.thechain.core.Clock
import org.thechain.hazard.FloodModel
import org.thechain.identity.IdentityLimiter
import org.thechain.identity.Registry
import org.thechain.node.PhoneNode
import org.thechain.transport.IngressGuard
import org.thechain.transport.StrangerLaneLimiter

/**
 * Hosts [PhoneNode] tick on a dedicated dispatcher. START_STICKY + connectedDevice FGS.
 * Exposes [uiState] so Compose observes peers / claims without faking crypto.
 */
class ChainForegroundService : Service() {
    private val tickDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + tickDispatcher)
    private var tickJob: Job? = null

    private var keystore: AndroidKeystore? = null
    private var registry: Registry? = null
    private var radio: NearbyRadio? = null
    private var phoneNode: PhoneNode? = null
    private var storageRef: FileStorage? = null
    private var enrolledPeers: Int = 0
    private var lang: String = "en"
    private val flood = FloodModel()
    private var coarseCell: String? = null
    private var hasFix: Boolean = false
    private val mapBusy = AtomicBoolean(false)
    private var mapNote: String = ""
    private val cardSharedTo = mutableSetOf<String>()
    private var lastDrops: Map<String, Int> = emptyMap()
    private var savedChatCount = -1

    private val binder = LocalBinder()
    private val _uiState = MutableStateFlow(ChainUiState())
    val uiState: StateFlow<ChainUiState> = _uiState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun service(): ChainForegroundService = this@ChainForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        ensureNode()
        FieldLocation.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        FieldLocation.stop()
        radio?.stop()
        phoneNode?.let { node ->
            runCatching { node.saveState() }
        }
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    fun setLang(tag: String) {
        lang = if (tag in setOf("en", "hi", "mr")) tag else "en"
        publishUi()
    }

    fun previewHelp(text: String): Boolean {
        val node = phoneNode ?: return false
        val pos = currentPos()
        return runCatching {
            node.inspectHelp(text.trim(), pos, now = System.currentTimeMillis() / 1000.0, lang = lang)
            publishUi()
            true
        }.onFailure {
            Log.w(TAG, "previewHelp failed", it)
            publishUi(lastHelp = "Help failed: ${it.message}")
        }.getOrDefault(false)
    }

    fun submitHelp(text: String): Boolean {
        val node = phoneNode ?: return false
        val now = System.currentTimeMillis() / 1000.0
        val fix = FieldLocation.lastFix(this)
        val pos = if (fix != null) fix.lat to fix.lon else 0.0 to 0.0
        return runCatching {
            val trace = node.originateHelp(text.trim(), pos, now = now, lang = lang)
            val claimId = trace["claim_id"] as? String
            if (fix != null && claimId != null) {
                node.shareGnss(claimId, fix.lat, fix.lon, fix.accuracyM.toDouble(), fix.timeMs, now)
            }
            val ok = trace["originated"] as? Boolean == true
            publishUi(lastHelp = if (ok) "sent" else "queued-local")
            ok
        }.onFailure {
            Log.w(TAG, "submitHelp failed", it)
            publishUi(lastHelp = "Help failed: ${it.message}")
        }.getOrDefault(false)
    }

    fun runSelfCheck() {
        val node = phoneNode ?: return
        val ks = keystore ?: return
        val reg = registry ?: return
        val store = storageRef ?: return
        _uiState.value = _uiState.value.copy(selfCheck = SelfCheckReport(running = true))
        val report = DeviceSelfCheck.run(
            context = this,
            node = node,
            keystore = ks,
            registry = reg,
            storage = store,
            radioStarted = radio?.isStarted() == true,
            verifiedPeers = radio?.verifiedPeerCount ?: 0,
            pos = currentPos(),
        )
        publishUi(selfCheck = report)
    }

    private fun currentPos(): Pair<Double, Double> {
        val fix = FieldLocation.lastFix(this)
        return if (fix != null) fix.lat to fix.lon else 0.0 to 0.0
    }

    fun downloadMap() {
        if (!mapBusy.compareAndSet(false, true)) return
        val fix = FieldLocation.lastFix(this)
        if (fix == null) {
            mapBusy.set(false)
            mapNote = "no-fix"
            publishUi()
            return
        }
        mapNote = "downloading"
        publishUi()
        OfflineMap.download(
            this,
            fix.lat,
            fix.lon,
            onProgress = { _, _ -> publishUi() },
            onDone = { ok ->
                mapBusy.set(false)
                mapNote = if (ok) "saved" else "failed"
                Log.i(TAG, "maplibre pack ok=$ok")
                publishUi()
            },
        )
    }

    fun acceptClaim(claimId: String): Boolean {
        val node = phoneNode ?: return false
        val me = RescuerProfileStore.load(this)
        val updated = node.acceptClaim(
            claimId = claimId,
            responderId = node.id,
            responderName = me?.name,
            now = System.currentTimeMillis() / 1000.0,
        ) ?: return false
        publishUi()
        Log.i(TAG, "accepted claim ${updated.claimId} by=${updated.acceptedBy} sealed=${updated.sealedHint}")
        return true
    }

    fun saveContact(nodeId: String): Boolean {
        val card = phoneNode?.peerCards?.get(nodeId) ?: return false
        ContactStore.save(this, card.nodeId, card.name, card.occupation)
        publishUi()
        return true
    }

    fun sendChat(to: String, text: String): Boolean {
        val node = phoneNode ?: return false
        if (ContactStore.list(this).none { it.nodeId == to }) return false
        val me = RescuerProfileStore.load(this)
        val ok = node.sendChat(to, me?.name ?: node.id, text, System.currentTimeMillis() / 1000.0)
        Log.i(TAG, "chat to $to stored=$ok chars=${text.trim().length}")
        ChatStore.save(this, node.chats)
        publishUi()
        return ok
    }

    private fun exchangeCards(now: Double) {
        val node = phoneNode ?: return
        val nearby = radio ?: return
        val profile = RescuerProfileStore.load(this) ?: return
        val peers = nearby.verifiedPeers()
        if (peers.isEmpty()) return
        val fresh = peers.filter { it.nodeId !in cardSharedTo }
        if (fresh.isEmpty()) return
        if (node.shareCard(profile.name, profile.occupation, now)) {
            fresh.forEach { cardSharedTo.add(it.nodeId) }
            Log.i(TAG, "shared card with ${fresh.size} peer(s)")
        }
    }

    private fun ensureNode() {
        if (phoneNode != null) return
        val ks = AndroidKeystore.loadOrCreate(this)
        keystore = ks
        if (!ks.hardwareWrapped) {
            Log.w(TAG, "Ed25519 keyset wrapped with software AES (Keystore fallback)")
        }
        val nodeId = "n-${ks.keyId}"
        val reg = Registry { _, _ -> true }
        check(reg.enroll(nodeId, ks, "self", ks.publicKeyset)) {
            "failed to enroll local key"
        }
        PeerEnrollment.exportLocal(this, nodeId, ks.publicKeyset)
        enrolledPeers = PeerEnrollment.loadPeers(this, reg)
        registry = reg

        val limiter = IdentityLimiter(reg, strangerBurst = 1.0)
        val ingressLimiter = object : StrangerLaneLimiter {
            override fun allow(node: String, now: Double, cost: Double): Boolean =
                limiter.allow(node, now, cost)

            override fun allowStranger(node: String, now: Double, cost: Double): Boolean =
                limiter.allowStranger(node, now, cost)
        }
        val guard = IngressGuard(
            limiter = ingressLimiter,
            strangerLane = true,
            signer = reg,
        )

        val nearby = NearbyRadio(
            this,
            nodeId,
            signer = reg,
            localPublicKey = ks.publicKeyset,
            adoptPeerKey = { id, key ->
                val ok = reg.enrollPublic(id, key, "radio")
                if (ok) Log.i(TAG, "radio-enrolled stranger $id")
                else Log.w(TAG, "rejected radio key for $id")
                ok
            },
        )
        radio = nearby
        val clock = Clock { System.currentTimeMillis() }
        val storage = FileStorage(this)
        storageRef = storage
        val node = PhoneNode(
            id = nodeId,
            radio = nearby,
            signer = reg,
            clock = clock,
            storage = storage,
            keystore = ks,
            guardIn = guard,
            persistenceKey = "phone",
        )
        phoneNode = node
        node.chats.addAll(ChatStore.load(this))
        BatteryMonitor.applyToNode(this, node, System.currentTimeMillis() / 1000.0)
        nearby.start()
        instance = this
        Log.i(
            TAG,
            "PhoneNode ready id=$nodeId hardwareWrap=${ks.hardwareWrapped} " +
                "enrolledPeers=$enrolledPeers hs=on battery=${node.batteryLevel}",
        )
        publishUi()
        tickJob = scope.launch {
            var ticks = 0
            while (isActive) {
                val now = System.currentTimeMillis() / 1000.0
                // Sample battery ~every 30s (60 ticks × 500ms) for drain path / P10.
                if (ticks % 60 == 0) {
                    runCatching { BatteryMonitor.applyToNode(this@ChainForegroundService, node, now) }
                        .onFailure { Log.w(TAG, "battery sample failed", it) }
                }
                ticks++
                runCatching {
                    exchangeCards(now)
                    node.syncLivePeers(nearby.verifiedPeers().map { it.nodeId }, now)
                    node.tick(now)
                }.onFailure { Log.w(TAG, "tick failed", it) }
                val drops = node.guard.dropped
                if (drops.isNotEmpty() && drops != lastDrops) {
                    lastDrops = drops.toMap()
                    Log.w(TAG, "ingress drops $lastDrops")
                }
                publishUi()
                delay(TICK_MS)
            }
        }
    }

    private fun publishUi(
        lastHelp: String? = _uiState.value.lastHelpStatus,
        selfCheck: SelfCheckReport = _uiState.value.selfCheck,
    ) {
        val node = phoneNode
        val nearby = radio
        val (level, charging) = runCatching { BatteryMonitor.readLevel(this) }
            .getOrDefault((node?.batteryLevel ?: 1.0) to false)
        FieldLocation.start(this)
        val fix = runCatching { FieldLocation.lastFix(this) }.getOrNull()
        hasFix = fix != null
        coarseCell = fix?.cell ?: node?.lastPreview?.cell ?: coarseCell
        val map = runCatching { OfflineMap.summary(this) }.getOrNull()
        val lines = node?.chats
        if (lines != null && lines.size != savedChatCount) {
            ChatStore.save(this, lines)
            savedChatCount = lines.size
        }
        _uiState.value = ChainUiState(
            nodeId = node?.id,
            hardwareWrapped = keystore?.hardwareWrapped == true,
            connectedEndpoints = nearby?.peerCount ?: 0,
            verifiedPeers = nearby?.verifiedPeers().orEmpty(),
            seenNames = nearby?.seenUnverified().orEmpty(),
            radioError = nearby?.lastError,
            enrolledPeerCount = enrolledPeers,
            publicClaims = node?.publicClaims?.values?.toList().orEmpty(),
            peerCards = node?.peerCards?.values?.filter { it.nodeId != node.id }?.toList().orEmpty(),
            savedContacts = ContactStore.list(this),
            chats = node?.chats.orEmpty().filter { line ->
                ContactStore.list(this).any { it.nodeId == line.peerId }
            },
            distressRemainingS = node?.distressRemainingS(System.currentTimeMillis() / 1000.0) ?: 0.0,
            lastHelpStatus = lastHelp,
            batteryLevel = level,
            batteryCharging = charging,
            timeToSilenceS = node?.silenceSeconds() ?: 0.0,
            lang = lang,
            advertising = nearby?.advertising == true,
            discovering = nearby?.discovering == true,
            radioStarted = nearby?.isStarted() == true,
            coarseCell = coarseCell,
            locationEnabled = FieldLocation.enabled(this),
            hasLocationFix = hasFix,
            gnssLat = fix?.lat,
            gnssLon = fix?.lon,
            gnssAccuracyM = fix?.accuracyM?.toDouble(),
            gnssTimeMs = fix?.timeMs ?: 0L,
            mapTileCount = map?.count ?: 0,
            mapZoom = map?.zoom ?: 0,
            mapStatus = if (mapBusy.get()) "downloading" else mapNote,
            floodLevelM = flood.level(System.currentTimeMillis() / 1000.0),
            ingressDrops = node?.guard?.dropped?.toMap().orEmpty(),
            pipeline = node?.lastPreview,
            selfCheck = selfCheck,
        )
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "chain_mesh"
        private const val NOTIFICATION_ID = 47
        private const val TICK_MS = 500L
        private const val TAG = "ChainFgs"

        @Volatile
        private var instance: ChainForegroundService? = null

        fun nodeIdOrNull(): String? = instance?.phoneNode?.id

        fun peerCount(): Int = instance?.radio?.verifiedPeerCount ?: 0

        fun uiStateOrNull(): StateFlow<ChainUiState>? = instance?.uiState

        fun start(context: Context) {
            val intent = Intent(context, ChainForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
