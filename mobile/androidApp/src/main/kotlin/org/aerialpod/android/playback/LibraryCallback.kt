package org.aerialpod.android.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.aerialpod.core.AerialPodCore
import org.aerialpod.core.db.Episodes
import java.util.concurrent.Executors

/**
 * The browse tree Android Auto (and any other media browser) shows, and how a
 * pick from it turns into playback.
 *
 * A pick plays the same way a tap on the phone does: the episode becomes the
 * player's single item and [PlaybackCoordinator] takes it from there — saved
 * position, per-podcast speed, skip intro, and advancing the queue at the end.
 * Browsing never touches the queue.
 *
 * Every database read runs on [io], never the main thread, which is where
 * Media3 calls these.
 */
class LibraryCallback(private val core: AerialPodCore) : MediaLibrarySession.Callback {

    private val io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun release() = io.shutdown()

    // ---------------------------------------------------------------- tree

    private companion object {
        const val ROOT = "root"
        const val QUEUE = "queue"
        const val IN_PROGRESS = "in_progress"
        const val NEW = "new"
        const val PODCASTS = "podcasts"
        const val PODCAST_PREFIX = "podcast:"

        const val SKIP_BACK = "org.aerialpod.SKIP_BACK"
        const val SKIP_FORWARD = "org.aerialpod.SKIP_FORWARD"

        // Auto shows at most four tabs at the root; this is all of them.
        val TABS = listOf(
            QUEUE to "Queue",
            IN_PROGRESS to "In progress",
            NEW to "New",
            PODCASTS to "Podcasts",
        )
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val extras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            )
        }
        val root = folder(ROOT, "AerialPod", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        return Futures.immediateFuture(
            LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build())
        )
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = io.submit<LibraryResult<ImmutableList<MediaItem>>> {
        val children = children(parentId)
            ?: return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        LibraryResult.ofItemList(page(children, page, pageSize), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = io.submit<LibraryResult<MediaItem>> {
        val item = mediaId.toLongOrNull()?.let { mediaItemFor(it, core) }
            ?: return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        LibraryResult.ofItem(item, null)
    }

    /** One page of [items]. Long arithmetic: legacy browsers ask for Int.MAX_VALUE. */
    private fun page(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        val from = (page.toLong() * pageSize).coerceIn(0, items.size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceIn(from.toLong(), items.size.toLong()).toInt()
        return items.subList(from, to)
    }

    private fun children(parentId: String): List<MediaItem>? {
        val repo = core.repo
        return when {
            parentId == ROOT -> TABS.map { (id, title) ->
                folder(id, title, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
            }
            parentId == QUEUE -> episodeItems(repo.queueEpisodes())
            parentId == IN_PROGRESS -> episodeItems(repo.inProgressEpisodes(limit = 100))
            parentId == NEW -> episodeItems(repo.inboxEpisodes(limit = 100))
            parentId == PODCASTS -> {
                val display = repo.podcastDisplayInfo()
                repo.subscribedPodcasts().map { p ->
                    folder(
                        "$PODCAST_PREFIX${p.id}",
                        display[p.id]?.title ?: repo.displayTitle(p),
                        MediaMetadata.MEDIA_TYPE_PODCAST,
                        artwork = p.image_url,
                    )
                }
            }
            parentId.startsWith(PODCAST_PREFIX) -> {
                val id = parentId.removePrefix(PODCAST_PREFIX).toLongOrNull() ?: return null
                episodeItems(repo.episodesForPodcast(id, limit = 100))
            }
            else -> null
        }
    }

    private fun folder(id: String, title: String, type: Int, artwork: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .setArtworkUri(artwork?.toUri())
                    .build()
            )
            .build()

    /**
     * Browse rows only — no stream URI. The player gets the full item from
     * [mediaItemFor] when one is picked, so the downloaded-file check happens at
     * play time rather than when the list was drawn.
     */
    private fun episodeItems(episodes: List<Episodes>): List<MediaItem> {
        val display = core.repo.podcastDisplayInfo()
        return episodes.map { ep ->
            val podcast = display[ep.podcast_id]
            val total = if (ep.total_secs > 0) ep.total_secs else ep.duration_secs ?: 0L
            val extras = Bundle().apply {
                when {
                    ep.state == "played" -> putInt(
                        MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                        MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
                    )
                    ep.position_secs > 0 && total > 0 -> {
                        putInt(
                            MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
                        )
                        putDouble(
                            MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                            (ep.position_secs.toDouble() / total).coerceIn(0.0, 1.0),
                        )
                    }
                    else -> putInt(
                        MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                        MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED,
                    )
                }
            }
            MediaItem.Builder()
                .setMediaId(ep.id.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(ep.title ?: "(untitled)")
                        .setArtist(podcast?.title)
                        .setArtworkUri((ep.image_url ?: podcast?.imageUrl)?.toUri())
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                        .setExtras(extras)
                        .build()
                )
                .build()
        }
    }

    // ---------------------------------------------------------------- playing

    /**
     * A pick from the tree, or a voice request ("play <show> on AerialPod").
     *
     * Only the first item counts: the player holds one episode at a time and
     * the queue decides what follows, exactly as on the phone.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = io.submit<MutableList<MediaItem>> {
        val requested = mediaItems.firstOrNull() ?: return@submit mutableListOf()
        val query = requested.requestMetadata.searchQuery
        val episodeId = when {
            requested.mediaId.isNotEmpty() -> requested.mediaId.toLongOrNull()
            query != null -> search(query).firstOrNull()?.id ?: resumeEpisode()?.id
            else -> null
        }
        listOfNotNull(episodeId?.let { mediaItemFor(it, core) }).toMutableList()
    }

    /**
     * Play pressed with nothing loaded — the car's play button right after
     * connecting, or a headset. Picks up where the queue says to.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = io.submit<MediaSession.MediaItemsWithStartPosition> {
        val item = resumeEpisode()?.let { mediaItemFor(it.id, core) }
            ?: throw UnsupportedOperationException("nothing to resume")
        // C.TIME_UNSET: the coordinator seeks to the saved position itself.
        MediaSession.MediaItemsWithStartPosition(listOf(item), 0, C.TIME_UNSET)
    }

    private fun resumeEpisode(): Episodes? =
        core.repo.queueEpisodes().firstOrNull() ?: core.repo.inProgressEpisodes(limit = 1).firstOrNull()

    // ---------------------------------------------------------------- search

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        io.execute {
            val count = search(query).size
            mainHandler.post { session.notifySearchResultChanged(browser, query, count, params) }
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = io.submit<LibraryResult<ImmutableList<MediaItem>>> {
        val results = episodeItems(search(query))
        LibraryResult.ofItemList(page(results, page, pageSize), params)
    }

    /**
     * Episodes matching [query], best first.
     *
     * A show's name is what people say to a car ("play Tech News"), so a
     * podcast match comes first and yields its newest unplayed episode; then
     * episodes whose own title matches.
     */
    private fun search(query: String): List<Episodes> {
        val q = query.trim()
        if (q.isEmpty()) return listOfNotNull(resumeEpisode())
        val repo = core.repo
        val podcasts = repo.subscribedPodcasts()
        val byShow = podcasts
            .filter { repo.displayTitle(it).contains(q, ignoreCase = true) }
            .mapNotNull { p ->
                val episodes = repo.episodesForPodcast(p.id, limit = 50)
                episodes.firstOrNull { it.state != "played" } ?: episodes.firstOrNull()
            }
        val byTitle = podcasts
            .flatMap { repo.episodesForPodcast(it.id, limit = 50) }
            .filter { it.title?.contains(q, ignoreCase = true) == true }
            .sortedByDescending { it.pub_date ?: 0L }
        return (byShow + byTitle).distinctBy { it.id }.take(50)
    }

    // ---------------------------------------------------------------- skip buttons

    private val skipBack = SessionCommand(SKIP_BACK, Bundle.EMPTY)
    private val skipForward = SessionCommand(SKIP_FORWARD, Bundle.EMPTY)

    /**
     * Skip back and forward in place of previous and next. The player holds
     * one episode, so previous/next would have nothing to do — and a relative
     * skip is what a podcast listener reaches for while driving.
     */
    val mediaButtons: List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName("Skip back")
            .setSessionCommand(skipBack)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName("Skip forward")
            .setSessionCommand(skipForward)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

    /**
     * Built from AcceptedResultBuilder's defaults, never from super.onConnect():
     * in Media3 1.11 that returns a placeholder with *empty* command sets that
     * the library swaps for the real defaults only if it is returned untouched.
     * Copying its commands grants nothing — Android Auto is then refused the
     * library root and spins forever, and the app's own controller loses play.
     */
    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val defaults = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon().add(skipBack).add(skipForward).build()
        return defaults
            .setAvailableSessionCommands(commands)
            .setMediaButtonPreferences(mediaButtons)
            .build()
    }

    /** The same skip amounts the phone's player uses, read fresh each time. */
    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val forward = when (customCommand.customAction) {
            SKIP_FORWARD -> true
            SKIP_BACK -> false
            else -> return super.onCustomCommand(session, controller, customCommand, args)
        }
        io.execute {
            val secs = if (forward) core.repo.stateLong("skip_fwd_secs", 30L)
            else -core.repo.stateLong("skip_back_secs", 10L)
            mainHandler.post {
                val player = session.player
                player.seekTo((player.currentPosition + secs * 1000).coerceAtLeast(0))
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
}
