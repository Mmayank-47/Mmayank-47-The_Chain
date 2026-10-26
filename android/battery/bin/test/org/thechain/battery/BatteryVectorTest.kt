package org.thechain.battery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.thechain.testing.Vectors

class BatteryVectorTest {
    @Test
    fun batteryMatchesPython() {
        val spec = Vectors.load("battery")
        val samples = listOf(Sample(0.0, 0.9), Sample(100.0, 0.89), Sample(200.0, 0.88))
        val fake = List(20) { Sample(it * 300.0, 1.0) }
        assertEquals(spec.getDouble("rate"), fitRate(samples), 1e-9)
        assertEquals(spec.getDouble("tts"), timeToSilence(0.5, 3e-5), 1e-9)
        assertEquals(spec.getDouble("llr_ok"), batteryLlr(samples), 1e-9)
        assertEquals(spec.getDouble("llr_flat"), batteryLlr(fake), 1e-9)
        val relays = pickRelays(listOf(Peer("a", 0.9, 8), Peer("b", 0.1, 20), Peer("c", 0.5, 2)), 2).map { it.id }
        val want = spec.getJSONArray("relays")
        assertEquals(want.length(), relays.size)
        for (i in relays.indices) assertEquals(want.getString(i), relays[i])
        assertEquals(spec.getDouble("p_reach"), pReach(0.8, 500.0), 1e-9)
        assertEquals(spec.getDouble("energy"), energyNeeded(500.0), 1e-9)
    }
}
