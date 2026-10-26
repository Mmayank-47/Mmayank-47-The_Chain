package org.thechain.core

/** Last-writer-wins map. HLC is a total order because it includes the node id. */
class LwwMap {
    private val entries = LinkedHashMap<String, Pair<Hlc, Any?>>()

    fun set(key: String, value: Any?, hlc: Hlc): Boolean {
        val cur = entries[key]
        if (cur == null || hlc > cur.first) {
            entries[key] = hlc to value
            return true
        }
        return false
    }

    fun delete(key: String, hlc: Hlc): Boolean = set(key, null, hlc)

    fun get(key: String, default: Any? = null): Any? {
        val cur = entries[key] ?: return default
        return if (cur.second == null) default else cur.second
    }

    fun items(): List<Pair<String, Any?>> =
        entries.mapNotNull { (k, hv) -> if (hv.second != null) k to hv.second else null }

    fun merge(other: LwwMap) {
        for ((k, hv) in other.entries) {
            set(k, hv.second, hv.first)
        }
    }

    internal fun raw(): Map<String, Pair<Hlc, Any?>> = entries
}
