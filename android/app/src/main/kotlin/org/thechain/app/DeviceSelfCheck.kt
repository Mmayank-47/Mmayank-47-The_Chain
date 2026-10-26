package org.thechain.app

import android.content.Context
import android.util.Log
import org.thechain.app.keystore.AndroidKeystore
import org.thechain.app.storage.FileStorage
import org.thechain.core.canonical
import org.thechain.identity.Registry
import org.thechain.node.PhoneNode
import java.io.File
import kotlin.math.max

/**
 * Runs the on-device pipeline once. Radio peers are reported OPEN when none are connected.
 * Ingress probes stay in-process and are not sent on the air.
 */
object DeviceSelfCheck {
    private const val TAG = "ChainSelfCheck"
    private const val SAMPLE =
        "Need clean drinking water for 4 people, Lane 2 Shivaji Nagar"

    fun run(
        context: Context,
        node: PhoneNode,
        keystore: AndroidKeystore,
        registry: Registry,
        storage: FileStorage,
        radioStarted: Boolean,
        verifiedPeers: Int,
        pos: Pair<Double, Double>,
    ): SelfCheckReport {
        val rows = mutableListOf<SelfCheckRow>()
        val preview = runCatching {
            node.inspectHelp(SAMPLE, pos, now = System.currentTimeMillis() / 1000.0)
        }.getOrElse { err ->
            rows += SelfCheckRow("parser", CheckStatus.FAIL, err.message ?: "inspect failed")
            return finish(context, rows)
        }

        rows += check("parser", preview.kind == "need") {
            "kind=${preview.kind} features=${preview.features.joinToString(",")}"
        }
        rows += check("verify", preview.pValid in 0.0..1.0 && preview.partNames.isNotEmpty()) {
            "pValid=${preview.pValid} parts=${preview.partNames.joinToString(",")}"
        }
        rows += check("trust", preview.trust in 0.0..1.0) {
            "trust=${preview.trust}"
        }
        rows += check("battery", preview.ttsS.isFinite() && preview.ttsS >= 0.0 && preview.drainRate.isFinite()) {
            "tts_s=${preview.ttsS} rate=${preview.drainRate}"
        }
        rows += check("hazard", preview.cell.isNotBlank() && !preview.cell.contains(pos.first.toString())) {
            "cell=${preview.cell}"
        }
        rows += check("match", preview.gate in setOf("dispatch", "scout", "probe", "hold")) {
            "gate=${preview.gate} urgency=${preview.urgency}"
        }
        rows += check(
            "privacy",
            !preview.publicHasPos && preview.privateHasPos && !preview.locationLeaked,
        ) {
            "publicPos=${preview.publicHasPos} privatePos=${preview.privateHasPos} leaked=${preview.locationLeaked}"
        }
        rows += check("justify_en", !preview.justificationEn.isNullOrBlank()) {
            preview.justificationEn ?: "missing"
        }
        rows += check("justify_hi", !preview.justificationHi.isNullOrBlank()) {
            preview.justificationHi ?: "missing"
        }
        rows += check("justify_mr", !preview.justificationMr.isNullOrBlank()) {
            preview.justificationMr ?: "missing"
        }

        val payload = "chain-self-check".toByteArray()
        val sig = runCatching { keystore.sign(payload) }.getOrNull()
        val verified = sig != null && runCatching { keystore.verify(payload, sig) }.getOrDefault(false)
        rows += check("keystore", verified) {
            if (keystore.hardwareWrapped) "hardware wrap, sign/verify ok" else "software wrap, sign/verify ok"
        }

        val marker = "ok-${System.currentTimeMillis()}".toByteArray()
        val stored = runCatching {
            storage.put("selfcheck-marker", marker)
            storage.get("selfcheck-marker")?.contentEquals(marker) == true
        }.getOrDefault(false)
        rows += check("storage", stored) { "reloaded selfcheck-marker" }

        val guard = node.guard
        val limit = max(
            guard.limits.maxFrameBytes,
            max(guard.limits.maxHelloBytes, guard.limits.maxAeBytes),
        )
        val beforeSize = guard.dropped["frame_size"] ?: 0
        guard.frameOk(ByteArray(limit + 64))
        val sizeDropped = (guard.dropped["frame_size"] ?: 0) > beforeSize
        rows += check("ingress_oversize", sizeDropped) {
            "frame_size=${guard.dropped["frame_size"]}"
        }

        val seq = (System.nanoTime() % 1_000_000_000L).toInt().coerceAtLeast(1)
        val body = linkedMapOf<String, Any>("snd" to node.id, "seq" to seq, "k" to "selfcheck")
        val frameSig = registry.sign(node.id, canonical(body))
        val frame = body.toMutableMap()
        frame["sig"] = frameSig
        val first = guard.authenticateFrame(node.id, frame)
        val second = guard.authenticateFrame(node.id, frame)
        val replayed = first == node.id && second == null && (guard.dropped["frame_replayed"] ?: 0) > 0
        rows += check("ingress_replay", replayed) {
            "frame_replayed=${guard.dropped["frame_replayed"]}"
        }

        rows += check("handshake", radioStarted) { "coordinator armed with radio" }
        rows += check("nearby_radio", radioStarted) {
            if (radioStarted) "advertising and discovering" else "radio not started"
        }
        rows += if (verifiedPeers > 0) {
            SelfCheckRow("nearby_peer", CheckStatus.PASS, "verified peers=$verifiedPeers")
        } else {
            SelfCheckRow("nearby_peer", CheckStatus.OPEN, "Waiting for a second phone")
        }

        return finish(context, rows)
    }

    private fun check(id: String, ok: Boolean, detail: () -> String): SelfCheckRow =
        SelfCheckRow(id, if (ok) CheckStatus.PASS else CheckStatus.FAIL, detail())

    private fun finish(context: Context, rows: List<SelfCheckRow>): SelfCheckReport {
        val text = rows.joinToString("\n") { "${it.status} ${it.id} ${it.detail}" }
        Log.i(TAG, text)
        runCatching { File(context.filesDir, "selfcheck-report.txt").writeText(text) }
        return SelfCheckReport(rows = rows, running = false)
    }
}
