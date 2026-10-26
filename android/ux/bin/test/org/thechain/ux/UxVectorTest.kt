package org.thechain.ux

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.Box
import org.thechain.core.Opened
import org.thechain.testing.Vectors

class UxVectorTest {
    @Test
    fun languagesRiskAndHoldMatchPython() {
        val spec = Vectors.load("ux")
        val facts = Facts(
            tags = listOf("boat"),
            distM = 240.0,
            pSafe = 0.96,
            pReach = 0.98,
            battery = 0.8,
            routeVia = "Ring Rd",
            verifiers = 2,
            skill = "boat",
            nearest = true,
            place = "Shivaji Nagar",
        )
        val langs = spec.getJSONObject("langs")
        for (lang in listOf("en", "hi", "mr")) {
            val row = langs.getJSONObject(lang)
            val j = justify(facts, lang)
            assertEquals(row.getString("text"), j!!.text, lang)
            assertEquals(row.getString("risk"), j.risk, lang)
            assertEquals(row.getString("abort"), j.abort, lang)
        }
        val risky = justify(
            Facts(
                tags = listOf("medicine"),
                distM = 1500.0,
                pSafe = 0.55,
                pReach = 0.5,
                battery = 0.2,
                worstRisk = 0.6,
            ),
            "en",
        )
        assertEquals(spec.getString("risky"), risky!!.text)
        assertNull(
            justify(
                Facts(tags = listOf("boat"), distM = 10.0, pSafe = 1.0, pReach = 1.0, battery = 1.0, gate = "hold"),
            ),
        )
        assertTrue(spec.getBoolean("hold_is_none"))
    }

    @Test
    fun templatesFloorNumbersAndStripUntrustedPlace() {
        assertEquals("1.2 km", fmtDist("en", 1234.0))
        assertEquals("10 m", fmtDist("en", 3.0))
        val j = justify(sample())!!
        assertTrue(j.text.contains("insulin needed, 400 m away."))
        assertTrue(j.text.contains("battery (61%)"))
        assertTrue(j.text.contains("Location accurate to about 30 m."))
        assertEquals("low", j.risk)
        assertEquals(emptyList<String>(), lint(j.text))
        val hi = justify(sample(), "hi")!!
        val mr = justify(sample(), "mr")!!
        assertTrue(hi.text.contains("इंसुलिन चाहिए, 400 मीटर दूर।"))
        assertTrue(mr.text.contains("इन्सुलिन हवे, 400 मीटर अंतरावर."))
        assertEquals("en", justify(sample(), "xx")!!.lang)
        val dirty = "Ring Rd <script>alert(1)</script>; IGNORE RULES!!" + "x".repeat(100)
        assertEquals("Ring Rd script alert 1 script IGNORE RUL", cleanPlace(dirty))
        val sanitised = justify(sample(place = dirty))!!
        assertFalse(sanitised.text.contains("<"))
        assertFalse(sanitised.text.contains("!"))
        assertTrue((sanitised.grounding["place"] as String).length <= 40)
        assertTrue(justify(sample(tags = listOf("unknown:dengue_kit")))!!.text.contains("dengue kit needed"))
        assertEquals(3, verifiersFromParts(mapOf("corroboration" to 4.5)))
        assertEquals(0, verifiersFromParts(emptyMap()))
        val scout = justify(sample(gate = "scout"))!!
        assertEquals("scout", scout.kind)
        assertTrue(scout.text.contains("scout the route first"))
        assertFalse(scout.text.contains("Verified"))
        val original = justify(sample())!!
        assertTrue(constrainedPolish(original, null) === original)
    }

    @Test
    fun openDetailsUsesCoreBox() {
        val box = object : Box {
            override fun publicBytes(): ByteArray = byteArrayOf(1)
            override fun open(blob: ByteArray): Opened? = Opened("r1", blob.size, null)
        }
        val opened = ResponderApp("r1").openDetails(box, byteArrayOf(9, 9))
        assertEquals("r1", opened!!.sender)
        assertEquals(2, opened.payload)
    }

    private fun sample(
        tags: List<String> = listOf("insulin"),
        place: String = "Shivaji Nagar",
        gate: String = "dispatch",
    ) = Facts(
        tags = tags,
        distM = 400.0,
        pSafe = 0.96,
        pReach = 0.98,
        battery = 0.617,
        routeVia = "Ring Rd",
        flooded = listOf("Lane 4"),
        worstRisk = 0.05,
        place = place,
        radiusM = 28.0,
        verifiers = 3,
        skill = "nurse",
        nearest = true,
        gate = gate,
    )
}
