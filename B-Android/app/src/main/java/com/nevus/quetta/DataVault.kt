package com.nevus.quetta

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DataVault {
    const val VERSION = 2
    const val STATUS = "P1-lab-keystore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEK_ALIAS = "nevus_vault_kek_v2"
    private const val PREFS = "nevus_vault_v2"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val AAD_PREFIX = "nevus-vault-v2|"

    enum class Slot { HISTORY, FAVORITES, DOWNLOADS, PLAYLIST }

    private val stateLock = Any()

    @Volatile
    private var dek: ByteArray? = null

    fun isUnlocked(): Boolean = synchronized(stateLock) { dek != null }

    fun lockNow() {
        synchronized(stateLock) {
            dek?.fill(0)
            dek = null
        }
    }

    fun unlock(context: Context): Boolean = synchronized(stateLock) {
        val wrapped = prefs(context).getString("wrappedDek", null) ?: return bootstrapLocked(context)
        val nonceB64 = prefs(context).getString("wrapNonce", null) ?: return false
        runCatching {
            val nonce = b64(nonceB64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek(create = false), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD((AAD_PREFIX + "dek").toByteArray())
            val raw = cipher.doFinal(b64(wrapped))
            require(raw.size == 32)
            dek?.fill(0)
            dek = raw
            true
        }.getOrElse { false }
    }

    fun put(context: Context, slot: Slot, plaintext: String): Boolean {
        val key = snapshotKey() ?: return false
        return try {
            val nonce = ByteArray(NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD((AAD_PREFIX + slot.name).toByteArray())
            val packed = nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            prefs(context).edit().putString("slot_" + slot.name, b64s(packed)).apply()
            true
        } catch (_: Exception) {
            false
        } finally {
            key.fill(0)
        }
    }

    fun get(context: Context, slot: Slot): String? {
        val key = snapshotKey() ?: return null
        return try {
            runCatching {
                val raw = prefs(context).getString("slot_" + slot.name, null) ?: return@runCatching null
                val packed = b64(raw)
                if (packed.size <= NONCE_BYTES) return@runCatching null
                val nonce = packed.copyOfRange(0, NONCE_BYTES)
                val body = packed.copyOfRange(NONCE_BYTES, packed.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                cipher.updateAAD((AAD_PREFIX + slot.name).toByteArray())
                String(cipher.doFinal(body), Charsets.UTF_8)
            }.getOrNull()
        } finally {
            key.fill(0)
        }
    }

    fun lockedSlots(context: Context): String {
        val names = JSONArray()
        Slot.values().forEach { slot ->
            if (prefs(context).contains("slot_" + slot.name)) names.put(slot.name)
        }
        return JSONObject()
            .put("version", VERSION)
            .put("status", STATUS)
            .put("unlocked", isUnlocked())
            .put("slots", names)
            .toString()
    }

    private fun snapshotKey(): ByteArray? = synchronized(stateLock) { dek?.copyOf() }

    private fun bootstrapLocked(context: Context): Boolean {
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, kek(create = true), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD((AAD_PREFIX + "dek").toByteArray())
            val wrapped = cipher.doFinal(raw)
            prefs(context).edit()
                .putString("wrappedDek", b64s(wrapped))
                .putString("wrapNonce", b64s(nonce))
                .putInt("version", VERSION)
                .apply()
            dek?.fill(0)
            dek = raw
            true
        }.getOrElse {
            raw.fill(0)
            false
        }
    }

    private fun kek(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(KEK_ALIAS)) {
            return (store.getEntry(KEK_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        if (!create) error("KEK missing")
        val spec = KeyGenParameterSpec.Builder(
            KEK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun b64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
    private fun b64s(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
}
