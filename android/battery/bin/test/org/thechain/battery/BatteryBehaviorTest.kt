package org.thechain.battery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BatteryBehaviorTest {
    private fun curve(rate: Double, n: Int = 10, dt: Double = 300.0, start: Double = 0.8) =
        List(n) { i -> Sample(i * dt, start - rate * i * dt) }

    @Test
    fun cleanCurveOkAndRateRecovered() {
        val c = curve(3e-5)
        assertEquals(true to emptyList<String>(), check(c))
        assertEquals(3e-5, fitRate(c), 5e-8)
    }

    @Test
    fun riseWithoutChargingFlaggedWithChargingAllowed() {
        val bad = listOf(Sample(0.0, 0.3), Sample(100.0, 0.5))
        assertFalse(check(bad).first)
        assertTrue(check(listOf(Sample(0.0, 0.3, true), Sample(100.0, 0.5, true))).first)
    }

    @Test
    fun impossibleDrainAndFlatCurveFlagged() {
        assertTrue("drained faster than hardware allows" in check(listOf(Sample(0.0, 0.9), Sample(60.0, 0.1))).second)
        val flat = listOf(Sample(0.0, 0.6), Sample(1800.0, 0.6), Sample(4000.0, 0.6))
        assertTrue("flat curve over a long span" in check(flat).second)
    }

    @Test
    fun outOfRangeFlagged() {
        assertFalse(check(listOf(Sample(0.0, 1.4), Sample(60.0, 1.39))).first)
    }

    @Test
    fun defaultRateWithFewSamplesAndClamp() {
        assertEquals(4e-5, fitRate(listOf(Sample(0.0, 0.5)), default = 4e-5), 0.0)
        assertTrue(fitRate(listOf(Sample(0.0, 0.9), Sample(1.0, 0.1), Sample(2.0, 0.0))) <= MAX_RATE)
    }

    @Test
    fun timeToSilence() {
        assertEquals(4000.0, timeToSilence(0.12, 3e-5), 5e-8)
        assertEquals(0.0, timeToSilence(0.0, 3e-5), 0.0)
    }

    @Test
    fun llr() {
        assertEquals(0.0, batteryLlr(listOf(Sample(0.0, 0.5))), 0.0)
        assertTrue(batteryLlr(curve(3e-5)) > 0.0)
        assertTrue(batteryLlr(listOf(Sample(0.0, 0.3), Sample(100.0, 0.9))) < 0.0)
    }

    @Test
    fun modes() {
        assertEquals(
            listOf(Mode.NORMAL, Mode.SAVER, Mode.SAVER, Mode.BEACON, Mode.BEACON),
            listOf(0.9, 0.3, 0.2, 0.15, 0.05).map { mode(it) },
        )
    }

    @Test
    fun urgencyDecreasesWithTimeLeft() {
        assertTrue(urgency(600.0) > urgency(7200.0))
        assertEquals(1.0, urgency(0.0), 0.0)
    }

    @Test
    fun sendLocationEarlyAt12Percent() {
        val tts = timeToSilence(0.12, 3e-5)
        assertTrue(sendLocationNow(0.12, tts))
        assertFalse(sendLocationNow(0.8, 40000.0))
    }

    @Test
    fun lastGaspFiresOnce() {
        val g = LastGasp()
        assertEquals(
            listOf(false, false, true, false, false),
            listOf(0.2, 0.06, 0.05, 0.04, 0.01).map { g.shouldSend(it) },
        )
    }

    @Test
    fun relaysPreferChargedConnectedAndSkipBeaconMode() {
        val peers = listOf(Peer("a", 0.9, 8), Peer("b", 0.9, 2), Peer("c", 0.10, 20), Peer("d", 0.5, 6))
        assertEquals(listOf("a", "d", "b"), pickRelays(peers, 3).map { it.id })
    }

    @Test
    fun deterministicTies() {
        val peers = listOf(Peer("y", 0.5, 6), Peer("x", 0.5, 6))
        assertEquals(listOf("x", "y"), pickRelays(peers, 2).map { it.id })
    }

    @Test
    fun energyScalesWithRoute() {
        assertTrue(energyNeeded(500.0) < energyNeeded(3000.0))
    }

    @Test
    fun pReachFallsWithDistanceAndLowCharge() {
        assertTrue(pReach(0.9, 500.0) > 0.99)
        assertTrue(pReach(0.9, 8000.0) < 0.1)
        assertTrue(pReach(0.2, 500.0) < pReach(0.9, 500.0))
    }

    @Test
    fun nobodyDispatchedWhoDiesMidRoute() {
        assertTrue(canDispatch(0.8, 1000.0))
        assertFalse(canDispatch(0.25, 2500.0))
    }
}
