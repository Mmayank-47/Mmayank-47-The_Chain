package org.thechain.identity

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.JsonKeysetWriter
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import com.google.crypto.tink.signature.SignatureKeyTemplates
import org.thechain.core.Signer
import org.thechain.core.sha256Hex
import java.io.ByteArrayOutputStream

/**
 * Hardware-bound device key (Android Keystore / StrongBox on a phone).
 * Private material never leaves [sign].
 */
interface Keystore {
    val keyId: String
    fun sign(data: ByteArray): String
    fun verify(data: ByteArray, sig: String): Boolean
}

/** Alias kept for call sites that still say DeviceKey. */
typealias DeviceKey = Keystore

object TinkBootstrap {
    @Volatile private var ready = false

    fun ensure() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            SignatureConfig.register()
            HybridConfig.register()
            ready = true
        }
    }
}

/**
 * Ed25519 keypair. [publicKeyset] is a Tink JSON public keyset.
 * [keyId] is sha256("kid:" || publicKeyset) truncated to 16 hex chars.
 * Android Keystore wrapping of the private keyset happens in :app; this object is the primitive.
 */
class Ed25519DeviceKey private constructor(private val privateHandle: KeysetHandle) : Keystore {
    val publicKeyset: ByteArray
    override val keyId: String

    init {
        TinkBootstrap.ensure()
        val pub = privateHandle.publicKeysetHandle
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(pub, JsonKeysetWriter.withOutputStream(out))
        publicKeyset = out.toByteArray()
        keyId = sha256Hex("kid:".toByteArray(Charsets.UTF_8) + publicKeyset).substring(0, 16)
    }

    override fun sign(data: ByteArray): String =
        privateHandle.getPrimitive(PublicKeySign::class.java).sign(data).toHex()

    override fun verify(data: ByteArray, sig: String): Boolean = verifyPublic(publicKeyset, data, sig)

    fun privateKeyset(): ByteArray {
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(privateHandle, JsonKeysetWriter.withOutputStream(out))
        return out.toByteArray()
    }

    companion object {
        fun generate(): Ed25519DeviceKey {
            TinkBootstrap.ensure()
            return Ed25519DeviceKey(KeysetHandle.generateNew(SignatureKeyTemplates.ED25519))
        }

        fun fromPrivateKeyset(bytes: ByteArray): Ed25519DeviceKey {
            TinkBootstrap.ensure()
            return Ed25519DeviceKey(CleartextKeysetHandle.read(JsonKeysetReader.withBytes(bytes)))
        }

        fun verifyPublic(publicKeyset: ByteArray, data: ByteArray, sig: String): Boolean {
            if (sig.length < 2 || sig.length % 2 != 0 || sig.any { it !in '0'..'9' && it !in 'a'..'f' }) return false
            return try {
                TinkBootstrap.ensure()
                val handle = CleartextKeysetHandle.read(JsonKeysetReader.withBytes(publicKeyset))
                handle.getPrimitive(PublicKeyVerify::class.java).verify(sig.hexToBytes(), data)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/**
 * node -> one hardware key. A key cannot speak for two nodes, and a node cannot hop keys.
 * [sign] only works for keys enrolled on this phone (peers are verify-only).
 */
class Registry(val rootCheck: (keyId: String, attestation: String?) -> Boolean) : Signer {
    private class Entry(val keyId: String, val publicKeyset: ByteArray, val local: Keystore?)

    private val pub = linkedMapOf<String, Entry>()
    private val byKey = linkedMapOf<String, String>()

    fun enroll(node: String, key: Keystore, attestation: String?, publicKeyset: ByteArray = ByteArray(0)): Boolean {
        if (node.isEmpty() || !rootCheck(key.keyId, attestation)) return false
        if (byKey[key.keyId]?.let { it != node } == true) return false
        val cur = pub[node]
        if (cur != null && cur.keyId != key.keyId) return false
        val material = if (key is Ed25519DeviceKey) key.publicKeyset else publicKeyset
        pub[node] = Entry(key.keyId, material, key)
        byKey[key.keyId] = node
        return true
    }

    /**
     * Verify-only peer enrollment (no local private key). Used after QR / debug-file import.
     * Unenrolled peers stay on the stranger lane until this succeeds.
     */
    fun enrollPublic(node: String, publicKeyset: ByteArray, attestation: String? = "peer"): Boolean {
        if (node.isEmpty() || publicKeyset.isEmpty()) return false
        val keyId = sha256Hex("kid:".toByteArray(Charsets.UTF_8) + publicKeyset).substring(0, 16)
        if (!rootCheck(keyId, attestation)) return false
        if (byKey[keyId]?.let { it != node } == true) return false
        val cur = pub[node]
        if (cur != null && cur.keyId != keyId) return false
        pub[node] = Entry(keyId, publicKeyset.copyOf(), null)
        byKey[keyId] = node
        return true
    }

    fun enrolled(node: String): Boolean = node in pub

    fun publicKeysetOf(node: String): ByteArray? = pub[node]?.publicKeyset?.copyOf()

    fun keyIdOf(node: String): String? = pub[node]?.keyId

    override fun sign(node: String, data: ByteArray): String {
        val key = pub[node]?.local ?: throw NoSuchElementException("no local key for $node")
        return key.sign(data)
    }

    override fun verify(node: String, data: ByteArray, sig: String): Boolean {
        val e = pub[node] ?: return false
        val local = e.local
        return if (local != null) local.verify(data, sig) else Ed25519DeviceKey.verifyPublic(e.publicKeyset, data, sig)
    }
}

class Ed25519Attestor(private val key: Ed25519DeviceKey) {
    fun attest(keyId: String): String = key.sign("attest|$keyId".toByteArray(Charsets.UTF_8))

    fun check(keyId: String, att: String?): Boolean =
        !att.isNullOrEmpty() && key.verify("attest|$keyId".toByteArray(Charsets.UTF_8), att)
}
