package com.nevus.quetta.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nevus.quetta.data.BrowserDatabase
import com.nevus.quetta.data.DownloadStatuses
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class HlsVodDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository =
        DownloadRepository(BrowserDatabase.get(appContext))
    private val parser = HlsVodParser()

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?: return Result.failure()
        val manifestUrl = inputData.getString(KEY_MANIFEST_URL)
            ?: return Result.failure()
        val userAgent = inputData.getString(KEY_USER_AGENT)

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

                val media = resolveMediaPlaylist(manifestUrl, userAgent)
                val parts = buildList {
                    media.initSegment?.let(::add)
                    addAll(media.segments)
                }

                var downloaded = 0L
                FileOutputStream(tempFile).use { output ->
                    parts.forEachIndexed { index, partUrl ->
                        if (isStopped) throw CancellationException("HLS worker stopped")
                        downloaded += appendHttps(partUrl, userAgent, output, downloaded)
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
                            ),
                        )
                    }
                }

                val extension = outputExtension(media)
                val fileName = DownloadPolicy.sanitizeFileName(
                    baseName(manifestUrl) + "-hls." + extension,
                )
                val mime = if (extension == "mp4") "video/mp4" else "video/mp2t"
                val localUri = publishVerified(tempFile, fileName, mime)

                repository.updateProgress(
                    downloadId = downloadId,
                    status = DownloadStatuses.COMPLETED,
                    bytesDownloaded = downloaded,
                    totalBytes = downloaded,
                    localUri = localUri,
                    errorCode = null,
                )
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

    private fun resolveMediaPlaylist(
        manifestUrl: String,
        userAgent: String?,
    ): HlsParseResult.Media {
        var current = manifestUrl
        repeat(MAX_MASTER_DEPTH) {
            val body = fetchText(current, userAgent)
            when (val parsed = parser.parse(current, body)) {
                is HlsParseResult.Media -> return parsed
                is HlsParseResult.Master -> {
                    current = parsed.variants
                        .maxByOrNull { it.bandwidth ?: 0L }
                        ?.url
                        ?: error("Master playlist tanpa variant")
                }
                is HlsParseResult.Rejected -> error(parsed.reason)
            }
        }
        error("Master playlist terlalu dalam")
    }

    private fun fetchText(url: String, userAgent: String?): String {
        val connection = openHttps(url, userAgent)
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP_" + code)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_MANIFEST_BYTES) error("Manifest HLS terlalu besar")
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
        userAgent: String?,
        output: FileOutputStream,
        alreadyDownloaded: Long,
    ): Long {
        DownloadPolicy.validateHttps(url) ?: error("Segment HLS bukan HTTPS valid")
        val connection = openHttps(url, userAgent)
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("SEGMENT_HTTP_" + code)
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
                        error("HLS melebihi batas keamanan ukuran")
                    }
                    output.write(buffer, 0, read)
                }
            }
            return copied
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(url: String, userAgent: String?): HttpsURLConnection {
        val uri = DownloadPolicy.validateHttps(url)
            ?: error("URL HLS bukan HTTPS valid")
        val connection = URL(uri.toString()).openConnection()
        require(connection is HttpsURLConnection)
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        userAgent?.takeIf(String::isNotBlank)
            ?.let { connection.setRequestProperty("User-Agent", it) }
        return connection
    }

    private fun ensureStorage(nextBytes: Long) {
        val available = StatFs(applicationContext.cacheDir.absolutePath).availableBytes
        if (available < nextBytes + STORAGE_RESERVE_BYTES) {
            error("Ruang sementara tidak mencukupi")
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
            ) ?: error("Gagal membuat output MediaStore")
            try {
                resolver.openOutputStream(uri, "w")?.use { target ->
                    source.inputStream().use { input -> input.copyTo(target) }
                } ?: error("Output MediaStore tidak dapat dibuka")
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

    private fun outputExtension(media: HlsParseResult.Media): String {
        if (media.initSegment != null) return "mp4"
        val paths = media.segments.map { Uri.parse(it).lastPathSegment.orEmpty() }
        return if (paths.all { it.substringBefore('?').endsWith(".ts", true) }) {
            "ts"
        } else {
            "mp4"
        }
    }

    private fun baseName(url: String): String {
        val segment = Uri.parse(url).lastPathSegment.orEmpty()
        return segment.substringBeforeLast('.').ifBlank { "video" }
    }

    private fun sanitizeError(error: Throwable): String =
        error.message
            ?.replace(Regex("https://[^\\s]+"), "[url]")
            ?.take(160)
            ?: error::class.java.simpleName.take(80)

    companion object {
        const val KEY_DOWNLOAD_ID = "downloadId"
        const val KEY_MANIFEST_URL = "manifestUrl"
        const val KEY_USER_AGENT = "userAgent"

        private const val MAX_MASTER_DEPTH = 3
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val MAX_HLS_BYTES = 8L * 1024L * 1024L * 1024L
        private const val STORAGE_RESERVE_BYTES = 32L * 1024L * 1024L
    }
}
