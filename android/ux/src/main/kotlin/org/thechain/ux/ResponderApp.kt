package org.thechain.ux

import org.thechain.core.Box
import org.thechain.core.Opened

const val ACCEPT = "accept"
const val DECLINE = "decline"
const val REFER = "refer"

val REASONS = setOf("too_far", "unsafe", "busy", "low_battery", "other")

val ICONS = mapOf(ACCEPT to "check", DECLINE to "cross", REFER to "person")

data class Prompt(
    val pid: String,
    val caseId: String,
    val responder: String,
    val kind: String,
    val text: String,
    val risk: String,
    val abort: String,
    val choices: List<Pair<String, String>>,
    val shownAt: Double,
    val expiresAt: Double,
)

data class Response(
    val pid: String,
    val caseId: String,
    val responder: String,
    val action: String,
    val t: Double,
    val reason: String? = null,
    val referral: String? = null,
)

/** The slice of an M7 escalation this app can drive. */
interface EscalationActions {
    fun ack(responder: String, now: Double): Boolean
    fun decline(responder: String, now: Double): String?
    fun refer(responder: String, to: String?, now: Double): String?
}

class ResponderApp(
    val me: String,
    val ackTimeout: Double = 120.0,
) {
    private val open = linkedMapOf<String, Prompt>()
    private var n = 0
    val onAccept: MutableList<(Response) -> Unit> = mutableListOf()

    fun show(caseId: String, justification: Justification, now: Double): Prompt {
        n += 1
        val prompt = Prompt(
            pid = "$caseId|$me|$n",
            caseId = caseId,
            responder = me,
            kind = justification.kind,
            text = justification.text,
            risk = justification.risk,
            abort = justification.abort,
            choices = listOf(ACCEPT, DECLINE, REFER).map { it to ICONS.getValue(it) },
            shownAt = now,
            expiresAt = now + ackTimeout,
        )
        open[prompt.pid] = prompt
        return prompt
    }

    fun pending(now: Double): List<Prompt> = open.values.filter { now < it.expiresAt }

    fun respond(
        pid: String,
        action: String,
        now: Double,
        reason: String? = null,
        referral: String? = null,
    ): Response {
        val prompt = open[pid] ?: throw IllegalArgumentException("unknown or already answered prompt")
        if (now >= prompt.expiresAt) {
            open.remove(pid)
            throw IllegalArgumentException("prompt expired")
        }
        if (action !in ICONS) throw IllegalArgumentException("bad action '$action'")
        if (action == REFER && (referral.isNullOrEmpty() || referral == me)) {
            throw IllegalArgumentException("referral needs someone other than you")
        }
        if (action == DECLINE && reason != null && reason !in REASONS) {
            throw IllegalArgumentException("bad reason '$reason'")
        }
        open.remove(pid)
        val response = Response(
            pid = pid,
            caseId = prompt.caseId,
            responder = me,
            action = action,
            t = now,
            reason = if (action == DECLINE) reason else null,
            referral = if (action == REFER) referral else null,
        )
        if (action == ACCEPT) {
            for (cb in onAccept) cb(response)
        }
        return response
    }

    fun openDetails(box: Box, blob: ByteArray): Opened? = box.open(blob)
}

fun apply(escalation: EscalationActions, response: Response, now: Double): String? = when (response.action) {
    ACCEPT -> {
        escalation.ack(response.responder, now)
        null
    }
    DECLINE -> escalation.decline(response.responder, now)
    else -> escalation.refer(response.responder, response.referral, now)
}
