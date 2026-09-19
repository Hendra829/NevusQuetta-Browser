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

    @Volatile
    private var dek: ByteArray? = null

    fun isUnlocked(): Boolean = dek != null

    fun lockNow() {
        dek?.fill(0)
        dek = null
    }

    fun unlock(context: Context): Boolean {
        val wrapped = prefs(context).getString("wrappedDek", null) ?: return bootstrap(context)
        val nonceB64 = prefs(context).getString("wrapNonce", null) ?: return false
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek(create = false), GCMParameterSpec(GCM_TAG_BITS, b64(nonceB64)))
            cipher.updateAAD("${AAD_PREFIX}dek".toByteArray())
            dek = cipher.doFinal(b64(wrapped))
            true
        }.getOrElse { false }
    }

    fun put(context: Context, slot: Slot, plaintext: String): Boolean {
        val key = dek ?: return false
        val nonce = ByteArray(NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD("$AAD_PREFIX${slot.name}".toByteArray())
        val packed = nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        prefs(context).edit().putString("slot_${slot.name}", b64s(packed)).apply()
        return true
    }

    fun get(context: Context, slot: Slot): String? {
        val key = dek ?: return null
        val packed = b64(prefs(context).getString("slot_${slot.name}", null) ?: return null)
        if (packed.size <= NONCE_BYTES) return null
        val nonce = packed.copyOfRange(0, NONCE_BYTES)
        val body = packed.copyOfRange(NONCE_BYTES, packed.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD("$AAD_PREFIX${slot.name}".toByteArray())
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    fun lockedSlots(context: Context): String {
        val names = JSONArray()
        Slot.values().forEach { slot ->
            if (prefs(context).contains("slot_${slot.name}")) names.put(slot.name)
        }
        return JSONObject()
            .put("version", VERSION)
            .put("status", STATUS)
            .put("unlocked", isUnlocked())
            .put("slots", names)
            .toString()
    }

    private fun bootstrap(context: Context): Boolean {
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek(create = true), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD("${AAD_PREFIX}dek".toByteArray())
        val wrapped = cipher.doFinal(raw)
        prefs(context).edit()
            .putString("wrappedDek", b64s(wrapped))
            .putString("wrapNonce", b64s(nonce))
            .putInt("version", VERSION)
            .apply()
        dek = raw
        return true
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
