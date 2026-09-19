package com.nevus.quetta.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.DownloadStatuses
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class HlsVodDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = DownloadRepository(BrowserDatabase.get(appContext))
    private val parser = HlsVodParser()
    private val secretStore = DownloadSecretStore(appContext)

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?: return Result.failure()
        val secret = secretStore.get(downloadId)
            ?: return failMissingSecret(downloadId)

        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.RUNNING,
            bytesDownloaded = 0,
            totalBytes = -1,
            localUri = null,
            errorCode = null,
        )

        val tempDir = File(applicationContext.cacheDir, "nevus_hls")
        val tempFile = File(tempDir, downloadId + ".part")

        return try {
            withContext(Dispatchers.IO) {
                tempDir.mkdirs()
                tempFile.delete()

                val media = resolveMediaPlaylist(downloadId, secret)
                if (media.initSegment != null && media.hasDiscontinuity) {
                    error("DISCONTINUITY_FMP4_UNSUPPORTED")
                }

                val parts = buildList {
                    media.initSegment?.let { add(HlsSegment(it, false)) }
                    addAll(media.segments)
                }

                var downloaded = 0L
                var firstContentType: String? = null
                FileOutputStream(tempFile).use { output ->
                    parts.forEachIndexed { index, part ->
                        if (isStopped) throw CancellationException("HLS worker stopped")
                        val copy = appendHttps(
                            downloadId = downloadId,
                            url = part.url,
                            secret = secret,
                            output = output,
                            alreadyDownloaded = downloaded,
                        )
                        downloaded += copy.bytes
                        if (firstContentType == null && copy.contentType != null) {
                            firstContentType = copy.contentType
                        }
                        repository.updateProgress(
                            downloadId = downloadId,
                            status = DownloadStatuses.RUNNING,
                            bytesDownloaded = downloaded,
                            totalBytes = -1,
                            localUri = null,
                            errorCode = null,
                        )
                        setProgress(
                            workDataOf(
                                "segmentsDone" to (index + 1),
                                "segmentsTotal" to parts.size,
                                "bytesDownloaded" to downloaded,
                                "gapSegmentsSkipped" to media.gapCount,
                                "hasDiscontinuity" to media.hasDiscontinuity,
                            ),
                        )
                    }
                }

                val output = outputFormat(media, firstContentType)
                val fileName = DownloadPolicy.sanitizeFileName(
                    baseName(secret.url) + "-hls." + output.extension,
                )
                val digest = sha256(tempFile)
                val localUri = publishVerified(tempFile, fileName, output.mimeType)

                repository.updateDigest(downloadId, digest)
                repository.updateProgress(
                    downloadId = downloadId,
                    status = DownloadStatuses.COMPLETED,
                    bytesDownloaded = downloaded,
                    totalBytes = downloaded,
                    localUri = localUri,
                    errorCode = null,
                )
                secretStore.remove(downloadId)
            }
            Result.success()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                val item = repository.get(downloadId)
                repository.updateProgress(
                    downloadId = downloadId,
                    status = DownloadStatuses.CANCELED,
                    bytesDownloaded = item?.bytesDownloaded ?: 0,
                    totalBytes = item?.totalBytes ?: -1,
                    localUri = item?.localUri,
                    errorCode = null,
                )
            }
            throw cancel
        } catch (error: Throwable) {
            val item = repository.get(downloadId)
            repository.updateProgress(
                downloadId = downloadId,
                status = DownloadStatuses.FAILED,
                bytesDownloaded = item?.bytesDownloaded ?: 0,
                totalBytes = item?.totalBytes ?: -1,
                localUri = null,
                errorCode = sanitizeError(error),
            )
            Result.failure()
        } finally {
            runCatching { tempFile.delete() }
            runCatching {
                if (tempDir.listFiles().isNullOrEmpty()) tempDir.delete()
            }
        }
    }

    private suspend fun failMissingSecret(downloadId: String): Result {
        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.FAILED,
            bytesDownloaded = 0,
            totalBytes = -1,
            localUri = null,
            errorCode = "HLS_SECRET_MISSING",
        )
        return Result.failure()
    }

    private fun resolveMediaPlaylist(
        downloadId: String,
        secret: DownloadSecret,
    ): HlsParseResult.Media {
        var current = secret.url
        repeat(MAX_MASTER_DEPTH) {
            val body = fetchText(downloadId, current, secret)
            when (val parsed = parser.parse(current, body)) {
                is HlsParseResult.Media -> return parsed
                is HlsParseResult.Master -> {
                    val selected = parsed.variants
                        .maxByOrNull { variant -> variant.bandwidth ?: 0L }
                        ?: error("MASTER_WITHOUT_VARIANT")
                    val externalAudio = selected.audioGroup?.let { group ->
                        parsed.audioRenditions.firstOrNull {
                            rendition -> rendition.groupId == group && rendition.url != null
                        }
                    }
                    if (externalAudio != null) {
                        error("EXTERNAL_AUDIO_RENDITION_REQUIRES_MUXER")
                    }
                    current = selected.url
                }
                is HlsParseResult.Rejected -> error(parsed.reason)
            }
        }
        error("MASTER_DEPTH_EXCEEDED")
    }

    private fun fetchText(
        downloadId: String,
        url: String,
        secret: DownloadSecret,
    ): String {
        val opened = openFollowingRedirects(downloadId, url, secret)
        val connection = opened.connection
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP_" + code)
            captureSetCookies(opened.finalUri, connection)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_MANIFEST_BYTES) error("MANIFEST_TOO_LARGE")
                    output.write(buffer, 0, read)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun appendHttps(
        downloadId: String,
        url: String,
        secret: DownloadSecret,
        output: FileOutputStream,
        alreadyDownloaded: Long,
    ): SegmentCopy {
        val opened = openFollowingRedirects(downloadId, url, secret)
        val connection = opened.connection
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("SEGMENT_HTTP_" + code)
            captureSetCookies(opened.finalUri, connection)
            val expected = connection.contentLengthLong
            if (expected > 0) ensureStorage(expected)
            var copied = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (isStopped) throw CancellationException("HLS worker stopped")
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (alreadyDownloaded + copied > MAX_HLS_BYTES) {
                        error("HLS_SIZE_LIMIT")
                    }
                    output.write(buffer, 0, read)
                }
            }
            return SegmentCopy(
                bytes = copied,
                contentType = connection.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowingRedirects(
        downloadId: String,
        rawUrl: String,
        secret: DownloadSecret,
    ): OpenedConnection {
        var current = DownloadPolicy.validateHttps(rawUrl)
            ?: error("INVALID_HTTPS_URL")

        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (!DownloadPolicy.resolvesToPublicAddress(current)) {
                error("NON_PUBLIC_TARGET")
            }

            val connection = URL(current.toString()).openConnection()
            require(connection is HttpsURLConnection)
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            secret.userAgent?.takeIf(String::isNotBlank)
                ?.let { connection.setRequestProperty("User-Agent", it) }
            DownloadPolicy.safeReferrer(secret.sourcePage, current)
                ?.let { connection.setRequestProperty("Referer", it) }

            if (DownloadPolicy.safeCookieTarget(secret.sourcePage, current)) {
                val jarCookie = CookieManager.getInstance().getCookie(current.toString())
                (jarCookie ?: secret.cookie)
                    ?.takeIf(String::isNotBlank)
                    ?.let { connection.setRequestProperty("Cookie", it) }
            }

            val code = connection.responseCode
            if (code !in REDIRECT_CODES) {
                return OpenedConnection(connection, current)
            }

            captureSetCookies(current, connection)
            if (redirectCount >= MAX_REDIRECTS) {
                connection.disconnect()
                error("REDIRECT_LIMIT")
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            current = DownloadPolicy.resolveRedirect(current, location ?: "")
                ?: error("UNSAFE_REDIRECT")
        }

        error("REDIRECT_LOOP_" + downloadId.take(8))
    }

    private fun captureSetCookies(
        uri: Uri,
        connection: HttpsURLConnection,
    ) {
        val headers = connection.headerFields
            .filterKeys { key -> key?.equals("Set-Cookie", ignoreCase = true) == true }
            .values
            .flatten()
        if (headers.isEmpty()) return
        val manager = CookieManager.getInstance()
        headers.forEach { value ->
            if (value.isNotBlank()) manager.setCookie(uri.toString(), value)
        }
        manager.flush()
    }

    private fun ensureStorage(nextBytes: Long) {
        val available = StatFs(applicationContext.cacheDir.absolutePath).availableBytes
        if (available < nextBytes + STORAGE_RESERVE_BYTES) {
            error("TEMP_STORAGE_LOW")
        }
    }

    private fun publishVerified(
        source: File,
        fileName: String,
        mimeType: String,
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/NevusQuetta",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values,
            ) ?: error("MEDIASTORE_INSERT_FAILED")
            try {
                resolver.openOutputStream(uri, "w")?.use { target ->
                    source.inputStream().use { input -> input.copyTo(target) }
                } ?: error("MEDIASTORE_OPEN_FAILED")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri.toString()
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

        val dir = applicationContext.getExternalFilesDir(
            Environment.DIRECTORY_DOWNLOADS,
        ) ?: applicationContext.filesDir
        dir.mkdirs()
        val target = File(dir, fileName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(target).toString()
    }

    private fun outputFormat(
        media: HlsParseResult.Media,
        contentType: String?,
    ): OutputFormat {
        if (media.initSegment != null) return OutputFormat("mp4", "video/mp4")
        return when (contentType) {
            "audio/aac", "audio/aacp" -> OutputFormat("aac", "audio/aac")
            "audio/mpeg" -> OutputFormat("mp3", "audio/mpeg")
            "audio/mp4", "audio/x-m4a" -> OutputFormat("m4a", "audio/mp4")
            "video/mp4" -> OutputFormat("mp4", "video/mp4")
            "video/mp2t", "application/mp2t" -> OutputFormat("ts", "video/mp2t")
            else -> OutputFormat("ts", "video/mp2t")
        }
    }

    private fun baseName(url: String): String {
        val segment = Uri.parse(url).lastPathSegment.orEmpty()
        return segment.substringBeforeLast('.').ifBlank { "video" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sanitizeError(error: Throwable): String =
        error.message
            ?.replace(Regex("https://[^\\s]+"), "[url]")
            ?.replace(Regex("(?i)(token|key|sig|signature|auth)=([^&\\s]+)"), "$1=[redacted]")
            ?.take(160)
            ?: error::class.java.simpleName.take(80)

    private data class SegmentCopy(
        val bytes: Long,
        val contentType: String?,
    )

    private data class OpenedConnection(
        val connection: HttpsURLConnection,
        val finalUri: Uri,
    )

    private data class OutputFormat(
        val extension: String,
        val mimeType: String,
    )

    companion object {
        const val KEY_DOWNLOAD_ID = "downloadId"

        private const val MAX_MASTER_DEPTH = 4
        private const val MAX_REDIRECTS = 5
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_HLS_BYTES = 8L * 1024L * 1024L * 1024L
        private const val STORAGE_RESERVE_BYTES = 32L * 1024L * 1024L
        private val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}
