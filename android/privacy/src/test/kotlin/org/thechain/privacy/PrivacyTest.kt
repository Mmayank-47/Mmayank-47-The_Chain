package org.thechain.privacy

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.CanonicalJson
import org.thechain.core.Hlc
import org.thechain.core.Sealer
import org.thechain.core.cellOf
import org.thechain.cryptofake.HmacProvider
import org.thechain.cryptofake.NONCE_LEN
import org.thechain.cryptofake.openHmac
import org.thechain.cryptofake.sealHmac
import org.thechain.testing.Vectors
import java.util.Random

class PrivacyTest {
    @Test
    fun publicJsonMatchesVectorAndHidesExactCoordinates() {
        val spec = Vectors.load("privacy")
        val case = vectorCase()
        val publicJson = CanonicalJson.encode(case.publicPayload())
        assertEquals(spec.getString("public_blob"), publicJson)
        assertEquals(spec.getBoolean("exact_in_public"), publicJson.contains("12345.6"))
        assertFalse(publicJson.contains("12345.6"))
        val pub = spec.getJSONObject("public")
        assertEquals(pub.getString("c"), case.publicPayload()["c"])
        assertEquals(pub.getString("cell"), case.cell)
        assertEquals(pub.getString("cat"), case.publicPayload()["cat"])
        assertEquals(pub.getString("need"), case.publicPayload()["need"])
        assertEquals(pub.getString("tag"), case.publicPayload()["tag"])
        assertEquals(spec.getBoolean("private_has_pos"), case.privatePayload(0.0).containsKey("pos"))
        assertFalse(case.publicPayload().containsKey("pos"))
        val wire = publicMessage(case, "pid", Hlc(1, 0, "x"), 0.0).wireBytes().toString(Charsets.UTF_8)
        assertFalse(wire.contains("12345.6"))
        assertFalse(wire.contains("roof of ward 3"))
    }

    @Test
    fun sealerRoundTripsThroughCryptoFake() {
        val provider = HmacProvider()
        val secret = ByteArray(32) { (it + 3).toByte() }
        val nonce = ByteArray(NONCE_LEN) { (it + 1).toByte() }
        var seals = 0
        val sealer = Sealer { pub, sender, payload, n, sig ->
            seals += 1
            sealHmac(provider, pub, sender, payload, n ?: nonce, sig)
        }
        val case = vectorCase()
        val discloser = Discloser(case, mapOf("r1" to secret), sealer)
        assertThrows(DisclosureDenied::class.java) { discloser.release("r1", 0.0) }
        assertFalse(discloser.accept("stranger"))
        assertTrue(discloser.accept("r1"))
        val blob = discloser.release("r1", 0.0, sign = { bytes -> "sig:${bytes.size}" }, nonce = nonce)
        assertEquals(PACKET, blob.size)
        assertEquals(512, blob.size)
        val opened = openHmac(provider, secret, blob)
        assertNotNull(opened)
        assertEquals(case.id, opened!!.sender)
        assertTrue(opened.sig!!.startsWith("sig:"))
        val payload = opened.payload as JSONObject
        assertEquals("case-1", payload.getString("c"))
        assertEquals("roof of ward 3", payload.getString("loc"))
        val pos = payload.getJSONArray("pos")
        assertEquals(12345.6, pos.getDouble(0), 1e-9)
        assertEquals(67890.1, pos.getDouble(1), 1e-9)
        assertEquals("not_breathing", payload.getJSONArray("feat").getString(0))
        assertNull(openHmac(provider, ByteArray(32) { 9 }, blob))
        val tampered = blob.copyOf()
        tampered[40] = (tampered[40].toInt() xor 1).toByte()
        assertNull(openHmac(provider, secret, tampered))
        assertArrayEquals(blob, discloser.release("r1", 50.0, nonce = ByteArray(NONCE_LEN) { 4 }))
        assertEquals(1, seals)
        val wire = sealedMessage(blob, "pidx", case.cell, Hlc(1, 0, "x"), 0.0)
        assertEquals(blob.toHex(), wire.payload["b"])
        assertEquals(case.cell, wire.destCell)
    }

    @Test
    fun pseudonymsMatchPythonHmac() {
        val chain = PseudonymChain("s".toByteArray(), epochS = 100.0)
        assertEquals("489103eb8ff9", chain.pid(5.0))
        assertEquals(chain.pid(5.0), chain.pid(99.0))
        assertEquals("82784b517352", chain.pid(105.0))
        assertEquals("86:87:de:12:51:2b", chain.bleAddr(5.0))
        assertEquals("ca:26:04:2f:b5:75", chain.bleAddr(105.0))
        assertEquals("86:87:de:12:51:2b", PseudonymChain("s".toByteArray()).bleAddr(0.0))
        val first = chain.bleAddr(0.0).substringBefore(":").toInt(16)
        assertEquals(0x02, first and 0x03)
        assertTrue(chain.owns(chain.pid(5.0), 150.0))
        assertFalse(chain.owns(chain.pid(5.0), 450.0))
        assertFalse(PseudonymChain("other".toByteArray(), 100.0).owns(chain.pid(5.0), 5.0))
        assertEquals("0b80bf438508", PseudonymChain("seed".toByteArray()).pid(0.0))
        assertThrows(IllegalArgumentException::class.java) { PseudonymChain(byteArrayOf(1), epochS = 0.0) }
    }

    @Test
    fun publicViewGeneralisesAndCapsReleases() {
        val sample = Case(
            "c1",
            1234.5 to 987.6,
            30.0,
            tags = listOf("insulin"),
            features = listOf("medication_dependent"),
            locationText = "Shivaji Nagar",
            quantity = 2,
            chain = PseudonymChain("seed".toByteArray()),
        )
        val pub = sample.publicPayload()
        assertEquals(cellOf(1234.5 to 987.6), pub["cell"])
        assertEquals("medical", pub["cat"])
        assertEquals("medicine", pub["tag"])
        assertNull(pub["need"])
        val wire = publicMessage(sample, sample.chain!!.pid(0.0), Hlc(1, 0, "x"), 0.0).wireBytes().toString(Charsets.UTF_8)
        for (secret in listOf("1234", "987", "Shivaji", "medication", "insulin", "seed")) {
            assertFalse(wire.contains(secret), secret)
        }
        assertEquals("medic", Case("c", 0.0 to 0.0, 1.0, features = listOf("not_breathing")).publicPayload()["need"])
        assertEquals("rescue", Case("c", 0.0 to 0.0, 1.0, tags = listOf("boat"), features = listOf("trapped")).publicPayload()["cat"])
        assertEquals("other", Case("c", 0.0 to 0.0, 1.0, tags = listOf("xyz")).publicPayload()["cat"])

        val secret = ByteArray(32) { 1 }
        val sealer = Sealer { _, _, _, _, _ -> ByteArray(PACKET) }
        val keys = (0 until 5).associate { "r$it" to secret }
        val discloser = Discloser(sample, keys, sealer, maxResponders = 2)
        assertEquals(listOf(true, true, false, false), (0 until 4).map { discloser.accept("r$it") })
        assertTrue(discloser.accept("r0"))
        assertThrows(DisclosureDenied::class.java) { discloser.release("r3", 0.0) }
    }

    @Test
    fun coverBudgetSubtractsRealTraffic() {
        val cover = CoverTraffic(rateBps = PACKET.toDouble(), rng = Random(1))
        assertEquals(0, cover.step(0.0).size)
        val out = cover.step(2.0)
        assertEquals(2, out.size)
        assertTrue(out.all { it.size == PACKET })
        val busy = CoverTraffic(rateBps = PACKET.toDouble(), rng = Random(1))
        busy.step(0.0)
        assertEquals(0, busy.step(2.0, realSentBytes = 2 * PACKET).size)
        val capped = CoverTraffic(rateBps = 1e6, maxPerStep = 3, rng = Random(2))
        capped.step(0.0)
        assertEquals(3, capped.step(100.0).size)
    }

    private fun vectorCase() = Case(
        "case-1",
        12345.6 to 67890.1,
        18.0,
        tags = listOf("insulin", "boat"),
        features = listOf("not_breathing"),
        locationText = "roof of ward 3",
        quantity = 2,
    )
}
