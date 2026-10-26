package org.thechain.app

import org.thechain.identity.HandshakeCoordinator
import org.thechain.node.PhoneNode

/** Snapshot published by [ChainForegroundService] for Compose. */
data class ChainUiState(
    val nodeId: String? = null,
    val hardwareWrapped: Boolean = false,
    val connectedEndpoints: Int = 0,
    val verifiedPeers: List<HandshakeCoordinator.PeerBinding> = emptyList(),
    val seenNames: List<String> = emptyList(),
    val radioError: String? = null,
    val enrolledPeerCount: Int = 0,
    val publicClaims: List<PhoneNode.PublicClaimView> = emptyList(),
    val peerCards: List<PhoneNode.PeerCard> = emptyList(),
    val savedContacts: List<SavedContact> = emptyList(),
    val chats: List<PhoneNode.ChatMessage> = emptyList(),
    val distressRemainingS: Double = 0.0,
    val lastHelpStatus: String? = null,
    val batteryLevel: Double = 1.0,
    val batteryCharging: Boolean = false,
    val timeToSilenceS: Double = 0.0,
    val lang: String = "en",
    val advertising: Boolean = false,
    val discovering: Boolean = false,
    val radioStarted: Boolean = false,
    val coarseCell: String? = null,
    val locationEnabled: Boolean = false,
    val hasLocationFix: Boolean = false,
    val gnssLat: Double? = null,
    val gnssLon: Double? = null,
    val gnssAccuracyM: Double? = null,
    val gnssTimeMs: Long = 0L,
    val mapTileCount: Int = 0,
    val mapZoom: Int = 0,
    val mapStatus: String = "",
    val floodLevelM: Double = 0.0,
    val ingressDrops: Map<String, Int> = emptyMap(),
    val pipeline: PhoneNode.HelpPreview? = null,
    val selfCheck: SelfCheckReport = SelfCheckReport(),
    val enrollmentNote: String =
        "QR enrollment is future work. For two-phone verify, copy each phone's " +
            "files/me.pub onto the other as files/peers/<nodeId>.pub then restart mesh.",
)

enum class CheckStatus { PASS, FAIL, OPEN }

data class SelfCheckRow(
    val id: String,
    val status: CheckStatus,
    val detail: String,
)

data class SelfCheckReport(
    val rows: List<SelfCheckRow> = emptyList(),
    val running: Boolean = false,
)
