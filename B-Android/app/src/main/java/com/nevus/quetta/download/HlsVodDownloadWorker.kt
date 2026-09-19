package com.nevus.quetta.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.nevus.quetta.MainActivity
import com.nevus.quetta.R
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.DownloadStatuses
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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
    private val secrets = DownloadSecretStore(appContext)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo("Menyiapkan HLS")

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?: return Result.failure()
        val item = repository.get(downloadId)
            ?: return Result.failure()
        val secret = secrets.get(downloadId)
            ?: return fail(downloadId, "HLS_SECRET_MISSING")

        setForeground(createForegroundInfo(item.fileName))

        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.RUNNING,
            bytesDownloaded = 0,
            totalBytes = -1,
            localUri = null,
            errorCode = null,
        )

        val tempDir = File(applicationContext.cacheDir, TEMP_DIR)
        val tempFile = File(tempDir, downloadId + ".part")

        return try {
            withContext(Dispatchers.IO) {
                tempDir.mkdirs()
                tempFile.delete()

                val media = resolveMediaPlaylist(secret)
                val parts = buildList {
                    media.initSegment?.let(::add)
                    addAll(media.segments)
                }

                var downloaded = 0L
                FileOutputStream(tempFile).use { output ->
                    parts.forEachIndexed { index, partUrl ->
                        if (isStopped) throw CancellationException("HLS worker stopped")
                        downloaded += appendHttps(
                            url = partUrl,
                            secret = secret,
                            output = output,
                            alreadyDownloaded = downloaded,
                        )
                        repository.updateProgress(
                            downloadId = downloadId,
                            status = DownloadStatuses.RUNNING,
                            bytesDownloaded = downloaded,
                            totalBytes = -1,
                            localUri = null,
                            errorCode = null,
                        )
                        setProgress(
                            androidx.work.workDataOf(
                                "segmentsDone" to (index + 1),
                                "segmentsTotal" to parts.size,
                                "bytesDownloaded" to downloaded,
                                "gapsSkipped" to media.skippedGapSegments,
                            ),
                        )
                    }
                    output.fd.sync()
                }

                val extension = if (media.initSegment != null) "mp4" else "ts"
                val fileName = withExtension(item.fileName, extension)
                val mime = if (extension == "mp4") "video/mp4" else "video/mp2t"
                val digest = sha256(tempFile)
                val localUri = publishVerified(tempFile, fileName, mime)

                repository.updateDigest(downloadId, digest)
                repository.updateProgress(
                    downloadId = downloadId,
                    status = DownloadStatuses.COMPLETED,
                    bytesDownloaded = downloaded,
                    totalBytes = downloaded,
                    localUri = localUri,
                    errorCode = null,
                )
                secrets.remove(downloadId)
            }
            Result.success()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                val current = repository.get(downloadId)
                repository.updateProgress(
                    downloadId = downloadId,
                    status = DownloadStatuses.CANCELED,
                    bytesDownloaded = current?.bytesDownloaded ?: 0,
                    totalBytes = current?.totalBytes ?: -1,
                    localUri = current?.localUri,
                    errorCode = null,
                )
            }
            throw cancel
        } catch (error: Throwable) {
            val current = repository.get(downloadId)
            repository.updateProgress(
                downloadId = downloadId,
                status = DownloadStatuses.FAILED,
                bytesDownloaded = current?.bytesDownloaded ?: 0,
                totalBytes = current?.totalBytes ?: -1,
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

    private fun resolveMediaPlaylist(secret: DownloadSecret): HlsParseResult.Media {
        var current = secret.url
        repeat(MAX_MASTER_DEPTH) {
            val body = fetchText(current, secret)
            when (val parsed = parser.parse(current, body)) {
                is HlsParseResult.Media -> return parsed
                is HlsParseResult.Master -> {
                    current = when (val selected = parser.selectVariant(parsed)) {
                        is HlsVariantSelection.Selected -> selected.variant.url
                        is HlsVariantSelection.Rejected -> error(selected.reason)
                    }
                }
                is HlsParseResult.Rejected -> error(parsed.reason)
            }
        }
        error("Master playlist terlalu dalam")
    }

    private fun fetchText(url: String, secret: DownloadSecret): String {
        val response = openValidated(url, secret)
        val connection = response.connection
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HLS_HTTP_" + code)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_MANIFEST_BYTES) error("HLS_MANIFEST_TOO_LARGE")
                    output.write(buffer, 0, read)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun appendHttps(
        url: String,
        secret: DownloadSecret,
        output: FileOutputStream,
        alreadyDownloaded: Long,
    ): Long {
        val response = openValidated(url, secret)
        val connection = response.connection
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HLS_SEGMENT_HTTP_" + code)
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
                        error("HLS_TOO_LARGE")
                    }
                    output.write(buffer, 0, read)
                }
            }
            if (expected > 0 && copied != expected) error("HLS_SEGMENT_LENGTH_MISMATCH")
            return copied
        } finally {
            connection.disconnect()
        }
    }

    private fun openValidated(
        rawUrl: String,
        secret: DownloadSecret,
    ): Response {
        val manifestOrigin = DownloadPolicy.validateHttps(secret.url)
            ?: error("INVALID_HLS_ORIGIN")
        var current = DownloadPolicy.validateHttps(rawUrl)
            ?: error("INVALID_HLS_URL")

        repeat(MAX_REDIRECTS + 1) { redirect ->
            if (!DownloadPolicy.resolvesToPublicAddress(current)) error("HLS_NON_PUBLIC_TARGET")
            val connection = URL(current.toString()).openConnection()
            require(connection is HttpsURLConnection)
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            secret.userAgent?.takeIf(String::isNotBlank)
                ?.let { connection.setRequestProperty("User-Agent", it) }
            DownloadPolicy.safeReferrer(secret.sourcePage, current)
                ?.let { connection.setRequestProperty("Referer", it) }
            if (secret.cookie != null && DownloadPolicy.sameOrigin(manifestOrigin, current)) {
                connection.setRequestProperty("Cookie", secret.cookie)
            }

            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                if (redirect >= MAX_REDIRECTS) {
                    connection.disconnect()
                    error("HLS_TOO_MANY_REDIRECTS")
                }
                val next = connection.getHeaderField("Location")
                    ?.let { DownloadPolicy.resolveRedirect(current, it) }
                connection.disconnect()
                current = next ?: error("HLS_INVALID_REDIRECT")
                return@repeat
            }
            return Response(current, connection)
        }
        error("HLS_REDIRECT_RESOLUTION_FAILED")
    }

    private fun ensureStorage(nextBytes: Long) {
        val available = StatFs(applicationContext.cacheDir.absolutePath).availableBytes
        if (available < nextBytes + STORAGE_RESERVE_BYTES) {
            error("HLS_INSUFFICIENT_STORAGE")
        }
    }

    private fun publishVerified(
        source: File,
        fileName: String,
        mimeType: String,
    ): String {
        if (!source.exists() || source.length() <= 0L) error("HLS_EMPTY_OUTPUT")
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
            ) ?: error("HLS_MEDIASTORE_INSERT_FAILED")
            try {
                resolver.openOutputStream(uri, "w")?.use { target ->
                    source.inputStream().use { input -> input.copyTo(target) }
                } ?: error("HLS_MEDIASTORE_OPEN_FAILED")
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun withExtension(base: String, extension: String): String {
        val clean = DownloadPolicy.sanitizeFileName(base)
        val withoutKnown = clean.removeSuffix(".m3u8")
            .removeSuffix(".mp4")
            .removeSuffix(".ts")
        return DownloadPolicy.sanitizeFileName("$withoutKnown.$extension")
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NevusQuetta HLS",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
        return ForegroundInfo(NOTIFICATION_ID_BASE + id.hashCode().and(0x0fff), notification)
    }

    private suspend fun fail(downloadId: String, code: String): Result {
        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.FAILED,
            bytesDownloaded = 0,
            totalBytes = -1,
            localUri = null,
            errorCode = code,
        )
        return Result.failure()
    }

    private fun sanitizeError(error: Throwable): String =
        (error.message ?: error::class.java.simpleName)
            .replace(Regex("""https://[^\s]+"""), "[url]")
            .take(160)

    private data class Response(
        val finalUri: Uri,
        val connection: HttpsURLConnection,
    )

    companion object {
        const val KEY_DOWNLOAD_ID = "downloadId"

        private const val TEMP_DIR = "nevus_hls"
        private const val MAX_MASTER_DEPTH = 4
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_HLS_BYTES = 16L * 1024L * 1024L * 1024L
        private const val STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val CHANNEL_ID = "nevus_hls_downloads"
        private const val NOTIFICATION_ID_BASE = 5200

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
