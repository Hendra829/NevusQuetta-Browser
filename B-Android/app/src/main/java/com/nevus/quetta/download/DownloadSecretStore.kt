package com.nevus.quetta.download

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class DownloadSecret(
    val url: String,
    val userAgent: String?,
    val cookie: String?,
    val sourcePage: String?,
)

class DownloadSecretStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(downloadId: String, secret: DownloadSecret): Boolean = runCatching {
        val nonce = ByteArray(NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key(create = true),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aad(downloadId))
        val payload = JSONObject()
            .put("url", secret.url)
            .put("userAgent", secret.userAgent)
            .put("cookie", secret.cookie)
            .put("sourcePage", secret.sourcePage)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val encrypted = cipher.doFinal(payload)
        val packed = nonce + encrypted
        prefs.edit()
            .putString(downloadId, Base64.encodeToString(packed, Base64.NO_WRAP))
            .commit()
    }.getOrDefault(false)

    fun get(downloadId: String): DownloadSecret? = runCatching {
        val encoded = prefs.getString(downloadId, null) ?: return@runCatching null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        if (packed.size <= NONCE_BYTES) return@runCatching null
        val nonce = packed.copyOfRange(0, NONCE_BYTES)
        val body = packed.copyOfRange(NONCE_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(create = false),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(aad(downloadId))
        val json = JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))
        DownloadSecret(
            url = json.getString("url"),
            userAgent = json.optString("userAgent").takeIf(String::isNotBlank),
            cookie = json.optString("cookie").takeIf(String::isNotBlank),
            sourcePage = json.optString("sourcePage").takeIf(String::isNotBlank),
        )
    }.getOrNull()

    fun remove(downloadId: String) {
        prefs.edit().remove(downloadId).apply()
    }

    fun contains(downloadId: String): Boolean = prefs.contains(downloadId)

    private fun aad(downloadId: String): ByteArray =
        ("nevus-download-secret-v1|" + downloadId).toByteArray(Charsets.UTF_8)

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) {
            return (store.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        if (!create) error("Download secret key missing")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val PREFS = "nevus_download_secrets_v1"
        const val KEY_ALIAS = "nevus_download_secret_kek_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
