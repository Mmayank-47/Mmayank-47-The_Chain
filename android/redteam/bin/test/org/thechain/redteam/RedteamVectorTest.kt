package org.thechain.redteam

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.testing.Vectors
import org.thechain.testing.objects
import kotlin.math.abs

class RedteamVectorTest {
    @Test
    fun attackSuiteMatchesVectors() {
        val spec = Vectors.load("redteam").getJSONArray("attacks").objects()
        val byName = spec.associateBy { it.getString("name") }
        val results = runAll()
        assertEquals(byName.keys, results.map { it.name }.toSet())
        for (r in results) {
            val expected = byName.getValue(r.name)
            assertTrue(r.ok, "${r.name}: defended=${r.defended} undefended=${r.undefended} maxOk=${r.maxOk}")
            assertTrue(r.defended <= r.maxOk, "${r.name}: defended ${r.defended} > max_ok ${r.maxOk}")
            assertTrue(r.undefended > r.defended, "${r.name}: undefended ${r.undefended} <= defended ${r.defended}")
            assertEquals(expected.getDouble("undefended"), r.undefended, 1e-9, r.name)
            assertEquals(expected.getDouble("defended"), r.defended, 1e-9, r.name)
            assertEquals(expected.getDouble("max_ok"), r.maxOk, 1e-12, r.name)
            assertEquals(expected.getBoolean("ok"), r.ok, r.name)
            assertTrue(abs(r.undefended - expected.getDouble("undefended")) < 1e-9)
        }
    }
}
