package org.thechain.cryptofake

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.thechain.core.WireClaim
import org.thechain.testing.Vectors

class HmacVectorTest {
    @Test
    fun claimSignatureMatchesPythonDevProvider() {
        val spec = Vectors.load("core").getJSONObject("claim")
        val parsed = linkedMapOf<String, Any?>(
            "capability_tags" to listOf("boat"),
            "confidence" to 0.55,
            "features" to emptyList<String>(),
            "kind" to "need",
            "language" to "en",
            "quantity" to 2,
            "source" to "keyword",
            "urgency" to 0.3,
        )
        val secret = ByteArray(32) { it.toByte() }
        val wc = WireClaim.make("phone-a", 19.076 to 72.8777, 15.0, 10.0, parsed, HmacSigner(secret))
        assertEquals(spec.getString("sig"), wc.sig)
        assertTrue(wc.verify(HmacSigner(secret)))
        assertEquals(spec.getString("canon"), org.thechain.core.CanonicalJson.encode(wc.toJson()))
    }
}
