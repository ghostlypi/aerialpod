package org.aerialpod.core

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aerialpod.core.db.AerialPodDatabase
import org.aerialpod.core.db.Library
import org.aerialpod.core.db.Repo
import org.aerialpod.core.downloads.DownloadPolicy
import org.aerialpod.core.feeds.FeedFetcher
import org.aerialpod.core.feeds.Opml
import org.aerialpod.core.gpodder.GpodderClient
import org.aerialpod.core.gpodder.GpodderCredentialStore
import org.aerialpod.core.gpodder.GpodderSync
import org.aerialpod.core.lan.LanPeerService
import org.aerialpod.core.lan.PairingKeys
import org.aerialpod.core.lan.SecretStore
import org.aerialpod.core.lan.SnapshotSync
import org.aerialpod.core.queue.QueueManager
import org.aerialpod.core.sync.Matcher

/**
 * The shared core, assembled.
 *
 * Exists so the two apps cannot wire it differently. In particular it is where
 * the queue meets the peer mesh, in the two directions that are easy to forget:
 *
 *  - **out** — a user's queue decision calls `onQueueEdited()`, which debounces
 *    and pushes a snapshot. A queue op that changes the local queue and skips
 *    this looks perfectly correct on the device it was made on.
 *  - **in** — a peer's state landing calls [onPeerStateMerged], which rebuilds
 *    the queue. The merge writes intents and queue rows directly, but the queue
 *    is *derived*: without a reconcile, an episode a peer queued sits at
 *    whatever position it arrived with, and one it finished never leaves.
 */
class AerialPodCore(
    database: AerialPodDatabase,
    secretStore: SecretStore,
    private val credentials: GpodderCredentialStore,
    private val httpClient: HttpClient,
    private val deviceCaption: String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = ::epochSeconds,
    private val dryRunSync: Boolean = false,
) {
    val repo = Repo(database, now)
    val library = Library(repo, ioDispatcher)
    val downloads = DownloadPolicy(repo)
    val matcher = Matcher(repo)
    val snapshots = SnapshotSync(repo, matcher, now)
    val pairing = PairingKeys(secretStore)

    val lan = LanPeerService(
        repo = repo,
        sync = snapshots,
        channelKey = { pairing.channelKey() },
        deviceCaption = deviceCaption,
        scope = scope,
        ioDispatcher = ioDispatcher,
    )

    private val _syncRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /**
     * A gpodder action was queued that the other apps should see soon. The
     * gpodder sync loop listens here; until it exists, nothing does, and the
     * action simply waits in the outbox.
     */
    val syncRequested: SharedFlow<Unit> = _syncRequested.asSharedFlow()

    private val _queueChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /**
     * The derived queue changed — by a user edit, a reconcile, or a peer's
     * merge. The download policy listens here, because "keep the first N queue
     * items on disk" has to react to the queue, not to what caused it to move.
     */
    val queueChanged: SharedFlow<Unit> = _queueChanged.asSharedFlow()

    val queue = QueueManager(
        repo = repo,
        now = now,
        onIntentChanged = { lan.onQueueEdited() },
        onSyncNeeded = { _syncRequested.tryEmit(Unit) },
        onQueueChanged = { _queueChanged.tryEmit(Unit) },
    )

    val feeds = FeedFetcher(repo, httpClient, now)

    private var cachedClient: GpodderClient? = null

    val gpodder = GpodderSync(
        repo = repo,
        matcher = matcher,
        deviceCaption = deviceCaption,
        clientProvider = { gpodderClient() },
        now = now,
        dryRun = dryRunSync,
    )

    private val _subscriptionsAdded = MutableSharedFlow<List<Long>>(extraBufferCapacity = 8)

    /** Podcast ids that arrived from the server and still need their feed fetched. */
    val subscriptionsAdded: SharedFlow<List<Long>> = _subscriptionsAdded.asSharedFlow()

    private val syncLock = Mutex()
    private var debouncedSync: Job? = null

    init {
        scope.launch {
            lan.merged.collect { onPeerStateMerged() }
        }
        scope.launch {
            // A subscription that arrived from the server has a URL and nothing
            // else — no title, no episodes — until its feed is fetched.
            subscriptionsAdded.collect { ids ->
                refreshFeeds(ids)
                // Now that those episodes exist, go back for the listening
                // history the last cycle deliberately left on the server.
                runCatching { syncNow() }
            }
        }
        scope.launch {
            // The desktop debounces a post-playback sync by ten seconds so a
            // burst of actions rides one request. Same here; the *periodic*
            // schedule is the platform's job (WorkManager on Android), because
            // a coroutine timer would only run while the process happens to be
            // alive and would keep it awake when it is.
            _syncRequested.collect {
                debouncedSync?.cancel()
                debouncedSync = scope.launch {
                    delay(SYNC_DEBOUNCE_MS)
                    runCatching { syncNow() }
                }
            }
        }
    }

    /**
     * Run one gpodder.net cycle. Serialised: a scheduled sync landing on top of
     * a manual one would double-push the outbox.
     */
    suspend fun syncNow(): GpodderSync.Result = syncLock.withLock {
        val result = gpodder.syncNow()
        // Pulled actions change episode state, and the queue is derived from it.
        if (result.applied > 0) queue.reconcile()
        if (result.subscriptionsAdded.isNotEmpty()) {
            _subscriptionsAdded.emit(result.subscriptionsAdded)
        }
        result
    }

    fun accountConfigured(): Boolean = credentials.load() != null

    /**
     * Refresh one feed and let the queue see any new episodes.
     *
     * New episodes only reach the queue through [QueueManager.reconcile] —
     * the feed writes rows, and reconcile decides which of them the user is
     * actually meant to see.
     */
    suspend fun refreshFeed(podcastId: Long): Int {
        val fresh = feeds.fetchAndStore(podcastId)
        // Unconditionally, exactly as the desktop's hub does on refreshFinished.
        // Guarding on `fresh > 0` looks like an optimisation and is a bug: a
        // podcast's *first* fetch always reports 0 new, because the back
        // catalogue is archived rather than counted, and the one episode left
        // 'new' would then never reach the queue.
        queue.reconcile()
        return fresh
    }

    /**
     * Refresh several feeds, carrying on past the ones that fail.
     *
     * One dead feed must not cost the user every other refresh, so failures are
     * collected and reported rather than thrown.
     */
    suspend fun refreshFeeds(podcastIds: List<Long>): FeedRefresh {
        var fresh = 0
        val failures = mutableListOf<String>()
        for (id in podcastIds) {
            try {
                fresh += feeds.fetchAndStore(id)
            } catch (exc: CancellationException) {
                throw exc
            } catch (exc: Exception) {
                failures += exc.message ?: "unknown error"
            }
        }
        queue.reconcile()
        return FeedRefresh(fresh, failures)
    }

    /**
     * Subscribe to a feed URL and fetch it.
     *
     * `add_pending` rather than `clean`: the subscription still has to reach
     * gpodder.net, and a podcast added on the phone that never syncs is one the
     * desktop never learns about. The fetch is what turns a bare URL into a
     * title, a cover and episodes.
     */
    suspend fun addPodcast(feedUrl: String): Long {
        val url = feedUrl.trim()
        require(url.isNotEmpty()) { "Feed URL is empty" }
        val id = repo.upsertPodcast(url)
        _syncRequested.tryEmit(Unit)
        refreshFeed(id)
        return id
    }

    /** Import an OPML document, then fetch every feed it brought in. */
    suspend fun importOpml(document: String): FeedRefresh {
        val ids = Opml.import(document, repo)
        if (ids.isNotEmpty()) _syncRequested.tryEmit(Unit)
        return refreshFeeds(ids)
    }

    fun exportOpml(): String = Opml.export(repo, now)

    suspend fun refreshAllFeeds(): FeedRefresh =
        refreshFeeds(repo.subscribedPodcasts().map { it.id })

    data class FeedRefresh(val newEpisodes: Int, val failures: List<String>)

    /**
     * What a background wake-up does: refresh every feed, then sync.
     *
     * The desktop's `Refresher` runs hourly and lets the reconcile that follows
     * drive everything else, so this is the same pass on the platform's
     * schedule. It exists in the core rather than in the Android worker because
     * iOS needs the identical pass from `BGAppRefreshTask`, and two hand-written
     * copies would drift.
     *
     * Nothing here throws. A background pass has no user watching it, so the
     * only thing it can usefully report is whether waking again *sooner* than
     * the next scheduled run would help — [BackgroundResult.shouldRetry].
     */
    suspend fun backgroundSync(): BackgroundResult {
        // Per-feed failures are already collected rather than thrown, so one
        // dead feed still leaves the rest refreshed and reconciled.
        val refresh = refreshAllFeeds()

        // Not configured is a *settled* state, not a failure. Retrying it would
        // put every phone that never signed in to gpodder.net into an endless
        // exponential backoff, waking the radio for a request that cannot
        // succeed until the user types a password.
        if (!accountConfigured()) {
            return BackgroundResult(refresh.newEpisodes, refresh.failures, null, null)
        }

        return try {
            BackgroundResult(refresh.newEpisodes, refresh.failures, syncNow(), null)
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Exception) {
            BackgroundResult(
                refresh.newEpisodes, refresh.failures, null, exc.message ?: "sync failed",
            )
        }
    }

    /**
     * The outcome of one [backgroundSync].
     *
     * [sync] is null both when there is no account and when the sync failed;
     * [syncFailure] is what tells those apart, and only the second is worth
     * waking up again for.
     */
    data class BackgroundResult(
        val newEpisodes: Int,
        val feedFailures: List<String>,
        val sync: GpodderSync.Result?,
        val syncFailure: String?,
    ) {
        val shouldRetry: Boolean get() = syncFailure != null
    }

    private fun gpodderClient(): GpodderClient? {
        val account = credentials.load() ?: return null
        val existing = cachedClient
        if (existing != null && existing.username == account.username &&
            existing.server == account.server.trimEnd('/')
        ) {
            // Same account — keep the session cookie, but pick up a password
            // the user just corrected.
            existing.password = account.password
            return existing
        }
        return GpodderClient(
            username = account.username,
            password = account.password,
            http = httpClient,
            server = account.server,
            dryRun = dryRunSync,
            now = now,
        ).also { cachedClient = it }
    }

    /**
     * Playback's heartbeat, ported from the desktop's `hub.report_position`.
     *
     * Called every few seconds while playing, and once more with [final] on
     * pause, seek, stop and end of episode. Persisting happens every time; the
     * gpodder action and the nudge to peers happen only on a final report.
     *
     * The desktop also calls `note_position()` on every tick, which feeds its
     * 5-second position heartbeat to peers. Mobile drops that deliberately —
     * `docs/mobile-lan-sync.md` puts "playback tick" in the table as
     * **nothing** — so only the final report reaches the mesh.
     */
    fun reportPosition(episodeId: Long, position: Long, total: Long, final: Boolean) {
        val episode = repo.episodeById(episodeId) ?: return

        if (total > 0 && total != episode.total_secs) {
            if (position > 0) {
                repo.db.episodesQueries.setEpisodePosition(position, total, now(), episodeId)
            } else {
                repo.db.episodesQueries.setEpisodeTotal(total, episodeId)
            }
        } else if (position > 0) {
            repo.db.episodesQueries.setEpisodePositionOnly(position, now(), episodeId)
        }

        if (!final || position <= 0) return

        val podcast = repo.podcastById(episode.podcast_id)
        if (podcast != null) {
            repo.enqueueAction(
                podcastUrl = podcast.feed_url,
                episodeUrl = episode.media_url,
                action = "play",
                started = position,
                position = position,
                total = total.takeIf { it > 0 },
            )
            _syncRequested.tryEmit(Unit)
        }
        lan.onTransportEvent(episodeId)
    }

    /** A peer's snapshot or position push landed; rebuild the derived queue. */
    fun onPeerStateMerged() {
        queue.reconcile()
    }

    /** Start the peer mesh. The platform still has to feed it path and
     *  lifecycle events — see docs/mobile-lan-sync.md. */
    fun start() {
        lan.start()
    }

    fun stop() {
        lan.stop()
    }

    companion object {
        const val SYNC_DEBOUNCE_MS = 10_000L

        /**
         * How often a background pass should run, matching the desktop's
         * `REFRESH_INTERVAL_MS` in `feeds/refresher.py`. Each platform asks its
         * own scheduler for this; neither gets to pick a different number.
         */
        const val BACKGROUND_INTERVAL_SECONDS = 3600L
    }
}
