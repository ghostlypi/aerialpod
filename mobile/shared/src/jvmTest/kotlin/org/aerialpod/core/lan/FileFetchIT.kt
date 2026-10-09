package org.aerialpod.core.lan

import io.ktor.network.selector.SelectorManager
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The phone's file fetch against a running `aerialpod-daemon`: one analysed
 * episode, over the real socket and handshake, checked against the desktop's
 * own record of the file.
 *
 * Opt-in, like DesktopInteropIT, and with the same connection settings, plus
 * the episode to fetch and what the desktop recorded about it:
 *
 *     AERIALPOD_IT=1 AERIALPOD_IT_SECRET=… AERIALPOD_IT_FEED=… AERIALPOD_IT_GUID=… \
 *     AERIALPOD_IT_BYTES=… AERIALPOD_IT_SHA256=… \
 *     ./gradlew :shared:jvmTest --tests '*FileFetchIT*' --rerun-tasks
 *
 * Writes nothing to the desktop: a file link is never remembered as a peer.
 */
class FileFetchIT {

    private fun env(name: String) = System.getenv(name).orEmpty()

    @Test
    fun fetchesTheAnalysedFile() {
        if (env("AERIALPOD_IT") != "1" || env("AERIALPOD_IT_FEED").isEmpty()) {
            println("FileFetchIT skipped: set AERIALPOD_IT=1 and the AERIALPOD_IT_* episode")
            return
        }
        val secret = File(env("AERIALPOD_IT_SECRET")).readText().trim().hexToBytesOrNull()
            ?: error("pairing secret file is not hex")
        val expectedBytes = env("AERIALPOD_IT_BYTES").toLong()
        val sha = env("AERIALPOD_IT_SHA256")
        val out = File.createTempFile("filefetch", ".mp3").apply { deleteOnExit() }

        val digest = MessageDigest.getInstance("SHA-256")
        val started = System.nanoTime()
        val result = runBlocking {
            RandomAccessFile(out, "rw").use { file ->
                FileFetch(
                    address = env("AERIALPOD_IT_HOST").ifEmpty { "127.0.0.1" },
                    port = env("AERIALPOD_IT_PORT").toIntOrNull() ?: 47722,
                    key = channelKey(secret),
                    ident = Ident(deviceId = "00000000000000000000000000000002", caption = "FileFetchIT"),
                    selector = SelectorManager(Dispatchers.IO),
                ).run(
                    FileRequest(env("AERIALPOD_IT_FEED"), env("AERIALPOD_IT_GUID"), ""),
                    object : FileSink {
                        override suspend fun head(bytes: Long, sha256: String): Boolean {
                            println("FileFetchIT head: $bytes bytes, $sha256")
                            return bytes == expectedBytes && sha256 == sha
                        }

                        override suspend fun write(offset: Long, data: ByteArray) {
                            file.write(data)
                            digest.update(data)
                        }
                    },
                )
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9
        println("FileFetchIT: $result, ${out.length()} bytes in ${"%.1f".format(seconds)} s " +
            "(${"%.1f".format(out.length() / seconds / 1e6)} MB/s)")

        assertEquals(FetchResult.Complete, result)
        assertEquals(expectedBytes, out.length())
        assertEquals(sha, digest.digest().joinToString("") { "%02x".format(it) })
    }
}
