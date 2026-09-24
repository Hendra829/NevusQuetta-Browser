package com.nevus.quetta.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.DownloadStatuses
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

class ResumableDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = DownloadRepository(BrowserDatabase.get(appContext))
    private val secrets = DownloadSecretStore(appContext)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo("Menyiapkan unduhan")

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?: return Result.failure()
        val item = repository.get(downloadId)
            ?: return Result.failure()
        val secret = secrets.get(downloadId)
            ?: return fail(downloadId, "DOWNLOAD_SECRET_MISSING")

        setForeground(createForegroundInfo(item.fileName))

        val tempDir = File(applicationContext.filesDir, TEMP_DIR)
        val part = File(tempDir, downloadId + ".part")

        return try {
            withContext(Dispatchers.IO) {
                tempDir.mkdirs()
                var offset = if (item.supportsResume && part.exists()) part.length() else 0L
                if (offset == 0L && part.exists()) part.delete()

                repository.updateProgress(
                    downloadId = downloadId,
                    status = DownloadStatuses.RUNNING,
                    bytesDownloaded = offset,
                    totalBytes = item.totalBytes,
                    localUri = null,
                    errorCode = null,
                )

                val response = openValidated(
                    rawUrl = secret.url,
                    secret = secret,
                    offset = offset,
                    currentEtag = item.etag,
                    currentLastModified = item.lastModified,
                )
                val connection = response.connection
                try {
                    val code = connection.responseCode
                    val contentRange = connection.getHeaderField("Content-Range")
                    val total = DownloadPolicy.parseContentRangeTotal(contentRange)
                        ?: connection.contentLengthLong.takeIf { it > 0 }?.let { length ->
                            if (code == HttpURLConnection.HTTP_PARTIAL) offset + length else length
                        }
                        ?: item.totalBytes
                    if (total > MAX_DIRECT_BYTES) error("DOWNLOAD_TOO_LARGE")
                    ensureStorage(part, (total - offset).coerceAtLeast(0))

                    if (offset > 0L) {
                        when (code) {
                            HttpURLConnection.HTTP_PARTIAL -> {
                                val start = DownloadPolicy.parseContentRangeStart(contentRange)
                                if (start != offset) error("INVALID_CONTENT_RANGE")
                                val responseEtag = connection.getHeaderField("ETag")
                                val responseLastModified =
                                    connection.getHeaderField("Last-Modified")
                                if (!currentEtag.isNullOrBlank() &&
                                    !responseEtag.isNullOrBlank() &&
                                    currentEtag != responseEtag
                                ) {
                                    error("RESUME_ETAG_CHANGED")
                                }
                                if (currentEtag.isNullOrBlank() &&
                                    !currentLastModified.isNullOrBlank() &&
                                    !responseLastModified.isNullOrBlank() &&
                                    currentLastModified != responseLastModified
                                ) {
                                    error("RESUME_LAST_MODIFIED_CHANGED")
                                }
                            }
                            HttpURLConnection.HTTP_OK -> {
                                // Server ignored Range or validator changed. Restart safely.
                                offset = 0L
                                FileOutputStream(part, false).use { }
                            }
                            HTTP_RANGE_NOT_SATISFIABLE -> {
                                if (total == offset && part.length() == offset) {
                                    return@withContext finalizeDownload(downloadId, item.fileName, item.mimeType, part)
                                }
                                error("RANGE_NOT_SATISFIABLE")
                            }
                            else -> error("HTTP_" + code)
                        }
                    } else if (code !in 200..299) {
                        error("HTTP_" + code)
                    }

                    val supportsResume =
                        code == HttpURLConnection.HTTP_PARTIAL ||
                            connection.getHeaderField("Accept-Ranges")
                                ?.equals("bytes", ignoreCase = true) == true

                    repository.updateResumeMetadata(
                        downloadId = downloadId,
                        supportsResume = supportsResume,
                        etag = connection.getHeaderField("ETag")?.take(256) ?: item.etag,
                        lastModified = connection.getHeaderField("Last-Modified")?.take(256)
                            ?: item.lastModified,
                        totalBytes = total,
                    )

                    var downloaded = offset
                    var lastReported = downloaded
                    FileOutputStream(part, offset > 0L).use { output ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                if (isStopped) throw CancellationException("Download worker stopped")
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (downloaded > MAX_DIRECT_BYTES) error("DOWNLOAD_TOO_LARGE")
                                if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                                    repository.updateProgress(
                                        downloadId = downloadId,
                                        status = DownloadStatuses.RUNNING,
                                        bytesDownloaded = downloaded,
                                        totalBytes = total,
                                        localUri = null,
                                        errorCode = null,
                                    )
                                    setProgress(
                                        androidx.work.workDataOf(
                                            "bytesDownloaded" to downloaded,
                                            "totalBytes" to total,
                                        ),
                                    )
                                    lastReported = downloaded
                                }
                            }
                            output.fd.sync()
                        }
                    }

                    if (total > 0 && downloaded != total) {
                        error("DOWNLOAD_LENGTH_MISMATCH")
                    }

                    repository.updateProgress(
                        downloadId = downloadId,
                        status = DownloadStatuses.RUNNING,
                        bytesDownloaded = downloaded,
                        totalBytes = if (total > 0) total else downloaded,
                        localUri = null,
                        errorCode = null,
                    )
                    finalizeDownload(downloadId, item.fileName, item.mimeType, part)
                } finally {
                    connection.disconnect()
                }
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                val current = repository.get(downloadId)
                val requestedPause = current?.status == DownloadStatuses.PAUSED
                repository.updateProgress(
                    downloadId = downloadId,
                    status = if (requestedPause) DownloadStatuses.PAUSED else DownloadStatuses.CANCELED,
                    bytesDownloaded = part.takeIf(File::exists)?.length()
                        ?: current?.bytesDownloaded
                        ?: 0L,
                    totalBytes = current?.totalBytes ?: -1L,
                    localUri = current?.localUri,
                    errorCode = null,
                )
            }
            throw cancel
        } catch (error: Throwable) {
            repository.updateProgress(
                downloadId = downloadId,
                status = DownloadStatuses.FAILED,
                bytesDownloaded = part.takeIf(File::exists)?.length() ?: 0L,
                totalBytes = repository.get(downloadId)?.totalBytes ?: -1L,
                localUri = null,
                errorCode = sanitizeError(error),
            )
            Result.failure()
        }
    }

    private suspend fun finalizeDownload(
        downloadId: String,
        fileName: String,
        mimeType: String?,
        source: File,
    ): Result {
        if (!source.exists() || source.length() <= 0L) error("EMPTY_DOWNLOAD")
        val digest = sha256(source)
        val uri = publish(source, fileName, mimeType ?: "application/octet-stream")
        repository.updateDigest(downloadId, digest)
        repository.updateProgress(
            downloadId = downloadId,
            status = DownloadStatuses.COMPLETED,
            bytesDownloaded = source.length(),
            totalBytes = source.length(),
            localUri = uri,
            errorCode = null,
        )
        source.delete()
        secrets.remove(downloadId)
        return Result.success(
            androidx.work.workDataOf(
                "sha256" to digest,
                "localUri" to uri,
            ),
        )
    }

    private fun openValidated(
        rawUrl: String,
        secret: DownloadSecret,
        offset: Long,
        currentEtag: String?,
        currentLastModified: String?,
    ): Response {
        val initial = DownloadPolicy.validateHttps(rawUrl)
            ?: error("INVALID_HTTPS_URL")
        var current = initial

        repeat(MAX_REDIRECTS + 1) { redirect ->
            if (!DownloadPolicy.resolvesToPublicAddress(current)) error("NON_PUBLIC_TARGET")
            val connection = URL(current.toString()).openConnection()
            require(connection is HttpsURLConnection)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.requestMethod = "GET"

            secret.userAgent?.takeIf(String::isNotBlank)
                ?.let { connection.setRequestProperty("User-Agent", it) }
            DownloadPolicy.safeReferrer(secret.sourcePage, current)
                ?.let { connection.setRequestProperty("Referer", it) }
            if (secret.cookie != null && DownloadPolicy.sameOrigin(initial, current)) {
                connection.setRequestProperty("Cookie", secret.cookie)
            }
            if (offset > 0L) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                val strongEtag = currentEtag
                    ?.takeIf(String::isNotBlank)
                    ?.takeUnless { it.trimStart().startsWith("W/", ignoreCase = true) }
                (strongEtag ?: currentLastModified)
                    ?.takeIf(String::isNotBlank)
                    ?.let { connection.setRequestProperty("If-Range", it) }
            }

            val code = connection.responseCode
            if (code in REDIRECT_CODES) {
                if (redirect >= MAX_REDIRECTS) {
                    connection.disconnect()
                    error("TOO_MANY_REDIRECTS")
                }
                val location = connection.getHeaderField("Location")
                val next = location?.let { DownloadPolicy.resolveRedirect(current, it) }
                connection.disconnect()
                current = next ?: error("INVALID_REDIRECT")
                return@repeat
            }
            return Response(current, connection)
        }
        error("REDIRECT_RESOLUTION_FAILED")
    }

    private fun ensureStorage(part: File, remainingBytes: Long) {
        if (remainingBytes <= 0L) return
        val available = StatFs(part.parentFile?.absolutePath ?: applicationContext.filesDir.absolutePath)
            .availableBytes
        if (available < remainingBytes + STORAGE_RESERVE_BYTES) {
            error("INSUFFICIENT_STORAGE")
        }
    }

    private fun publish(source: File, fileName: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, DownloadPolicy.sanitizeFileName(fileName))
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/NevusQuetta",
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MEDIASTORE_INSERT_FAILED")
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

        val dir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: applicationContext.filesDir
        dir.mkdirs()
        val target = File(dir, DownloadPolicy.sanitizeFileName(fileName))
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(target).toString()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun createForegroundInfo(text: String): ForegroundInfo =
        DownloadForegroundInfoFactory.create(
            context = applicationContext,
            notificationId = 4200 + id.hashCode().and(0x0fff),
            channelId = "nevus_downloads",
            channelName = "NevusQuetta Downloads",
            text = text,
        )

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
            .replace(
                Regex("""(?i)(token|key|sig|signature|auth)=([^&\s]+)"""),
            ) { match ->
                match.groupValues[1] + "=[redacted]"
            }
            .take(160)

    private data class Response(
        val finalUri: Uri,
        val connection: HttpsURLConnection,
    )

    companion object {
        const val KEY_DOWNLOAD_ID = "downloadId"

        private const val TEMP_DIR = "nevus_download_parts"
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512L * 1024L
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val STORAGE_RESERVE_BYTES = 64L * 1024L * 1024L
        private const val MAX_DIRECT_BYTES = 16L * 1024L * 1024L * 1024L
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
