package org.thechain.node

import org.thechain.battery.Sample
import org.thechain.battery.fitRate
import org.thechain.battery.timeToSilence
import org.thechain.battery.urgency as batteryUrgency
import org.thechain.core.Clock
import org.thechain.core.Dispatcher
import org.thechain.core.Message
import org.thechain.core.Priority
import org.thechain.core.Signer
import org.thechain.core.Storage
import org.thechain.core.WireClaim
import org.thechain.core.needOf
import org.thechain.core.pyUnit
import org.thechain.core.toM3
import org.thechain.identity.Keystore
import org.thechain.matching.Claim as M7Claim
import org.thechain.matching.claimScores
import org.thechain.matching.gate
import org.thechain.merge.AntiEntropy
import org.thechain.merge.Event
import org.thechain.merge.Replica
import org.thechain.merge.attach
import org.thechain.parser.parse
import org.thechain.privacy.Case
import org.thechain.privacy.KIND_CASE
import org.thechain.privacy.publicMessage
import org.thechain.transport.GossipEngine
import org.thechain.transport.IngressGuard
import org.thechain.transport.MeshNode
import org.thechain.transport.NeighborInfo
import org.thechain.transport.Radio
import org.thechain.transport.ReputationSink
import org.thechain.transport.Watchdog
import org.thechain.trust.TrustEngine
import org.thechain.ux.Facts
import org.thechain.ux.Justification
import org.thechain.ux.ResponderApp
import org.thechain.ux.justify
import org.thechain.ux.verifiersFromParts
import org.thechain.verify.ObservedClaim
import org.thechain.verify.Verdict
import org.thechain.verify.Verifier

/**
 * Full phone stack: M0 transport, Dispatcher, M8 replica + anti-entropy,
 * and M2–M10 claim pipeline. Pure Kotlin — no `android.*` imports.
 */
class PhoneNode(
    val id: String,
    val radio: Radio,
    val signer: Signer,
    val clock: Clock,
    val storage: Storage? = null,
    val keystore: Keystore? = null,
    guardIn: IngressGuard? = null,
    dispatcherIn: Dispatcher? = null,
    replicaIn: Replica? = null,
    engineIn: GossipEngine? = null,
    trustEngineIn: TrustEngine? = null,
    verifierIn: Verifier<String, String>? = null,
    var batteryLevel: Double = 1.0,
    var batteryDrainRate: Double = 3e-5,
    val persistenceKey: String? = null,
) {
    val dispatcher: Dispatcher = dispatcherIn ?: Dispatcher()
    val replica: Replica = replicaIn ?: Replica(id, signer, nowMs = { clock.nowMs() })
    val guard: IngressGuard = guardIn ?: IngressGuard(signer = signer)
    val trustEngine: TrustEngine = trustEngineIn ?: TrustEngine()
    val verifier: Verifier<String, String> = verifierIn ?: Verifier()
    val app = ResponderApp(id)
    val pendingClaims: MutableMap<String, Any> = linkedMapOf()
    /** Incoming public m9.case meta for Respond UI (exact location never present). */
    val publicClaims: MutableMap<String, PublicClaimView> = linkedMapOf()
    /** Name+occupation cards heard on the mesh (never blood/phone/medical). */
    val peerCards: MutableMap<String, PeerCard> = linkedMapOf()
    /** Local cases awaiting accept before sealed details leave this phone. */
    private val localCases: MutableMap<String, Case> = linkedMapOf()
    private val pendingPins = linkedMapOf<String, GnssPin>()
    private var activeDistress: ActiveDistress? = null
    /** Message ids this phone is carrying for a claim, so Accept or the 2-minute cap can stop them. */
    private val carriedByClaim = linkedMapOf<String, MutableSet<String>>()
    private val livePeers = mutableSetOf<String>()

    private data class GnssPin(val lat: Double, val lon: Double, val accuracyM: Double, val timeMs: Long)

    private data class ActiveDistress(
        val claimId: String,
        val untilS: Double,
        val case: Case,
        var pin: GnssPin? = null,
    )

    data class PeerCard(val nodeId: String, val name: String, val occupation: String)

    data class ChatMessage(
        val peerId: String,
        val fromId: String,
        val name: String,
        val text: String,
        val timeMs: Long,
    )

    val chats: MutableList<ChatMessage> = mutableListOf()
    private val directChatTries = mutableMapOf<String, Int>()
    private var lastDirectChatAt = -1e9
    val batterySamples: MutableList<Sample> = mutableListOf(Sample(0.0, batteryLevel))

    private val repSink = object : ReputationSink {
        override fun record(relay: String, kind: String, now: Double) {
            trustEngine.rep.record(relay, kind, now)
        }

        override fun mean(relay: String, now: Double): Double = trustEngine.rep.mean(relay, now)
    }

    val watchdog = Watchdog(repSink, ackTimeoutS = 6.0)

    val engine: GossipEngine = if (engineIn != null) {
        if (engineIn.watchdog == null) engineIn.watchdog = watchdog
        if (engineIn.trustFn == null) engineIn.trustFn = { n, now -> trustEngine.trust(n, now) }
        engineIn
    } else {
        GossipEngine(
            id,
            watchdog = watchdog,
            trustFn = { n, now -> trustEngine.trust(n, now) },
            // Every phone in radio range should get a copy in one round.
            // Farther phones get it when an in-between phone relays.
            baseFanout = 64,
        )
    }

    val node = MeshNode(
        id,
        radio,
        this.engine,
        getBattery = { batteryLevel },
        guard = this.guard,
        signer = signer,
    )

    val ae = AntiEntropy(this.replica)

    init {
        attach(this.replica, node, this.dispatcher)
        this.dispatcher.register(KIND_CASE) { msg -> ingestPublicClaim(msg) }
        this.dispatcher.register("m9.pin") { msg -> ingestPin(msg) }
        this.dispatcher.register("m9.card") { msg -> ingestCard(msg) }
        this.dispatcher.register("m9.chat") { msg -> ingestChat(msg) }
        this.dispatcher.register("m9.ack") { msg -> ingestAck(msg) }
        node.frameHandlers["ae.sum"] = { frm, frame, now ->
            val batch = ae.onSummary(frm, frame, now)
            if (batch != null) node.sendFrame(frm, batch.toMutableMap(), now)
        }
        node.frameHandlers["ae.ev"] = { frm, frame, now ->
            ae.onEvents(frm, frame, now)
        }
        if (persistenceKey != null && storage != null && storage.get(persistenceKey) != null) {
            NodePersistence.restore(this, storage, persistenceKey)
        }
    }

    data class PublicClaimView(
        val claimId: String,
        val origin: String,
        val cell: String?,
        val category: String?,
        val need: String?,
        val tag: String?,
        val justificationText: String?,
        val accepted: Boolean = false,
        val sealedHint: String? = null,
        val urgency: Double = 0.0,
        val justificationHi: String? = null,
        val justificationMr: String? = null,
        val gnssLat: Double? = null,
        val gnssLon: Double? = null,
        val gnssAccuracyM: Double? = null,
        val gnssTimeMs: Long = 0L,
        val acceptedBy: String? = null,
        val acceptedByName: String? = null,
        val distressUntilS: Double = 0.0,
    )

    /** Strings the UI can show without holding parser or verifier types. */
    data class HelpPreview(
        val kind: String,
        val features: List<String>,
        val locationText: String,
        val cell: String,
        val urgency: Double,
        val gate: String,
        val pValid: Double,
        val trust: Double,
        val partNames: List<String>,
        val ttsS: Double,
        val drainRate: Double,
        val publicHasPos: Boolean,
        val privateHasPos: Boolean,
        val locationLeaked: Boolean,
        val justificationEn: String?,
        val justificationHi: String?,
        val justificationMr: String?,
    )

    var lastPreview: HelpPreview? = null
        private set

    private fun ingestPublicClaim(msg: Message) {
        if (msg.kind != KIND_CASE) return
        val payload = msg.payload
        val claimId = payload["c"] as? String ?: return
        trackCarried(claimId, msg.msgId)
        // Public wire must never carry exact victim coordinates.
        check(!payload.containsKey("pos")) { "public claim must not include pos" }
        check(!payload.containsKey("loc")) { "public claim must not include loc" }
        if (claimId in publicClaims) return
        val pin = pendingPins.remove(claimId)
        publicClaims[claimId] = PublicClaimView(
            claimId = claimId,
            origin = msg.origin,
            cell = payload["cell"] as? String,
            category = payload["cat"] as? String,
            need = payload["need"] as? String,
            tag = payload["tag"] as? String,
            justificationText = null,
            gnssLat = pin?.lat,
            gnssLon = pin?.lon,
            gnssAccuracyM = pin?.accuracyM,
            gnssTimeMs = pin?.timeMs ?: 0L,
        )
    }

    fun saveState(key: String? = null) {
        val target = key ?: persistenceKey ?: return
        val store = storage ?: return
        NodePersistence.save(this, store, target)
    }

    fun emit(kind: String, data: Map<String, Any?>): Event = replica.emitKind(kind, data)

    fun openClaim(claimId: String, cell: String, urgency: Double): Event =
        replica.openClaim(claimId, cell, urgency)

    fun originate(msg: Message, now: Double): Boolean = node.originate(msg, now)

    fun pValid(claim: ObservedClaim<String, String>): Double = verifier.pValid(claim)

    fun observe(claim: ObservedClaim<String, String>) {
        verifier.observe(claim)
        pendingClaims[claim.id] = claim
    }

    fun tick(now: Double) {
        pulseDistress(now)
        val chatOnly = deliverDirectChat(now)
        node.tick(now, gossip = !chatOnly)
    }

    /** @return true when a chat frame was the only data sent this tick. */
    private fun deliverDirectChat(now: Double): Boolean {
        if (now - lastDirectChatAt < 5.0) return false
        val line = chats.firstOrNull { chat ->
            chat.fromId == id &&
                node.neighbors.containsKey(chat.peerId) &&
                (directChatTries[chatKey(chat)] ?: 0) < 3
        } ?: return false
        val msg = Message(
            kind = "m9.chat",
            priority = Priority.CHATTER,
            origin = id,
            payload = mapOf(
                "to" to line.peerId,
                "name" to line.name,
                "text" to line.text,
                "t" to line.timeMs,
            ),
            hlc = replica.clock.now(),
            created = now,
        )
        node.sendFrame(
            line.peerId,
            mutableMapOf("t" to "data", "m" to msg.toWire(), "tok" to 1),
            now,
            broadcast = false,
            prio = Priority.CHATTER,
        )
        val key = chatKey(line)
        directChatTries[key] = (directChatTries[key] ?: 0) + 1
        lastDirectChatAt = now
        return true
    }

    private fun chatKey(line: ChatMessage) = "${line.peerId}|${line.timeMs}|${line.text}"

    /**
     * Verified Nearby peers are the mesh neighbors. A fresh link resets the replay
     * window so a restarted phone (seq back at 1) is not stuck "out of window".
     */
    fun syncLivePeers(peerIds: Collection<String>, now: Double) {
        val ids = peerIds.filter { it.isNotBlank() && it != id }.toSet()
        livePeers.retainAll(ids)
        for (peer in ids) {
            if (livePeers.add(peer)) guard.resetReplay(peer)
            val existing = node.neighbors[peer]
            if (existing == null) {
                node.neighbors[peer] = NeighborInfo(peer, mutableSetOf<String>(), null, 1.0, now)
            } else {
                existing.lastSeen = now
            }
        }
    }

    fun distressRemainingS(now: Double = clock.nowMs() / 1000.0): Double {
        val active = activeDistress ?: return 0.0
        return (active.untilS - now).coerceAtLeast(0.0)
    }

    /**
     * Publish this phone's display name + occupation after a verified handshake.
     * Blood group and medical fields must never be passed here.
     */
    fun shareCard(name: String, occupation: String, now: Double): Boolean {
        val cleanName = name.trim().take(64)
        val cleanOcc = occupation.trim().take(32)
        if (cleanName.isEmpty()) return false
        peerCards[id] = PeerCard(id, cleanName, cleanOcc)
        val msg = Message(
            kind = "m9.card",
            priority = Priority.CHATTER,
            origin = id,
            payload = mapOf(
                "id" to id,
                "name" to cleanName,
                "occ" to cleanOcc,
            ),
            hlc = replica.clock.now(),
            created = now,
        )
        return originate(msg, now)
    }

    /** Mesh chat addressed to one node. Relays carry it; only the addressee keeps it. */
    fun sendChat(to: String, fromName: String, text: String, now: Double): Boolean {
        val dest = to.trim()
        val body = text.trim().take(CHAT_MAX)
        if (dest.isEmpty() || dest == id || body.isEmpty()) return false
        val timeMs = (now * 1000.0).toLong()
        val name = fromName.trim().take(64).ifEmpty { id }
        chats.add(ChatMessage(dest, id, name, body, timeMs))
        val msg = Message(
            kind = "m9.chat",
            priority = Priority.CHATTER,
            origin = id,
            payload = mapOf(
                "to" to dest,
                "name" to name,
                "text" to body,
                "t" to timeMs,
            ),
            hlc = replica.clock.now(),
            created = now,
        )
        return originate(msg, now)
    }

    private fun ingestChat(msg: Message) {
        if (msg.kind != "m9.chat") return
        val payload = msg.payload
        val dest = payload["to"] as? String ?: return
        if (dest != id || msg.origin == id) return
        val body = (payload["text"] as? String)?.trim()?.take(CHAT_MAX).orEmpty()
        if (body.isEmpty()) return
        val timeMs = (payload["t"] as? Number)?.toLong() ?: (msg.created * 1000.0).toLong()
        if (chats.any { it.fromId == msg.origin && it.timeMs == timeMs && it.text == body }) return
        val name = (payload["name"] as? String)?.trim()?.take(64).orEmpty().ifEmpty { msg.origin }
        chats.add(ChatMessage(msg.origin, msg.origin, name, body, timeMs))
    }

    private fun ingestCard(msg: Message) {
        if (msg.kind != "m9.card") return
        val payload = msg.payload
        val nodeId = (payload["id"] as? String)?.takeIf { it.isNotBlank() } ?: msg.origin
        if (nodeId == id) return
        val name = (payload["name"] as? String)?.trim()?.take(64).orEmpty()
        if (name.isEmpty()) return
        val occ = (payload["occ"] as? String)?.trim()?.take(32).orEmpty()
        // Reject oversized or unexpected keys that look like medical fields.
        if (payload.containsKey("blood") || payload.containsKey("allergies") || payload.containsKey("phone")) return
        peerCards[nodeId] = PeerCard(nodeId, name, occ)
    }

    fun silenceSeconds(): Double =
        timeToSilence(batteryLevel, fitRate(batterySamples, default = batteryDrainRate))

    /**
     * Full claim processing chain: parse → verify → trust → battery → match → disclosure → justify.
     */
    fun runChain(
        rawText: String,
        pos: Pair<Double, Double>,
        now: Double = 0.0,
        radiusM: Double = 20.0,
        pRouteSafe: Double = 0.95,
        pReach: Double = 0.98,
        routeLen: Double = 200.0,
        lang: String = "en",
    ): Map<String, Any?> {
        val trace = linkedMapOf<String, Any?>()

        val parsed = parse(rawText)
        trace["m2_parsed"] = parsed

        val wc = WireClaim.make(id, pos, radiusM, now, parsed.toJson(), signer)
        trace["wire_claim"] = wc

        val m3c = toM3(wc, battery = batteryLevel)
        val observed = ObservedClaim(m3c.id, m3c.node, m3c.src, m3c.pos, m3c.t)
        val verdict = verifier.score(observed, now)
        trace["m3_verdict"] = verdict

        val trustScore = trustEngine.trust(wc.node, now)
        trace["m5_trust"] = trustScore

        val rate = fitRate(batterySamples, default = batteryDrainRate)
        val ttsS = timeToSilence(batteryLevel, rate)
        val batUrgency = batteryUrgency(ttsS)
        trace["m6_battery"] = mapOf("rate" to rate, "tts_s" to ttsS, "urgency" to batUrgency)

        val features = (wc.parsed["features"] as? Iterable<*>)?.mapNotNull { it as? String }.orEmpty()
        val kind = wc.parsed["kind"] as? String ?: "need"
        val (_, urgency, gain) = claimScores(features, pyUnit(wc.parsed["urgency"], 0.3), kind, ttsS)
        val m7c = M7Claim(wc.id, wc.pos, pyUnit(verdict.p), urgency, gain, needOf(wc.parsed), pyUnit(trustScore))
        val gateDecision = gate(m7c.pValid, pRouteSafe, m7c.trust, pReach)
        trace["m7_match"] = mapOf("claim" to m7c, "gate" to gateDecision)

        val case = Case(
            wc.id,
            pos = wc.pos,
            radiusM = wc.radius,
            tags = parsed.capabilityTags,
            features = parsed.features,
            locationText = parsed.locationText,
            // Positions here are WGS84 degrees. 0.005° is about 500 m.
            cellM = 0.005,
        )
        val publicMeta = case.publicPayload()
        trace["m9_disclosure"] = mapOf("case" to case, "public" to publicMeta)

        val tags = listOfNotNull(publicMeta["tag"] as? String)
        val facts = Facts(
            tags = tags,
            distM = routeLen,
            pSafe = pRouteSafe,
            pReach = pReach,
            battery = batteryLevel,
            verifiers = verifiersFromParts(verdict.parts),
            gate = gateDecision,
            skill = publicMeta["need"] as? String,
        )
        val justification = justify(facts, lang = lang)
        trace["m10_facts"] = facts
        trace["m10_justification"] = justification

        if (justification != null) {
            trace["m10_prompt"] = app.show(case.id, justification, now)
        }
        lastPreview = previewFrom(trace, parsed, verdict, trustScore, case, publicMeta, facts)
        return trace
    }

    /** Run the claim chain for display. Does not gossip or open a replica claim. */
    fun inspectHelp(
        rawText: String,
        pos: Pair<Double, Double>,
        now: Double = clock.nowMs() / 1000.0,
        radiusM: Double = 20.0,
        lang: String = "en",
    ): HelpPreview {
        runChain(rawText, pos, now, radiusM, lang = lang)
        return checkNotNull(lastPreview)
    }

    private fun previewFrom(
        trace: Map<String, Any?>,
        parsed: org.thechain.parser.ParsedClaim,
        verdict: Verdict,
        trustScore: Double,
        case: Case,
        publicMeta: Map<String, Any?>,
        facts: Facts,
    ): HelpPreview {
        @Suppress("UNCHECKED_CAST")
        val bat = trace["m6_battery"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val m7 = trace["m7_match"] as Map<String, Any?>
        val m7c = m7["claim"] as M7Claim
        val gateDecision = m7["gate"] as String
        val loc = parsed.locationText
        val leaked = loc.isNotEmpty() && publicMeta.values.any { it?.toString()?.contains(loc) == true }
        return HelpPreview(
            kind = parsed.kind,
            features = parsed.features,
            locationText = loc,
            cell = case.cell,
            urgency = m7c.urgency,
            gate = gateDecision,
            pValid = verdict.p,
            trust = trustScore,
            partNames = verdict.parts.keys.toList(),
            ttsS = (bat["tts_s"] as Number).toDouble(),
            drainRate = (bat["rate"] as Number).toDouble(),
            publicHasPos = publicMeta.containsKey("pos") || publicMeta.containsKey("loc"),
            privateHasPos = case.privatePayload(0.0).containsKey("pos"),
            locationLeaked = leaked,
            justificationEn = justify(facts, "en")?.text,
            justificationHi = justify(facts, "hi")?.text,
            justificationMr = justify(facts, "mr")?.text,
        )
    }

    /**
     * Help path: run the claim chain, open a replica claim, and gossip **public** meta only.
     * Exact coordinates stay in [localCases] until a responder is accepted (M9 two-stage).
     */
    fun originateHelp(
        rawText: String,
        pos: Pair<Double, Double>,
        now: Double = clock.nowMs() / 1000.0,
        radiusM: Double = 20.0,
        pRouteSafe: Double = 0.95,
        pReach: Double = 0.98,
        routeLen: Double = 200.0,
        lang: String = "en",
    ): Map<String, Any?> {
        val trace = runChain(rawText, pos, now, radiusM, pRouteSafe, pReach, routeLen, lang).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val disclosure = trace["m9_disclosure"] as Map<String, Any?>
        val case = disclosure["case"] as Case
        val publicMeta = case.publicPayload()
        require(!publicMeta.containsKey("pos") && !publicMeta.containsKey("loc")) {
            "public payload must omit pos/loc"
        }

        @Suppress("UNCHECKED_CAST")
        val m7 = trace["m7_match"] as Map<String, Any?>
        val m7c = m7["claim"] as M7Claim
        openClaim(case.id, case.cell, m7c.urgency)
        localCases[case.id] = case

        val msg = publicMessage(case, id, replica.clock.now(), now)
        val wireUtf8 = String(msg.wireBytes(), Charsets.UTF_8)
        // Exact victim coordinates / free-text location must not ride the public gossip frame.
        require(!wireUtf8.contains("\"pos\"")) { "public wire must not embed pos" }
        if (case.locationText.isNotEmpty()) {
            require(!wireUtf8.contains(case.locationText)) {
                "location text must not leave phone before accept"
            }
        }
        val originated = originate(msg, now)
        trackCarried(case.id, msg.msgId)
        trace["originated"] = originated
        trace["public_wire"] = msg.toWire()
        trace["claim_id"] = case.id

        val just = trace["m10_justification"] as? Justification
        val preview = lastPreview
        val until = now + DISTRESS_WINDOW_S
        activeDistress = ActiveDistress(claimId = case.id, untilS = until, case = case)
        publicClaims[case.id] = PublicClaimView(
            claimId = case.id,
            origin = id,
            cell = publicMeta["cell"] as? String,
            category = publicMeta["cat"] as? String,
            need = publicMeta["need"] as? String,
            tag = publicMeta["tag"] as? String,
            justificationText = preview?.justificationEn ?: just?.text,
            accepted = false,
            sealedHint = null,
            urgency = preview?.urgency ?: 0.0,
            justificationHi = preview?.justificationHi,
            justificationMr = preview?.justificationMr,
            distressUntilS = until,
        )
        return trace
    }

    /**
     * GNSS pin that rides with a help request. The public case frame stays a coarse cell;
     * this distress frame carries the last fix so every phone on the path can plot it.
     */
    fun shareGnss(
        claimId: String,
        lat: Double,
        lon: Double,
        accuracyM: Double,
        timeMs: Long,
        now: Double,
    ): Boolean {
        if (!lat.isFinite() || !lon.isFinite()) return false
        val pin = GnssPin(lat, lon, accuracyM, timeMs)
        val active = activeDistress
        if (active != null && active.claimId == claimId) {
            active.pin = pin
        }
        val existing = publicClaims[claimId]
        if (existing != null) {
            publicClaims[claimId] = existing.copy(
                gnssLat = lat,
                gnssLon = lon,
                gnssAccuracyM = accuracyM,
                gnssTimeMs = timeMs,
            )
        } else {
            pendingPins[claimId] = pin
        }
        val msg = Message(
            kind = "m9.pin",
            priority = Priority.DISTRESS,
            origin = id,
            payload = mapOf(
                "c" to claimId,
                "lat" to lat,
                "lon" to lon,
                "acc" to accuracyM,
                "t" to timeMs,
            ),
            hlc = replica.clock.now(),
            created = now,
        )
        trackCarried(claimId, msg.msgId)
        return originate(msg, now)
    }

    private fun ingestPin(msg: Message) {
        if (msg.kind != "m9.pin") return
        val payload = msg.payload
        val claimId = payload["c"] as? String ?: return
        trackCarried(claimId, msg.msgId)
        val lat = (payload["lat"] as? Number)?.toDouble() ?: return
        val lon = (payload["lon"] as? Number)?.toDouble() ?: return
        if (!lat.isFinite() || !lon.isFinite()) return
        val pin = GnssPin(
            lat = lat,
            lon = lon,
            accuracyM = (payload["acc"] as? Number)?.toDouble() ?: 0.0,
            timeMs = (payload["t"] as? Number)?.toLong() ?: 0L,
        )
        val existing = publicClaims[claimId]
        if (existing == null) {
            pendingPins[claimId] = pin
            return
        }
        publicClaims[claimId] = existing.copy(
            gnssLat = pin.lat,
            gnssLon = pin.lon,
            gnssAccuracyM = pin.accuracyM,
            gnssTimeMs = pin.timeMs,
        )
    }

    /**
     * Respond Accept: mark public claim accepted, stop the distress window mesh-wide via
     * [m9.ack], and keep sealed details local for cases we originated.
     */
    fun acceptClaim(
        claimId: String,
        responderId: String = id,
        responderName: String? = null,
        now: Double = clock.nowMs() / 1000.0,
    ): PublicClaimView? {
        val view = publicClaims[claimId] ?: return null
        if (view.accepted) return view
        val local = localCases[claimId]
        val sealedHint = when {
            local == null -> "awaiting sealed envelope from origin"
            local.locationText.isNotBlank() -> local.locationText
            else -> "sealed details held locally until Discloser.release($responderId)"
        }
        val name = responderName?.trim()?.take(64)?.ifEmpty { null }
            ?: peerCards[responderId]?.name
        val updated = view.copy(
            accepted = true,
            sealedHint = sealedHint,
            acceptedBy = responderId,
            acceptedByName = name,
            distressUntilS = 0.0,
        )
        publicClaims[claimId] = updated
        clearDistress(claimId)
        announceAck(claimId, responderId, name, now)
        return updated
    }

    private fun announceAck(claimId: String, by: String, name: String?, now: Double) {
        val payload = linkedMapOf<String, Any?>(
            "c" to claimId,
            "by" to by,
        )
        if (!name.isNullOrBlank()) payload["name"] = name
        val msg = Message(
            kind = "m9.ack",
            priority = Priority.DISTRESS,
            origin = id,
            payload = payload,
            hlc = replica.clock.now(),
            created = now,
        )
        originate(msg, now)
    }

    private fun ingestAck(msg: Message) {
        if (msg.kind != "m9.ack") return
        val payload = msg.payload
        val claimId = payload["c"] as? String ?: return
        val by = payload["by"] as? String ?: msg.origin
        val name = (payload["name"] as? String)?.trim()?.take(64)?.ifEmpty { null }
        clearDistress(claimId)
        val existing = publicClaims[claimId]
        if (existing == null) {
            publicClaims[claimId] = PublicClaimView(
                claimId = claimId,
                origin = "",
                cell = null,
                category = null,
                need = null,
                tag = null,
                justificationText = null,
                accepted = true,
                acceptedBy = by,
                acceptedByName = name,
                distressUntilS = 0.0,
            )
            return
        }
        if (existing.accepted && existing.acceptedBy != null) return
        publicClaims[claimId] = existing.copy(
            accepted = true,
            acceptedBy = by,
            acceptedByName = name ?: existing.acceptedByName,
            distressUntilS = 0.0,
        )
    }

    private fun clearDistress(claimId: String) {
        dropCarried(claimId)
        val active = activeDistress
        if (active != null && active.claimId == claimId) {
            activeDistress = null
        }
    }

    private fun trackCarried(claimId: String, mid: String) {
        carriedByClaim.getOrPut(claimId) { linkedSetOf() }.add(mid)
    }

    private fun dropCarried(claimId: String) {
        val mids = carriedByClaim.remove(claimId) ?: return
        for (mid in mids) engine.store.remove(mid)
    }

    /**
     * The original distress stays in the gossip store for [DISTRESS_WINDOW_S].
     * Neighbors who join during that window still lack it, so the next gossip
     * round hands them the same frame. Re-minting a new frame every few seconds
     * blew the ingress rate limit and the help never arrived.
     */
    private fun pulseDistress(now: Double) {
        val active = activeDistress ?: return
        if (now < active.untilS) return
        val claimId = active.claimId
        activeDistress = null
        dropCarried(claimId)
        publicClaims[claimId]?.let {
            if (!it.accepted) publicClaims[claimId] = it.copy(distressUntilS = 0.0)
        }
    }

    companion object {
        const val DISTRESS_WINDOW_S = 120.0
        const val CHAT_MAX = 240
    }
}
