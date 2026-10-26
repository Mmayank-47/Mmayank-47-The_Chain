package org.thechain.trust

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.thechain.testing.Vectors

class TrustVectorTest {
    @Test
    fun trustMatchesPython() {
        val spec = Vectors.load("trust")
        val rep = BetaRep(halfLifeS = 100.0)
        rep.record("n", "verified_arrival", 0.0)
        rep.record("n", "failed_claim", 50.0, 1.0)
        val g = VouchGraph(seeds = listOf("root"))
        g.vouch("root", "h1")
        g.vouch("h1", "h2", 1.0)
        val eng = TrustEngine(g, rep)
        assertEquals(spec.getDouble("mean0"), rep.mean("n", 0.0), 1e-9)
        assertEquals(spec.getDouble("mean200"), rep.mean("n", 200.0), 1e-9)
        assertEquals(spec.getDouble("std0"), rep.std("stranger", 0.0), 1e-9)
        assertEquals(spec.getDouble("evidence"), rep.evidence("n", 50.0), 1e-9)
        val want = spec.getJSONObject("scores").doubles()
        val got = g.scores()
        assertEquals(want.size, got.size)
        for ((k, v) in want) assertEquals(v, got.getValue(k), 1e-9, k)
        assertEquals(spec.getDouble("trust_h1"), eng.trust("h1", 0.0), 1e-9)
        assertEquals(spec.getDouble("trust_stranger"), eng.trust("nobody", 0.0), 1e-9)
    }
}

private fun JSONObject.doubles(): Map<String, Double> {
    val out = linkedMapOf<String, Double>()
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        out[k] = getDouble(k)
    }
    return out
}
