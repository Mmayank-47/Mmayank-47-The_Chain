package org.thechain.battery

import kotlin.math.exp
import kotlin.math.max

enum class Mode {
    NORMAL,
    SAVER,
    BEACON,
}

val DUTY: Map<Mode, Double> = mapOf(
    Mode.NORMAL to 1.0,
    Mode.SAVER to 0.5,
    Mode.BEACON to 0.1,
)

const val SAVER_AT = 0.30
const val BEACON_AT = 0.15
const val LAST_GASP_AT = 0.05

fun mode(level: Double): Mode =
    if (level > SAVER_AT) Mode.NORMAL else if (level > BEACON_AT) Mode.SAVER else Mode.BEACON

/** 0..1. 1 means about to go silent. */
fun urgency(ttsS: Double, tau: Double = 3600.0): Double = exp(-max(0.0, ttsS) / tau)

/** Push the exact location out early while the phone can still transmit. */
fun sendLocationNow(level: Double, ttsS: Double, windowS: Double = 3600.0): Boolean =
    ttsS <= windowS || level <= BEACON_AT

/** Fires exactly once, the first time the level falls to [LAST_GASP_AT] or below. */
class LastGasp {
    var sent: Boolean = false
        private set

    fun shouldSend(level: Double): Boolean {
        if (!sent && level <= LAST_GASP_AT) {
            sent = true
            return true
        }
        return false
    }
}
