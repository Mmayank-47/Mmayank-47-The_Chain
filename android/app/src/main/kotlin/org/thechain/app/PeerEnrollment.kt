package org.thechain.app

import android.content.Context
import android.util.Log
import org.thechain.identity.Registry
import java.io.File

/**
 * Debug / placeholder peer enrollment.
 *
 * - Exports local Tink public keyset to `filesDir/me.pub` (and `me.nodeId` text).
 * - Loads verify-only peers from `filesDir/peers/<nodeId>.pub`.
 * - Unenrolled Nearby peers stay stranger-lane (no verified handshake mapping).
 * - Full QR enrollment UI is future work (see [ChainUiState.enrollmentNote]).
 */
object PeerEnrollment {
    private const val TAG = "PeerEnrollment"

    fun exportLocal(context: Context, nodeId: String, publicKeyset: ByteArray) {
        val me = File(context.filesDir, "me.pub")
        me.writeBytes(publicKeyset)
        File(context.filesDir, "me.nodeId").writeText(nodeId, Charsets.UTF_8)
        Log.i(TAG, "exported pubkey to ${me.absolutePath} nodeId=$nodeId")
    }

    fun loadPeers(context: Context, registry: Registry): Int {
        val dir = File(context.filesDir, "peers").also { it.mkdirs() }
        var n = 0
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "pub" }.orEmpty()
        for (f in files) {
            val nodeId = f.nameWithoutExtension
            val bytes = f.readBytes()
            if (bytes.isEmpty()) continue
            if (registry.enrollPublic(nodeId, bytes, attestation = "debug-file")) {
                n += 1
                Log.i(TAG, "enrolled peer $nodeId from ${f.name}")
            } else {
                Log.w(TAG, "failed to enroll peer $nodeId")
            }
        }
        // Placeholder QR path: peers/qr_import.pub + peers/qr_import.nodeId
        val qrPub = File(dir, "qr_import.pub")
        val qrId = File(dir, "qr_import.nodeId")
        if (qrPub.exists() && qrId.exists()) {
            val id = qrId.readText(Charsets.UTF_8).trim()
            if (id.isNotEmpty() && registry.enrollPublic(id, qrPub.readBytes(), attestation = "qr-placeholder")) {
                n += 1
                Log.i(TAG, "enrolled QR placeholder peer $id")
            }
        }
        return n
    }
}
