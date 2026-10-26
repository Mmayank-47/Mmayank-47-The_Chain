package org.thechain.app.storage

import android.content.Context
import org.thechain.core.Storage
import java.io.File

/** Atomic-ish file snapshots under app filesDir/store. */
class FileStorage(context: Context) : Storage {
    private val root = File(context.filesDir, "store").also { it.mkdirs() }

    override fun put(key: String, value: ByteArray) {
        val target = fileFor(key)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(value)
        if (!tmp.renameTo(target)) {
            target.writeBytes(value)
            tmp.delete()
        }
    }

    override fun get(key: String): ByteArray? {
        val f = fileFor(key)
        return if (f.exists()) f.readBytes() else null
    }

    override fun remove(key: String) {
        fileFor(key).delete()
    }

    override fun keys(): Set<String> =
        root.listFiles()?.mapNotNull { it.nameWithoutExtension.takeIf { n -> it.extension == "bin" } }
            ?.toSet()
            ?: emptySet()

    private fun fileFor(key: String): File {
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(root, "$safe.bin")
    }
}
