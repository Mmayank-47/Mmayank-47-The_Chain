package org.thechain.core

import java.math.BigInteger
import kotlin.math.ln
import kotlin.math.max

class BloomFilter(
    val mBits: Int = 256,
    val k: Int = 4,
    bits: BigInteger = BigInteger.ZERO,
) {
    var bits: BigInteger = bits
        private set

    fun add(item: String) {
        for (i in indices(item)) {
            bits = bits.setBit(i)
        }
    }

    operator fun contains(item: String): Boolean = indices(item).all { bits.testBit(it) }

    fun toJson(): List<Any> = listOf(mBits, k, bits.toString(16))

    private fun indices(item: String): List<Int> {
        val digest = sha256(item.toByteArray(Charsets.UTF_8))
        return (0 until k).map { i ->
            val start = 4 * i
            val end = minOf(start + 4, digest.size)
            var v = 0L
            for (j in start until end) {
                v = (v shl 8) or (digest[j].toLong() and 0xFF)
            }
            (v % mBits).toInt()
        }
    }

    companion object {
        fun forCapacity(n: Int, fp: Double = 0.01): BloomFilter {
            val capped = max(n, 16)
            val mRaw = -capped * ln(fp) / (ln(2.0) * ln(2.0))
            val m = max(64, mRaw.toInt())
            val k = max(1, pyRound(m.toDouble() / capped * ln(2.0)))
            return BloomFilter(m, k)
        }

        fun fromJson(x: List<*>): BloomFilter =
            BloomFilter((x[0] as Number).toInt(), (x[1] as Number).toInt(), BigInteger(x[2].toString(), 16))

        /** Python 3 `round`: half to even. */
        fun pyRound(x: Double): Int {
            val floor = kotlin.math.floor(x)
            val diff = x - floor
            val base = floor.toInt()
            return when {
                diff > 0.5 -> base + 1
                diff < 0.5 -> base
                else -> if (base % 2 == 0) base else base + 1
            }
        }
    }
}
