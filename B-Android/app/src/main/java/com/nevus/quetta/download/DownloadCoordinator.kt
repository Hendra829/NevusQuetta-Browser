package com.nevus.quetta.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil

sealed interface DownloadResult {
    data class Enqueued(val id: Long, val fileName: String) : DownloadResult
    data class Rejected(val reason: String) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
}

class DownloadCoordinator(
    private val context: Context,
) {
    fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        sourcePage: String?,
    ): DownloadResult {
        val target = runCatching { Uri.parse(url) }.getOrNull()
            ?: return DownloadResult.Rejected("URL unduhan tidak valid")
        if (!target.scheme.equals("https", ignoreCase = true) || target.host.isNullOrBlank()) {
            return DownloadResult.Rejected("Hanya unduhan HTTPS yang diizinkan")
        }

        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val fileName = sanitizeFileName(guessed).ifBlank { "download" }
        val request = DownloadManager.Request(target).apply {
            userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
            CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { addRequestHeader("Cookie", it) }
            safeReferrer(sourcePage, target)?.let { addRequestHeader("Referer", it) }
            mimeType?.takeIf { it.isNotBlank() }?.let(::setMimeType)
            setTitle(fileName)
            setDescription("NevusQuetta download")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        return runCatching {
            val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            DownloadResult.Enqueued(id, fileName)
        }.getOrElse {
            DownloadResult.Failed(it.message ?: "DownloadManager gagal")
        }
    }

    private fun safeReferrer(sourcePage: String?, target: Uri): String? {
        val source = sourcePage?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        if (!source.scheme.equals("https", ignoreCase = true) || source.host.isNullOrBlank()) return null

        val sameOrigin = source.host.equals(target.host, ignoreCase = true) &&
            effectivePort(source) == effectivePort(target)
        if (sameOrigin) return source.toString()

        val authority = if (source.port == -1 || source.port == 443) {
            source.host
        } else {
            source.host + ":" + source.port
        }
        return "https://" + authority + "/"
    }

    private fun effectivePort(uri: Uri): Int = if (uri.port == -1) 443 else uri.port

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\/:*?\"<>|\u0000-\u001F]"), "_").take(180)
}
