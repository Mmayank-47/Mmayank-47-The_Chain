package org.thechain.transport

import java.util.PriorityQueue

/** Bounded priority outbox: distress > dispatch > hazard > chatter, FIFO within a class. */
class PriorityOutbox(val capacity: Int = 256) {
    private data class HeapEntry(val priority: Int, val seq: Int, val itemId: Any) :
        Comparable<HeapEntry> {
        override fun compareTo(other: HeapEntry): Int {
            val byP = priority.compareTo(other.priority)
            if (byP != 0) return byP
            return seq.compareTo(other.seq)
        }
    }

    private val heap = PriorityQueue<HeapEntry>()
    private val items = linkedMapOf<Any, Triple<Int, Int, Any?>>()
    private var seq = 0

    val size: Int get() = items.size

    operator fun contains(itemId: Any): Boolean = itemId in items

    /**
     * False if duplicate, or if full and the new item is no better than the worst queued.
     */
    fun push(itemId: Any, priority: Int, payload: Any? = null): Boolean {
        if (itemId in items) return false
        if (items.size >= capacity) {
            val worst = items.maxWith(compareBy({ it.value.first }, { it.value.second }))
            if (worst.value.first <= priority) return false
            items.remove(worst.key)
        }
        seq += 1
        items[itemId] = Triple(priority, seq, payload)
        heap.add(HeapEntry(priority, seq, itemId))
        return true
    }

    fun push(itemId: Any, priority: org.thechain.core.Priority, payload: Any? = null): Boolean =
        push(itemId, priority.ordinal, payload)

    fun pop(): Any? {
        while (heap.isNotEmpty()) {
            val e = heap.poll()!!
            val cur = items[e.itemId]
            if (cur != null && cur.second == e.seq) {
                items.remove(e.itemId)
                return cur.third
            }
        }
        return null
    }
}
