package com.nevus.quetta.download

import android.net.Uri
import android.webkit.URLUtil
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadProbe(
    val finalUrl: Uri,
    val mimeType: String?,
    val contentLength: Long,
    val supportsResume: Boolean,
    val suggestedFileName: String,
    val etag: String?,
    val lastModified: String?,
)

sealed interface DownloadProbeResult {
    data class Accepted(val probe: DownloadProbe) : DownloadProbeResult
    data class Rejected(val reason: String) : DownloadProbeResult
}

class DownloadPreflightClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxRedirects: Int = 5,
    private val connectTimeoutMs: Int = 12_000,
    private val readTimeoutMs: Int = 12_000,
) {
    suspend fun probe(
        rawUrl: String,
        userAgent: String?,
        sourcePage: String?,
        sameOriginCookie: String?,
    ): DownloadProbeResult = withContext(ioDispatcher) {
        var current = DownloadPolicy.validateHttps(rawUrl)
            ?: return@withContext DownloadProbeResult.Rejected("URL unduhan harus HTTPS valid")

        repeat(maxRedirects + 1) { redirectCount ->
            if (!DownloadPolicy.resolvesToPublicAddress(current)) {
                return@withContext DownloadProbeResult.Rejected(
                    "Tujuan unduhan bukan alamat jaringan publik yang diizinkan",
                )
            }

            val connection = open(current)
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Range", "bytes=0-0")
                userAgent?.takeIf(String::isNotBlank)
                    ?.let { connection.setRequestProperty("User-Agent", it) }
                DownloadPolicy.safeReferrer(sourcePage, current)
                    ?.let { connection.setRequestProperty("Referer", it) }
                if (sameOriginCookie != null &&
                    DownloadPolicy.safeCookieTarget(sourcePage, current)
                ) {
                    connection.setRequestProperty("Cookie", sameOriginCookie)
                }

                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    if (redirectCount >= maxRedirects) {
                        return@withContext DownloadProbeResult.Rejected("Redirect unduhan terlalu banyak")
                    }
                    val location = connection.getHeaderField("Location")
                        ?: return@withContext DownloadProbeResult.Rejected("Redirect tanpa Location")
                    current = DownloadPolicy.resolveRedirect(current, location)
                        ?: return@withContext DownloadProbeResult.Rejected(
                            "Redirect keluar dari HTTPS yang diizinkan",
                        )
                    return@repeat
                }

                if (code !in 200..299) {
                    return@withContext DownloadProbeResult.Rejected(
                        "Server menolak unduhan (HTTP $code)",
                    )
                }

                val mime = connection.contentType?.substringBefore(';')?.trim()
                val disposition = connection.getHeaderField("Content-Disposition")
                val contentRange = connection.getHeaderField("Content-Range")
                val totalFromRange = DownloadPolicy.parseContentRangeTotal(contentRange)
                val length = totalFromRange
                    ?: connection.getHeaderFieldLong("Content-Length", -1L)
                val supportsRange =
                    code == HttpURLConnection.HTTP_PARTIAL ||
                        connection.getHeaderField("Accept-Ranges")
                            ?.equals("bytes", ignoreCase = true) == true
                val guessed = URLUtil.guessFileName(
                    current.toString(),
                    disposition,
                    mime,
                )

                return@withContext DownloadProbeResult.Accepted(
                    DownloadProbe(
                        finalUrl = current,
                        mimeType = mime,
                        contentLength = length,
                        supportsResume = supportsRange,
                        suggestedFileName = DownloadPolicy.sanitizeFileName(guessed),
                        etag = connection.getHeaderField("ETag")?.take(256),
                        lastModified = connection.getHeaderField("Last-Modified")?.take(256),
                    ),
                )
            } finally {
                connection.disconnect()
            }
        }

        DownloadProbeResult.Rejected("Preflight unduhan gagal")
    }

    private fun open(uri: Uri): HttpsURLConnection {
        val connection = URL(uri.toString()).openConnection()
        require(connection is HttpsURLConnection)
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        return connection
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
