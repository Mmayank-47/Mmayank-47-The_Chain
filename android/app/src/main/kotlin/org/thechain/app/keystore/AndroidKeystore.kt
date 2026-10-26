package org.thechain.app.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.thechain.identity.Ed25519DeviceKey
import org.thechain.identity.Keystore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps an [Ed25519DeviceKey] private keyset with an AES/GCM key held in the
 * Android Keystore. If hardware Keystore is unavailable, falls back to a
 * software-held AES key (logged) so the phone can still boot.
 */
class AndroidKeystore private constructor(
    private val deviceKey: Ed25519DeviceKey,
    val hardwareWrapped: Boolean,
) : Keystore {
    override val keyId: String get() = deviceKey.keyId
    val publicKeyset: ByteArray get() = deviceKey.publicKeyset

    override fun sign(data: ByteArray): String = deviceKey.sign(data)

    override fun verify(data: ByteArray, sig: String): Boolean = deviceKey.verify(data, sig)

    companion object {
        private const val TAG = "AndroidKeystore"
        private const val ANDROID_KS = "AndroidKeyStore"
        private const val WRAP_ALIAS = "org.thechain.app.ed25519.wrap"
        private const val BLOB_NAME = "ed25519_keyset.gcm"
        private const val GCM_IV_LEN = 12
        private const val GCM_TAG_BITS = 128

        fun loadOrCreate(context: Context): AndroidKeystore {
            val dir = File(context.filesDir, "keystore").also { it.mkdirs() }
            val blob = File(dir, BLOB_NAME)
            val wrap = obtainWrapKey(dir)
            return if (blob.exists()) {
                val plain = decrypt(wrap.key, blob.readBytes())
                AndroidKeystore(Ed25519DeviceKey.fromPrivateKeyset(plain), wrap.hardware)
            } else {
                val fresh = Ed25519DeviceKey.generate()
                blob.writeBytes(encrypt(wrap.key, fresh.privateKeyset()))
                AndroidKeystore(fresh, wrap.hardware)
            }
        }

        private data class WrapKey(val key: SecretKey, val hardware: Boolean)

        private fun obtainWrapKey(dir: File): WrapKey {
            return try {
                val ks = KeyStore.getInstance(ANDROID_KS).apply { load(null) }
                val existing = ks.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (existing != null) {
                    WrapKey(existing.secretKey, hardware = true)
                } else {
                    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KS)
                    gen.init(
                        KeyGenParameterSpec.Builder(
                            WRAP_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                    WrapKey(gen.generateKey(), hardware = true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Android Keystore unavailable; using software AES wrap", t)
                WrapKey(loadOrCreateSoftwareKey(dir), hardware = false)
            }
        }

        private fun loadOrCreateSoftwareKey(dir: File): SecretKey {
            val soft = File(dir, "soft_wrap.key")
            return if (soft.exists()) {
                javax.crypto.spec.SecretKeySpec(soft.readBytes(), "AES")
            } else {
                val gen = KeyGenerator.getInstance("AES")
                gen.init(256)
                val key = gen.generateKey()
                soft.writeBytes(key.encoded)
                key
            }
        }

        private fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain)
            return iv + ct
        }

        private fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
            require(blob.size > GCM_IV_LEN) { "key blob too short" }
            val iv = blob.copyOfRange(0, GCM_IV_LEN)
            val ct = blob.copyOfRange(GCM_IV_LEN, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ct)
        }
    }
}
