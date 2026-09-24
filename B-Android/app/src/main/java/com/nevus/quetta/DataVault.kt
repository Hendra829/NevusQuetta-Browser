package com.nevus.quetta

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Brankas data lokal berbasis Android Keystore (format V2).
 *
 * Perbaikan pada revisi ini (lihat AUDIT-REPORT.md A-DV-01/A-DV-02):
 *  - [A-DV-01] `unlock()` tidak lagi memanggil bootstrap ketika `wrappedDek` ada
 *    tetapi `wrapNonce` hilang. Versi sebelumnya memperlakukan kondisi itu sebagai
 *    "belum diinisialisasi" dan menimpa `wrappedDek`/`wrapNonce` dengan DEK baru,
 *    sehingga seluruh isi slot yang lama menjadi tidak dapat didekripsi tanpa
 *    pesan error apa pun (kehilangan data diam-diam).
 *  - [A-DV-01] DEK lama selalu di-wipe sebelum diganti, dan DEK tidak pernah
 *    dipasang ke state bila wrapping ke Keystore gagal.
 *  - [A-DV-02] Seluruh operasi Keystore/Disk I/O (KeyStore.load, generateKey,
 *    doFinal, commit prefs) dijalankan DI LUAR `stateLock`. Sebelumnya `unlock()`
 *    menahan kunci sambil melakukan I/O Keystore, sehingga `put()`/`get()`/
 *    `isUnlocked()` dari thread lain (termasuk main thread) dapat terblokir lama.
 */
object DataVault {
    const val VERSION = 2
    const val STATUS = "P1-lab-keystore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEK_ALIAS = "nevus_vault_kek_v2"
    private const val PREFS = "nevus_vault_v2"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val DEK_BYTES = 32
    private const val AAD_PREFIX = "nevus-vault-v2|"
    private const val KEY_WRAPPED_DEK = "wrappedDek"
    private const val KEY_WRAP_NONCE = "wrapNonce"
    private const val SLOT_PREFIX = "slot_"

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

    /**
     * Membuka brankas. Bila belum pernah diinisialisasi, DEK baru dibuat dan
     * dibungkus (wrap) oleh KEK di Android Keystore.
     *
     * @return true bila brankas siap dipakai.
     */
    fun unlock(context: Context): Boolean {
        val prefs = prefs(context)
        val wrapped = prefs.getString(KEY_WRAPPED_DEK, null)
        val nonceB64 = prefs.getString(KEY_WRAP_NONCE, null)

        // Belum pernah diinisialisasi -> bootstrap.
        if (wrapped == null && nonceB64 == null) return bootstrap(context)

        // Setengah terinisialisasi: JANGAN bootstrap ulang, itu akan menimpa data
        // lama. Laporkan gagal saja sehingga data lama tetap dapat dipulihkan.
        if (wrapped == null || nonceB64 == null) return false

        // I/O Keystore di luar lock.
        val raw = unwrapDek(wrapped, nonceB64) ?: return false

        synchronized(stateLock) {
            dek?.fill(0)
            dek = raw
        }
        return true
    }

    fun put(context: Context, slot: Slot, plaintext: String): Boolean {
        val key = snapshotKey() ?: return false
        return try {
            val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD((AAD_PREFIX + slot.name).toByteArray())
            val packed = nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // commit() bukan apply(): kegagalan penulisan harus terlihat oleh pemanggil.
            prefs(context).edit().putString(SLOT_PREFIX + slot.name, b64s(packed)).commit()
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
                val raw = prefs(context).getString(SLOT_PREFIX + slot.name, null)
                    ?: return@runCatching null
                val packed = b64(raw)
                if (packed.size <= NONCE_BYTES) return@runCatching null
                val nonce = packed.copyOfRange(0, NONCE_BYTES)
                val body = packed.copyOfRange(NONCE_BYTES, packed.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
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
            if (prefs(context).contains(SLOT_PREFIX + slot.name)) names.put(slot.name)
        }
        return JSONObject()
            .put("version", VERSION)
            .put("status", STATUS)
            .put("unlocked", isUnlocked())
            .put("initialized", prefs(context).contains(KEY_WRAPPED_DEK))
            .put("slots", names)
            .toString()
    }

    private fun snapshotKey(): ByteArray? = synchronized(stateLock) { dek?.copyOf() }

    private fun unwrapDek(wrappedB64: String, nonceB64: String): ByteArray? = runCatching {
        val nonce = b64(nonceB64)
        if (nonce.size != NONCE_BYTES) return@runCatching null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            kek(create = false),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD((AAD_PREFIX + "dek").toByteArray())
        val raw = cipher.doFinal(b64(wrappedB64))
        if (raw.size != DEK_BYTES) {
            raw.fill(0)
            null
        } else {
            raw
        }
    }.getOrNull()

    private fun bootstrap(context: Context): Boolean {
        val prefs = prefs(context)
        // Tolak bootstrap bila vault ternyata sudah terinisialisasi (jaga-jaga
        // terhadap balapan dua pemanggil pada waktu bersamaan).
        if (prefs.contains(KEY_WRAPPED_DEK) || prefs.contains(KEY_WRAP_NONCE)) return false

        val raw = ByteArray(DEK_BYTES).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val wrapped = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                kek(create = true),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD((AAD_PREFIX + "dek").toByteArray())
            cipher.doFinal(raw)
        }.getOrElse {
            raw.fill(0)
            return false
        }

        val committed = runCatching {
            prefs.edit()
                .putString(KEY_WRAPPED_DEK, b64s(wrapped))
                .putString(KEY_WRAP_NONCE, b64s(nonce))
                .putInt("version", VERSION)
                .commit()
        }.getOrDefault(false)

        if (!committed) {
            raw.fill(0)
            return false
        }

        synchronized(stateLock) {
            dek?.fill(0)
            dek = raw
        }
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
