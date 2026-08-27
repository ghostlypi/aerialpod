package org.aerialpod.core.lan

import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aerialpod.core.db.JvmDriverFactory
import org.aerialpod.core.db.Repo
import org.aerialpod.core.db.openDatabase
import org.aerialpod.core.sync.Matcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real thing: our peer against a running `aerialpod-daemon`.
 *
 * Everything else in this suite checks us against *vectors* of the desktop.
 * This checks us against the desktop — its actual socket, its actual handshake,
 * its actual snapshot of a real library. It is the difference between "the
 * bytes we generate match the bytes Python generated once" and "the two
 * programs can hold a conversation".
 *
 * Opt-in, because it needs a daemon running and the machine's pairing key:
 *
 *     AERIALPOD_IT=1 ./gradlew :shared:jvmTest --tests '*DesktopInteropIT*' --rerun-tasks
 *
 * **What it writes to the desktop:** one `lan_peers` row, from the desktop's own
 * `remember_peer` on a successful handshake. Nothing else can change, because
 * the snapshot we send is built from an empty database — every section is empty,
 * so the desktop's merge loop iterates nothing.
 */
class DesktopInteropIT {

    private companion object {
        val enabled = System.getenv("AERIALPOD_IT") == "1"
        val host = System.getenv("AERIALPOD_IT_HOST") ?: "127.0.0.1"
        val port = System.getenv("AERIALPOD_IT_PORT")?.toIntOrNull() ?: 47722
        val secretFile = System.getenv("AERIALPOD_IT_SECRET") ?: ""
        const val DEVICE_ID = "00000000000000000000000000000001"
    }

    private fun skip(reason: String): Boolean {
        println("DesktopInteropIT skipped: $reason")
        return true
    }

    /** Connect, handshake, and capture whatever the desktop says. */
    private fun exchange(): Pair<PeerConnection, Snapshot>? {
        if (!enabled) { skip("set AERIALPOD_IT=1 to run"); return null }
        val file = File(secretFile)
        if (!file.isFile) { skip("no pairing secret at '$secretFile'"); return null }
        val secret = file.readText().trim().hexToBytesOrNull()
            ?: error("pairing secret file is not hex")

        return runBlocking {
            val selector = SelectorManager(Dispatchers.IO)
            val connection = PeerConnection(
                address = host,
                port = port,
                key = channelKey(secret),
                ident = Ident(deviceId = DEVICE_ID, caption = "AerialPod interop test", port = 0),
                selector = selector,
            )
            val snapshot = CompletableDeferred<Snapshot>()
            val pump = launch {
                runCatching {
                    connection.run(
                        scope = this,
                        onReady = { peer ->
                            // An empty library: this snapshot carries nothing the
                            // desktop can resolve, so its merge is a no-op.
                            peer.send(emptySnapshot().toJsonObject())
                        },
                        onMessage = { _, message ->
                            if (message.typeOrNull() == "snapshot" && !snapshot.isCompleted) {
                                snapshot.complete(message.decodeAs())
                            }
                        },
                    )
                }
            }
            val received = try {
                withTimeout(30_000) { snapshot.await() }
            } finally {
                connection.close()
                pump.cancel()
                selector.close()
            }
            connection to received
        }
    }

    private fun emptySnapshot(): Snapshot {
        val repo = Repo(openDatabase(JvmDriverFactory()))
        return SnapshotSync(repo, Matcher(repo)).buildSnapshot()
    }

    // ---------------------------------------------------------------- tests

    /**
     * The handshake itself: HKDF, the HMAC proofs, the session keys, AES-GCM
     * framing and the ident exchange, all against the real implementation.
     */
    @Test
    fun completesTheHandshakeWithARunningDesktop() {
        val (connection, _) = exchange() ?: return
        assertTrue(connection.established, "channel should be established")
        val peerId = assertNotNull(connection.peerId, "the desktop should identify itself")
        assertEquals(32, peerId.length, "a desktop device id is uuid4().hex")
        assertTrue(connection.caption.isNotBlank(), "the desktop should send a caption")
        println("  peer      : ${connection.caption} (${peerId.take(8)}…)")
        println("  peer port : ${connection.peerPort}")
    }

    /** The snapshot wire format, on a real library rather than a fixture. */
    @Test
    fun readsARealSnapshot() {
        val (_, snapshot) = exchange() ?: return
        assertEquals(SNAPSHOT_VERSION, snapshot.v)
        println("  intents=${snapshot.intents.size} settings=${snapshot.settings.size} " +
            "positions=${snapshot.positions.size}")

        assertTrue(
            snapshot.intents.isNotEmpty() || snapshot.positions.isNotEmpty(),
            "a library with listening history should send something",
        )
        // Every record must carry the fields the merge depends on. A default-valued
        // stamp here is the exact failure a wrong @SerialName would produce, and it
        // would make every incoming record look infinitely old.
        for (intent in snapshot.intents) {
            assertTrue(intent.feed.isNotBlank(), "intent without a feed")
            assertTrue(intent.media.isNotBlank(), "intent without a media url")
            assertTrue(intent.intent == "queued" || intent.intent == "excluded", intent.intent)
            assertTrue(intent.updatedAt > 0, "intent with no timestamp: $intent")
            assertTrue(intent.updatedBy.isNotBlank(), "intent with no author: $intent")
        }
        for (position in snapshot.positions) {
            assertTrue(position.feed.isNotBlank(), "position without a feed")
            assertTrue(position.position > 0, "position with no offset: $position")
            assertTrue(position.updatedAt > 0, "position with no timestamp: $position")
        }
        for (setting in snapshot.settings) {
            assertTrue(setting.feed.isNotBlank(), "setting without a feed")
            assertTrue(setting.updatedAt > 0, "setting with no timestamp: $setting")
        }
    }

    /**
     * Every record the desktop sent, resolved and applied.
     *
     * The library is seeded from the snapshot's own references — the feeds and
     * GUIDs it names — so this is the phone's real job: take what a peer sends
     * and land all of it, with nothing silently unresolvable.
     */
    @Test
    fun mergesEveryRecordARealDesktopSent() {
        val (_, snapshot) = exchange() ?: return

        val db = openDatabase(JvmDriverFactory())
        val repo = Repo(db)
        val sync = SnapshotSync(repo, Matcher(repo))

        // Recreate the library the snapshot is talking about.
        val feeds = (snapshot.intents.map { it.feed } +
            snapshot.positions.map { it.feed } +
            snapshot.settings.map { it.feed }).distinct()
        val podcastIds = feeds.associateWith { repo.upsertPodcast(it, syncState = "clean") }

        data class Ref(val feed: String, val guid: String?, val media: String)
        val refs = (snapshot.intents.map { Ref(it.feed, it.guid, it.media) } +
            snapshot.positions.map { Ref(it.feed, it.guid, it.media) }).distinctBy {
            it.feed to (it.guid ?: it.media)
        }
        for (ref in refs) {
            db.episodesQueries.insertEpisode(
                podcast_id = podcastIds.getValue(ref.feed),
                guid = ref.guid, media_url = ref.media, title = null, description = null,
                pub_date = null, duration_secs = null, mime = null, file_size = null,
                image_url = null,
            )
        }
        println("  seeded ${feeds.size} podcast(s), ${refs.size} episode(s)")

        val counts = sync.mergeSnapshot(snapshot)
        println("  merged $counts")

        assertEquals(snapshot.positions.size, counts.positions, "every position should apply")
        assertEquals(snapshot.intents.size, counts.intents, "every intent should apply")
        assertEquals(snapshot.settings.size, counts.settings, "every setting should apply")

        // The queue the desktop actually has, rebuilt here from its intents.
        val queued = snapshot.intents.count { it.intent == "queued" }
        assertEquals(queued, db.queueQueries.queueItems().executeAsList().size)

        // Re-merging must change nothing: the stamps we stored are the peer's.
        assertEquals(MergeCounts(), sync.mergeSnapshot(snapshot), "merge should be idempotent")
    }
}
