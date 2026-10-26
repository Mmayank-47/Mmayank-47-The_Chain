package org.thechain.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.CanonicalJson
import org.thechain.core.Clock
import org.thechain.core.MemoryStorage
import org.thechain.cryptofake.HmacSigner
import org.thechain.parser.ParsedClaim
import org.thechain.privacy.Case
import org.thechain.transport.SimMedium
import org.thechain.transport.SimRadio
import org.thechain.ux.Justification
import org.thechain.verify.Verdict

class PhoneNodeTest {
    private val signer = HmacSigner(ByteArray(32) { 7 })

    @Test
    fun runChainProducesAuditableTrace() {
        var t = 1_000_000L
        val clock = Clock { t }
        val medium = SimMedium()
        val node = PhoneNode(
            "a",
            SimRadio("a", medium),
            signer,
            clock,
            storage = MemoryStorage(),
        )
        val trace = node.runChain("Need medic at underpass, severe bleeding", 100.0 to 50.0, now = t / 1000.0)
        val parsed = trace["m2_parsed"] as ParsedClaim
        assertTrue("medic" in parsed.capabilityTags)
        assertTrue(parsed.urgency > 0.5)
        assertNotNull(trace["wire_claim"])
        val verdict = trace["m3_verdict"] as Verdict
        assertTrue(verdict.p > 0.0)
        assertTrue("identity" in verdict.parts)
        assertNotNull(trace["m5_trust"])
        @Suppress("UNCHECKED_CAST")
        val bat = trace["m6_battery"] as Map<String, Any?>
        assertTrue(bat.containsKey("tts_s"))
        @Suppress("UNCHECKED_CAST")
        val m7 = trace["m7_match"] as Map<String, Any?>
        assertEquals(verdict.p, (m7["claim"] as org.thechain.matching.Claim).pValid, 1e-12)
        assertNotNull(trace["m9_disclosure"])
        val just = trace["m10_justification"]
        if (just != null) {
            assertTrue(just is Justification)
            assertNotNull(trace["m10_prompt"])
        }
    }

    @Test
    fun originateHelpPublicWireHidesExactLocation() {
        var t = 1_700_000_000_000L
        val clock = Clock { t }
        val medium = SimMedium()
        medium.link("a", "b")
        val node = PhoneNode(
            "a",
            SimRadio("a", medium),
            signer,
            clock,
            storage = MemoryStorage(),
        )
        val pos = 19.076090 to 72.877392
        val trace = node.originateHelp(
            "Need medic at roof of ward 3, severe bleeding",
            pos,
            now = t / 1000.0,
        )
        assertTrue(trace["originated"] as Boolean)
        @Suppress("UNCHECKED_CAST")
        val wire = trace["public_wire"] as Map<String, Any?>
        val wireJson = CanonicalJson.encode(wire)
        assertFalse(wireJson.contains("\"pos\""))
        assertFalse(wireJson.contains("19.076090"))
        assertFalse(wireJson.contains("72.877392"))
        assertFalse(wireJson.contains("roof of ward 3"))
        @Suppress("UNCHECKED_CAST")
        val disclosure = trace["m9_disclosure"] as Map<String, Any?>
        val case = disclosure["case"] as Case
        assertTrue(case.privatePayload(0.0).containsKey("pos"))
        val view = node.publicClaims.values.first()
        assertFalse(view.accepted)
        val accepted = node.acceptClaim(view.claimId)
        assertNotNull(accepted)
        assertTrue(accepted!!.accepted)
        assertNotNull(accepted.sealedHint)
    }

    @Test
    fun helpReachesPhonesThatAreOnlyConnectedThroughRelays() {
        val clock = Clock { 1_700_000_000_000L }
        val now = 1_700_000_000.0
        val medium = SimMedium()
        // a—b—c—d  and a star a—e, a—f. d never hears a directly.
        val ids = listOf("a", "b", "c", "d", "e", "f")
        val nodes = ids.associateWith { id ->
            PhoneNode(id, SimRadio(id, medium), signer, clock, storage = MemoryStorage())
        }
        medium.link("a", "b")
        medium.link("b", "c")
        medium.link("c", "d")
        medium.link("a", "e")
        medium.link("a", "f")

        nodes.getValue("a").originateHelp(
            "Need clean drinking water for 4 people, Lane 2",
            19.0 to 72.0,
            now = now,
        )
        var t = now
        while (t < now + 20.0) {
            for (id in ids) nodes.getValue(id).tick(t)
            t += 1.0
        }
        val originClaim = nodes.getValue("a").publicClaims.keys.single()
        for (id in listOf("b", "c", "d", "e", "f")) {
            val got = nodes.getValue(id).publicClaims[originClaim]
            assertNotNull(got, "$id should receive the help via the mesh")
            assertEquals("a", got!!.origin)
            assertFalse(got.accepted)
            assertEquals(null, got.sealedHint)
        }
    }

    @Test
    fun gnssPinTravelsWithTheHelpAndNotTheWrittenPlace() {
        val clock = Clock { 1_700_000_000_000L }
        val now = 1_700_000_000.0
        val medium = SimMedium()
        medium.link("a", "b")
        medium.link("b", "c")
        val nodes = listOf("a", "b", "c").associateWith { id ->
            PhoneNode(id, SimRadio(id, medium), signer, clock, storage = MemoryStorage())
        }
        val trace = nodes.getValue("a").originateHelp(
            "Need clean drinking water for 4 people, Lane 2 Shivaji Nagar",
            19.07609 to 72.87739,
            now = now,
        )
        val claimId = trace["claim_id"] as String
        assertTrue(
            nodes.getValue("a").shareGnss(claimId, 19.07609, 72.87739, 8.0, 1_700_000_000_000L, now),
        )
        var t = now
        while (t < now + 20.0) {
            for (id in listOf("a", "b", "c")) nodes.getValue(id).tick(t)
            t += 1.0
        }
        val far = nodes.getValue("c").publicClaims[claimId]
        assertNotNull(far)
        assertEquals(19.07609, far!!.gnssLat!!, 1e-5)
        assertEquals(72.87739, far.gnssLon!!, 1e-5)
        assertEquals(8.0, far.gnssAccuracyM!!, 1e-6)
        val shown = listOf(far.cell, far.need, far.tag, far.justificationText).joinToString(" ")
        assertFalse(shown.contains("Shivaji"))
    }

    @Test
    fun cardSharesNameAndOccupationOnlyAndAckStopsDistressMeshWide() {
        var tMs = 1_700_000_000_000L
        val clock = Clock { tMs }
        val now = tMs / 1000.0
        val medium = SimMedium()
        medium.link("a", "b")
        medium.link("b", "c")
        val nodes = listOf("a", "b", "c").associateWith { id ->
            PhoneNode(id, SimRadio(id, medium), signer, clock, storage = MemoryStorage())
        }
        assertTrue(nodes.getValue("a").shareCard("Asha", "doctor", now))
        assertTrue(nodes.getValue("b").shareCard("Bala", "nurse", now))
        var t = now
        while (t < now + 8.0) {
            for (id in listOf("a", "b", "c")) nodes.getValue(id).tick(t)
            t += 1.0
            tMs = (t * 1000.0).toLong()
        }
        val aOnC = nodes.getValue("c").peerCards["a"]
        assertNotNull(aOnC)
        assertEquals("Asha", aOnC!!.name)
        assertEquals("doctor", aOnC.occupation)

        val helpNow = t
        val trace = nodes.getValue("a").originateHelp(
            "Need medic, severe bleeding",
            19.0 to 72.0,
            now = helpNow,
        )
        val claimId = trace["claim_id"] as String
        assertTrue(nodes.getValue("a").distressRemainingS(helpNow) > 100.0)

        // Late joiner d connects after the first send; pulses must still deliver.
        medium.link("c", "d")
        val d = PhoneNode("d", SimRadio("d", medium), signer, clock, storage = MemoryStorage())
        t = helpNow
        while (t < helpNow + 12.0) {
            for (id in listOf("a", "b", "c")) nodes.getValue(id).tick(t)
            d.tick(t)
            t += 1.0
            tMs = (t * 1000.0).toLong()
        }
        assertNotNull(d.publicClaims[claimId], "late joiner should get the pulsed distress")

        val acceptAt = t
        val accepted = nodes.getValue("b").acceptClaim(claimId, responderId = "b", responderName = "Bala", now = acceptAt)
        assertNotNull(accepted)
        assertTrue(accepted!!.accepted)
        assertEquals("b", accepted.acceptedBy)
        assertEquals("Bala", accepted.acceptedByName)

        while (t < acceptAt + 10.0) {
            for (id in listOf("a", "b", "c")) nodes.getValue(id).tick(t)
            d.tick(t)
            t += 1.0
            tMs = (t * 1000.0).toLong()
        }
        assertEquals(0.0, nodes.getValue("a").distressRemainingS(t), 1e-9)
        for (node in listOf(nodes.getValue("a"), nodes.getValue("c"), d)) {
            val view = node.publicClaims[claimId]
            assertNotNull(view)
            assertTrue(view!!.accepted)
            assertEquals("b", view.acceptedBy)
            assertEquals("Bala", view.acceptedByName)
        }
    }

    @Test
    fun chatReachesTheAddressedPhoneThroughARelay() {
        val clock = Clock { 1_700_000_000_000L }
        val now = 1_700_000_000.0
        val medium = SimMedium()
        medium.link("a", "b")
        medium.link("b", "c")
        val nodes = listOf("a", "b", "c").associateWith { id ->
            PhoneNode(id, SimRadio(id, medium), signer, clock, storage = MemoryStorage())
        }
        assertTrue(nodes.getValue("a").sendChat("c", "Asha", "on my way", now))
        assertEquals("on my way", nodes.getValue("a").chats.single().text)
        assertEquals("c", nodes.getValue("a").chats.single().peerId)
        var t = now
        while (t < now + 12.0) {
            for (id in listOf("a", "b", "c")) nodes.getValue(id).tick(t)
            t += 1.0
        }
        val got = nodes.getValue("c").chats.single()
        assertEquals("on my way", got.text)
        assertEquals("a", got.fromId)
        assertEquals("a", got.peerId)
        assertEquals("Asha", got.name)
        assertTrue(nodes.getValue("b").chats.isEmpty(), "a relay must carry chat without showing it")
        assertFalse(nodes.getValue("a").sendChat("c", "Asha", "   ", now))
    }
}
