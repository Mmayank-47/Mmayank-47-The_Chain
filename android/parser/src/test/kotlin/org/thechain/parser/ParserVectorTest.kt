package org.thechain.parser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.thechain.testing.Vectors

class ParserVectorTest {
    private val root = Vectors.load("parser")

    @Test
    fun parsesMatchVectors() {
        val parses = root.getJSONArray("parses")
        for (i in 0 until parses.length()) {
            val row = parses.getJSONObject(i)
            val text = row.getString("text")
            assertClaim(text, row.getJSONObject("out"))
        }
    }

    @Test
    fun urgencyMatchesVectors() {
        val rows = root.getJSONArray("urgency")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val features = row.getJSONArray("features").strings()
            val got = urgencyFromFeatures(row.getString("kind"), features)
            assertEquals(row.getDouble("u"), got, 1e-9, features.toString())
        }
    }

    @Test
    fun badKindRejected() {
        if (!root.getBoolean("bad_kind_rejected")) return
        assertThrows(SchemaError::class.java) {
            validate(
                mapOf(
                    "kind" to "evil",
                    "confidence" to 0.5,
                    "capability_tags" to emptyList<String>(),
                ),
            )
        }
    }

    private fun assertClaim(text: String, out: JSONObject) {
        val got = parse(text)
        assertEquals(out.getString("kind"), got.kind, text)
        assertEquals(out.getJSONArray("capability_tags").strings(), got.capabilityTags, text)
        if (out.isNull("quantity")) assertNull(got.quantity, text) else assertEquals(out.getInt("quantity"), got.quantity, text)
        assertEquals(out.getDouble("urgency"), got.urgency, 1e-9, text)
        assertEquals(out.getJSONArray("features").strings(), got.features, text)
        assertEquals(out.getString("location_text"), got.locationText, text)
        assertEquals(out.getString("language"), got.language, text)
        assertEquals(out.getDouble("confidence"), got.confidence, 1e-9, text)
        assertEquals(out.getString("source"), got.source, text)
    }
}

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
