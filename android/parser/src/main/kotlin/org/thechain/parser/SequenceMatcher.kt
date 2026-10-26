package org.thechain.parser

/**
 * `difflib.SequenceMatcher(None, a, b).ratio()` with the default autojunk heuristic.
 * `isjunk` is None, which is the call the ontology uses.
 */
internal object SequenceMatcher {
    fun ratio(a: String, b: String): Double {
        val ac = codePoints(a)
        val bc = codePoints(b)
        val length = ac.size + bc.size
        if (length == 0) return 1.0
        var matches = 0
        for (block in matchingBlocks(ac, bc)) matches += block[2]
        return 2.0 * matches / length
    }

    private fun codePoints(s: String): IntArray {
        val n = s.codePointCount(0, s.length)
        val out = IntArray(n)
        var i = 0
        var k = 0
        while (i < s.length) {
            out[k++] = s.codePointAt(i)
            i += Character.charCount(out[k - 1])
        }
        return out
    }

    private fun matchingBlocks(a: IntArray, b: IntArray): List<IntArray> {
        val index = HashMap<Int, ArrayList<Int>>()
        for (i in b.indices) index.getOrPut(b[i]) { ArrayList() }.add(i)
        if (b.size >= 200) {
            val ntest = b.size / 100 + 1
            val popular = ArrayList<Int>()
            for ((elt, idxs) in index) if (idxs.size > ntest) popular.add(elt)
            for (elt in popular) index.remove(elt)
        }
        val b2j = HashMap<Int, IntArray>(index.size)
        for ((elt, idxs) in index) b2j[elt] = idxs.toIntArray()

        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(0, a.size, 0, b.size))
        val blocks = ArrayList<IntArray>()
        while (queue.isNotEmpty()) {
            val range = queue.removeLast()
            val alo = range[0]
            val ahi = range[1]
            val blo = range[2]
            val bhi = range[3]
            val match = findLongest(a, b, b2j, alo, ahi, blo, bhi)
            val i = match[0]
            val j = match[1]
            val k = match[2]
            if (k != 0) {
                blocks.add(match)
                if (alo < i && blo < j) queue.add(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.add(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        blocks.sortWith(compareBy({ it[0] }, { it[1] }, { it[2] }))

        var i1 = 0
        var j1 = 0
        var k1 = 0
        val collapsed = ArrayList<IntArray>()
        for (block in blocks) {
            val i2 = block[0]
            val j2 = block[1]
            val k2 = block[2]
            if (i1 + k1 == i2 && j1 + k1 == j2) {
                k1 += k2
            } else {
                if (k1 != 0) collapsed.add(intArrayOf(i1, j1, k1))
                i1 = i2
                j1 = j2
                k1 = k2
            }
        }
        if (k1 != 0) collapsed.add(intArrayOf(i1, j1, k1))
        collapsed.add(intArrayOf(a.size, b.size, 0))
        return collapsed
    }

    private fun findLongest(
        a: IntArray,
        b: IntArray,
        b2j: Map<Int, IntArray>,
        alo: Int,
        ahi: Int,
        blo: Int,
        bhi: Int,
    ): IntArray {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            val js = b2j[a[i]]
            if (js != null) {
                for (j in js) {
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    newj2len[j] = k
                    if (k > bestsize) {
                        besti = i - k + 1
                        bestj = j - k + 1
                        bestsize = k
                    }
                }
            }
            j2len = newj2len
        }
        while (besti > alo && bestj > blo && a[besti - 1] == b[bestj - 1]) {
            besti--
            bestj--
            bestsize++
        }
        while (besti + bestsize < ahi && bestj + bestsize < bhi &&
            a[besti + bestsize] == b[bestj + bestsize]
        ) {
            bestsize++
        }
        return intArrayOf(besti, bestj, bestsize)
    }
}
