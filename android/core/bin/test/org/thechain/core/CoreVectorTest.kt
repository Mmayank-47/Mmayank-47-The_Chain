package org.thechain.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.thechain.testing.Vectors
import org.thechain.testing.untag

class CoreVectorTest {
    private val root = Vectors.load("core")

    @Test
    fun canonicalMatchesPython() {
        val cases = root.getJSONArray("canonical")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            val value = untag(row.getJSONObject("tagged"))
            assertEquals(row.getString("canon"), CanonicalJson.encode(value), row.getString("name"))
        }
    }

    @Test
    fun hlcMatchesPython() {
        var wall = 0L
        val clock = HlClock("n1", { wall })
        val cases = root.getJSONArray("hlc")
        for (i in 0 until cases.length()) {
            val row = cases.getJSONObject(i)
            wall = row.getLong("wall")
            val remote = row.optJSONArray("remote")
            if (row.getString("op") == "now") {
                assertEquals(row.getJSONArray("out").hlcList(), clock.now().toJson())
            } else if (row.has("err")) {
                val hlc = hlcOf(remote!!)
                assertThrows(ClockDrift::class.java) { clock.update(hlc) }
            } else {
                assertEquals(row.getJSONArray("out").hlcList(), clock.update(hlcOf(remote!!)).toJson())
            }
        }
    }

    @Test
    fun lwwMatchesPython() {
        val a = LwwMap()
        val b = LwwMap()
        a.set("k", "old", Hlc(1, 0, "a"))
        a.set("k", "new", Hlc(2, 0, "a"))
        a.set("k", "lose", Hlc(2, 0, "a"))
        b.set("k", "tie-node", Hlc(2, 0, "b"))
        b.set("gone", "x", Hlc(1, 0, "b"))
        b.delete("gone", Hlc(3, 0, "b"))
        b.set("onlyb", 7, Hlc(1, 1, "b"))
        a.merge(b)
        val expected = root.getJSONObject("lww").getJSONArray("items")
        val got = a.items().sortedBy { it.first }
        assertEquals(expected.length(), got.size)
        for (i in 0 until expected.length()) {
            val pair = expected.getJSONArray(i)
            assertEquals(pair.getString(0), got[i].first)
            val want: Any = if (pair.get(1) is String) pair.getString(1) else pair.getInt(1)
            assertEquals(want, got[i].second)
        }
    }

    @Test
    fun bloomMatchesPython() {
        val spec = root.getJSONObject("bloom")
        val bf = BloomFilter.forCapacity(100, 0.01)
        assertEquals(spec.getInt("m"), bf.mBits)
        assertEquals(spec.getInt("k"), bf.k)
        for (i in 0 until 60) bf.add("id-$i")
        assertEquals(spec.getString("bits"), bf.bits.toString(16))
        val members = spec.getJSONObject("members")
        for (key in members.keyList()) {
            assertEquals(members.getBoolean(key), key in bf, key)
        }
        val fresh = BloomFilter()
        val defaults = spec.getJSONObject("default_members")
        for (key in defaults.keyList()) {
            if (defaults.getBoolean(key)) fresh.add(key)
        }
        assertEquals(spec.getString("default_bits"), fresh.bits.toString(16))
    }

    @Test
    fun tokenBucketMatchesPython() {
        val tb = TokenBucket(1.0, 2.0)
        val rows = root.getJSONArray("bucket")
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            assertEquals(row.getBoolean("ok"), tb.allow(row.getDouble("now")))
            assertEquals(row.getDouble("tokens"), tb.tokens, 1e-9)
        }
    }

    @Test
    fun claimAndMessageIdsMatchPython() {
        val spec = root.getJSONObject("claim")
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
        val wc = WireClaim.make("phone-a", 19.076 to 72.8777, 15.0, 10.0, parsed, object : Signer {
            override fun sign(node: String, data: ByteArray) = "00"
            override fun verify(node: String, data: ByteArray, sig: String) = false
        })
        assertEquals(spec.getString("id"), wc.id)
        assertEquals(spec.getString("cell"), cellOf(wc.pos))
        assertEquals(spec.getString("need"), needOf(parsed))
        val msg = Message(
            "m9.case",
            Priority.DISTRESS,
            "phone-a",
            mapOf("c" to wc.id),
            Hlc(10, 0, "phone-a"),
            destCell = "0:0",
            ttlS = 3600.0,
            created = 10.0,
        )
        assertEquals(spec.getString("msg_id"), msg.msgId)
        assertEquals(spec.getString("wire"), CanonicalJson.encode(msg.toWire()))
        assertEquals(spec.getInt("event_priority_claim"), eventPriority("claim").wire)
        assertEquals(spec.getInt("event_priority_other"), eventPriority("beat").wire)
        assertEquals(spec.getInt("event_priority_hostile"), eventPriority(null).wire)
    }

    @Test
    fun negativeZeroFloat() {
        assertEquals("-0.0", CanonicalJson.pythonFloat(-0.0))
        assertEquals("0.0", CanonicalJson.pythonFloat(0.0))
    }
}

private fun hlcOf(arr: org.json.JSONArray) = Hlc(arr.getLong(0), arr.getInt(1), arr.getString(2))

private fun JSONArray.hlcList(): List<Any> = listOf(getLong(0), getInt(1), getString(2))

private fun JSONObject.keyList(): List<String> {
    val out = mutableListOf<String>()
    val it = keys()
    while (it.hasNext()) out.add(it.next())
    return out
}
