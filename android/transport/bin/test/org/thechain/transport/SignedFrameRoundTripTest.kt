package org.thechain.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.thechain.core.BloomFilter
import org.thechain.core.CanonicalJson
import org.thechain.core.canonical
import org.thechain.core.parseJsonObject
import org.thechain.cryptofake.HmacSigner

class SignedFrameRoundTripTest {
    @Test
    fun helloFrameSurvivesCanonicalJsonRoundTrip() {
        val signer = HmacSigner(ByteArray(32) { 1 })
        val frame = mutableMapOf<String, Any?>(
            "t" to "hello",
            "id" to "C",
            "cell" to null,
            "bat" to 1.0,
            "bf" to BloomFilter.forCapacity(16).toJson(),
        )
        frame["snd"] = "C"
        frame["seq"] = 3
        val body = frame.filterKeys { it != "sig" }
        val sig = signer.sign("C", canonical(body))
        frame["sig"] = sig
        val signedCanon = String(canonical(body))

        val wire = CanonicalJson.encode(plainJson(frame))
        val decoded = parseJsonObject(wire)!!
        val body2 = decoded.filterKeys { it != "sig" }
        val decodedCanon = String(canonical(body2))

        assertEquals(signedCanon, decodedCanon) {
            "type drift: bat=${decoded["bat"]?.javaClass?.simpleName} " +
                "bf0=${(decoded["bf"] as List<*>)[0]?.javaClass?.simpleName} " +
                "seq=${decoded["seq"]?.javaClass?.simpleName}"
        }
        assertNotNull(IngressGuard(signer = signer).authenticateFrame("C", decoded))
    }
}
