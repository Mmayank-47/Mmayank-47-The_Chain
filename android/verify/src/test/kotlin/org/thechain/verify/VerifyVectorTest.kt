package org.thechain.verify

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.thechain.testing.Vectors

class VerifyVectorTest {
    @Test
    fun scoresMatchPython() {
        val spec = Vectors.load("verify").getJSONArray("scores")
        val v = Verifier<String, String>()
        val claims = listOf(
            ObservedClaim("a", "n1", "n1", 0.0 to 0.0, 0.0),
            ObservedClaim("b", "n1", "other", 500.0 to 0.0, 1.0),
            ObservedClaim("c", "n2", "n2", 10.0 to 10.0, 2.0),
            ObservedClaim("d", "n1", "n1", 0.0 to 0.0, 4000.0),
        )
        assertEquals(claims.size, spec.length())
        for (i in claims.indices) {
            val c = claims[i]
            val verdict = v.score(c, c.t)
            val row = spec.getJSONObject(i)
            assertEquals(c.id, row.getString("id"))
            assertEquals(row.getDouble("p"), verdict.p, 1e-9, c.id)
            val parts = row.getJSONObject("parts")
            assertEquals(parts.length(), verdict.parts.size, c.id)
            for ((name, value) in parts.doubles()) {
                assertEquals(value, verdict.parts.getValue(name), 1e-9, "${c.id}.$name")
            }
        }
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
