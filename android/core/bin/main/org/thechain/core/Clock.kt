package org.thechain.core

/** Wall-clock milliseconds for HLC and persistence. */
fun interface Clock {
    fun nowMs(): Long
}

/** Opaque key/value blob store (files on phone, in-memory in sim). */
interface Storage {
    fun put(key: String, value: ByteArray)
    fun get(key: String): ByteArray?
    fun remove(key: String)
    fun keys(): Set<String>
}

/** In-memory [Storage] for JVM tests and the sim harness. */
class MemoryStorage : Storage {
    private val data = linkedMapOf<String, ByteArray>()

    override fun put(key: String, value: ByteArray) {
        data[key] = value.copyOf()
    }

    override fun get(key: String): ByteArray? = data[key]?.copyOf()

    override fun remove(key: String) {
        data.remove(key)
    }

    override fun keys(): Set<String> = data.keys.toSet()
}
