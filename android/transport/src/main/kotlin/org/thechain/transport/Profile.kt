package org.thechain.transport

import org.thechain.core.Priority
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

open class RadioProfile(
    val name: String,
    val freqMhz: Double,
    val bandwidthHz: Int,
    val mtu: Int,
    val dutyCycleLimit: Double,
    val txPowerDbm: Double = 14.0,
) {
    open fun calcAirtimeS(payloadBytes: Int): Double =
        throw UnsupportedOperationException("subclass must implement")
}

/** Sub-GHz LoRa modulation (Semtech SX126x/SX127x standard). */
class LoRaProfile(
    name: String = "LoRa-EU868",
    freqMhz: Double = 868.1,
    bandwidthHz: Int = 125_000,
    val spreadingFactor: Int = 7,
    val codingRate: Int = 5,
    val preambleLen: Int = 8,
    txPowerDbm: Double = 14.0,
) : RadioProfile(
    name = name,
    freqMhz = freqMhz,
    bandwidthHz = bandwidthHz,
    mtu = 255,
    dutyCycleLimit = 0.01,
    txPowerDbm = txPowerDbm,
) {
    override fun calcAirtimeS(payloadBytes: Int): Double {
        val tSym = (1 shl spreadingFactor).toDouble() / bandwidthHz
        val tPreamble = (preambleLen + 4.25) * tSym
        val pl = max(0, min(payloadBytes, 255))
        val h = 0
        val de = if (spreadingFactor >= 11 && bandwidthHz <= 125_000) 1 else 0
        val term = ceil(
            (8.0 * pl - 4 * spreadingFactor + 28 + 16 - 20 * h) /
                (4.0 * (spreadingFactor - 2 * de)),
        )
        val nPayload = 8 + max(0.0, term * codingRate)
        return tPreamble + nPayload * tSym
    }
}

/** Bluetooth Low Energy 2.4 GHz PHY. */
class BleProfile(
    name: String = "BLE-1M",
    freqMhz: Double = 2402.0,
    txPowerDbm: Double = 4.0,
) : RadioProfile(
    name = name,
    freqMhz = freqMhz,
    bandwidthHz = 1_000_000,
    mtu = 255,
    dutyCycleLimit = 1.0,
    txPowerDbm = txPowerDbm,
) {
    override fun calcAirtimeS(payloadBytes: Int): Double {
        val totalBits = (payloadBytes + 10) * 8.0
        return totalBits / bandwidthHz
    }
}

/** Tracks airtime usage over a rolling window and enforces duty-cycle limits. */
class AirtimeTracker(
    val profile: RadioProfile,
    val windowS: Double = 3600.0,
) {
    val history = mutableListOf<Pair<Double, Double>>()
    var totalTxBytes: Int = 0
        private set
    var totalAirtimeS: Double = 0.0
        private set

    private fun purge(now: Double) {
        val cutoff = now - windowS
        while (history.isNotEmpty() && history[0].first < cutoff) history.removeAt(0)
    }

    fun windowAirtime(now: Double): Double {
        purge(now)
        return history.sumOf { it.second }
    }

    fun currentDutyCycle(now: Double): Double {
        val used = windowAirtime(now)
        val effective = min(windowS, max(1.0, now))
        return used / effective
    }

    fun canTransmit(
        payloadBytes: Int,
        now: Double,
        priority: Priority = Priority.CHATTER,
    ): Boolean {
        if (priority == Priority.DISTRESS) return true
        if (profile.dutyCycleLimit >= 1.0) return true
        purge(now)
        val newAirtime = profile.calcAirtimeS(payloadBytes)
        val allowed = windowS * profile.dutyCycleLimit
        val used = history.sumOf { it.second }
        return used + newAirtime <= allowed
    }

    fun recordTx(payloadBytes: Int, now: Double): Double {
        val airtime = profile.calcAirtimeS(payloadBytes)
        history.add(now to airtime)
        totalTxBytes += payloadBytes
        totalAirtimeS += airtime
        return airtime
    }

    fun energyDrainMah(airtimeS: Double, supplyVoltage: Double = 3.3): Double {
        val pDbm = profile.txPowerDbm
        val txCurrentMa = if (pDbm >= 10) 45.0 else 10.0
        return txCurrentMa * (airtimeS / 3600.0)
    }
}
