package org.aerialpod.android.downloads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.aerialpod.android.appGraph
import org.aerialpod.core.db.Episodes
import java.io.File
import java.io.RandomAccessFile

/**
 * One download pass: evict what the policy no longer wants, then fetch what it
 * does, one file at a time.
 *
 * The policy is re-read before every file rather than planned once up front,
 * because a pass can take minutes and the queue can change underneath it — the
 * desktop re-runs `apply_policy` on every completion for the same reason.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val graph by lazy { applicationContext.appGraph }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val core = graph.core
        val policy = core.downloads
        var failures = 0

        while (!isStopped) {
            val plan = policy.plan()
            if (plan.isEmpty) break

            for (episode in plan.evict) evict(episode)

            val next = plan.fetch.firstOrNull() ?: continue
            val ok = runCatching { fetch(next) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrDefault(false)
            if (!ok) {
                failures++
                // Leave it 'none' so a later pass can retry, and stop pushing
                // at a network that is clearly not working.
                if (failures >= MAX_FAILURES) return@withContext Result.retry()
            }
        }
        Result.success()
    }

    // ---------------------------------------------------------------- fetch

    private suspend fun fetch(episode: Episodes): Boolean {
        val core = graph.core
        val destination = targetFile(episode) ?: return false
        val part = File(destination.path + ".part")

        core.repo.setDownloadState(episode.id, "downloading")

        val existing = if (part.exists()) part.length() else 0L

        // `prepareGet { execute { } }`, never `get()`. A plain `get()` reads the
        // whole body into memory before returning, which is survivable for a
        // feed and fatal for an episode: a four-hour show is a few hundred
        // megabytes and the heap is a couple. That is an OutOfMemoryError on an
        // OkHttp thread, which kills the process — and WorkManager retries,
        // so it presents as the app repeatedly stopping.
        val ok = graph.http.prepareGet(episode.media_url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
            // The client's 60-second request timeout is right for an API call
            // and wrong for a download, which legitimately takes minutes.
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
        }.execute { response ->
            when {
                // The range was past the end — what is on disk is already the file.
                response.status.value == 416 -> true

                !response.status.isSuccess() -> false

                else -> {
                    // A server that ignores Range answers 200 with the whole
                    // body, and appending it to a partial file would produce a
                    // corrupt one that still looks complete. Start over instead.
                    val append = existing > 0 && response.status.value == 206
                    if (!append) part.delete()
                    writeTo(part, response.bodyAsChannel(), append)
                    true
                }
            }
        }

        if (!ok) {
            core.repo.setDownloadState(episode.id, "none")
            return false
        }

        if (!part.renameTo(destination)) {
            core.repo.setDownloadState(episode.id, "none")
            return false
        }
        core.repo.setDownloadState(episode.id, "done", destination.path)

        // A download is a gpodder action in its own right: it is how the other
        // devices learn this episode was taken offline here.
        core.repo.podcastById(episode.podcast_id)?.let { podcast ->
            core.repo.enqueueAction(podcast.feed_url, episode.media_url, "download")
        }
        return true
    }

    private suspend fun writeTo(
        part: File,
        channel: io.ktor.utils.io.ByteReadChannel,
        append: Boolean,
    ) {
        part.parentFile?.mkdirs()
        RandomAccessFile(part, "rw").use { file ->
            file.seek(if (append) file.length() else 0L)
            if (!append) file.setLength(0)
            val buffer = ByteArray(CHUNK)
            while (!channel.isClosedForRead) {
                if (isStopped) throw CancellationException("download cancelled")
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                file.write(buffer, 0, read)
            }
        }
    }

    // ---------------------------------------------------------------- evict

    private fun evict(episode: Episodes) {
        episode.downloaded_path?.let { path ->
            File(path).delete()
            File("$path.part").delete()
        }
        graph.core.repo.setDownloadState(episode.id, "none", null)
    }

    // ---------------------------------------------------------------- paths

    /**
     * `<external files>/media/<podcast>/<title>-<id>.<ext>`.
     *
     * App-scoped external storage: no permission, removed with the app, and
     * large enough for audio. The id in the filename is what makes two episodes
     * with the same title from the same feed distinct.
     */
    private fun targetFile(episode: Episodes): File? {
        val root = applicationContext.getExternalFilesDir(null)
            ?: applicationContext.filesDir
            ?: return null
        val podcast = graph.core.repo.podcastById(episode.podcast_id)
        val folder = File(root, "media/" + safeName(podcast?.title ?: episode.podcast_id.toString()))
        folder.mkdirs()
        val extension = episode.media_url.substringBefore('?')
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= 5 } ?: "mp3"
        return File(folder, "${safeName(episode.title ?: "untitled")}-${episode.id}.$extension")
    }

    private fun safeName(text: String, limit: Int = 80): String =
        text.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
            .joinToString("")
            .take(limit)
            .trim('_')
            .ifEmpty { "untitled" }

    private companion object {
        const val CHUNK = 256 * 1024
        const val MAX_FAILURES = 3
        const val USER_AGENT = "AerialPod/0.1"
    }
}
