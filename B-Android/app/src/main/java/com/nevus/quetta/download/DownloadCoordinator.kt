package com.nevus.quetta.download

import android.app.DownloadManager
import android.content.Context
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
        overrideFileName: String? = null,
    ): DownloadResult {
        val target = DownloadPolicy.validateHttps(url)
            ?: return DownloadResult.Rejected("Hanya unduhan HTTPS valid yang diizinkan")

        val guessed = overrideFileName
            ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
        val fileName = DownloadPolicy.sanitizeFileName(guessed)

        val request = DownloadManager.Request(target).apply {
            userAgent?.takeIf { it.isNotBlank() }
                ?.let { addRequestHeader("User-Agent", it) }

            if (DownloadPolicy.safeCookieTarget(sourcePage, target)) {
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { addRequestHeader("Cookie", it) }
            }

            DownloadPolicy.safeReferrer(sourcePage, target)
                ?.let { addRequestHeader("Referer", it) }

            mimeType?.takeIf { it.isNotBlank() }?.let(::setMimeType)
            setTitle(fileName)
            setDescription("NevusQuetta download")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
        }

        return runCatching {
            val manager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = manager.enqueue(request)
            DownloadResult.Enqueued(id, fileName)
        }.getOrElse {
            DownloadResult.Failed(it.message ?: "DownloadManager gagal")
        }
    }
}
