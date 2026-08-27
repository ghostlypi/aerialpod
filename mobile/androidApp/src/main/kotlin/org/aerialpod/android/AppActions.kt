package org.aerialpod.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aerialpod.core.AerialPodCore
import org.aerialpod.core.gpodder.GpodderCredentials
import org.aerialpod.core.gpodder.GpodderCredentialStore
import org.aerialpod.core.lan.Pairing

/**
 * Everything the screens can do, in one place.
 *
 * Two reasons it exists rather than screens calling the core directly. Every
 * repo call is a synchronous SQLite call, so it must not happen on the main
 * thread — putting them all behind this makes that one decision instead of
 * dozens. And anything that can fail (a feed that 404s, a wrong gpodder
 * password) has to reach the user, so failures come back as [messages] rather
 * than dying in a coroutine nobody is watching.
 */
class AppActions(
    private val core: AerialPodCore,
    private val credentials: GpodderCredentialStore,
    private val scope: CoroutineScope,
    /** Ask for a download pass. Wired to WorkManager by the graph. */
    private val onDownloadsChanged: () -> Unit = {},
) {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // ------------------------------------------------------------------ queue

    fun toggleQueue(episodeId: Long) = bg { core.queue.toggle(episodeId) }

    fun addToQueue(episodeId: Long, toFront: Boolean = false) =
        bg { core.queue.add(episodeId, toFront) }

    fun removeFromQueue(episodeId: Long) = bg { core.queue.remove(episodeId) }

    fun moveInQueue(episodeId: Long, newIndex: Int) = bg { core.queue.move(episodeId, newIndex) }

    fun pin(episodeId: Long) = bg { core.queue.pin(episodeId) }

    fun releaseToAuto(episodeId: Long) = bg { core.queue.releaseToAuto(episodeId) }

    fun markPlayed(episodeId: Long) = bg { core.queue.markPlayedAndAdvance(episodeId) }

    fun markUnplayed(episodeId: Long) = bg { core.queue.markUnplayed(episodeId) }

    // ------------------------------------------------------------ library

    fun addPodcast(feedUrl: String) = busyBg("Adding feed…") {
        val id = core.addPodcast(feedUrl)
        val podcast = core.repo.podcastById(id)
        val title = podcast?.let { core.repo.displayTitle(it) } ?: feedUrl
        "Subscribed to $title"
    }

    fun unsubscribe(podcastId: Long) = bg {
        core.repo.unsubscribePodcast(podcastId)
        core.queue.reconcile()
    }

    fun refreshAll() = busyBg("Refreshing feeds…") {
        val result = core.refreshAllFeeds()
        when {
            result.failures.isEmpty() && result.newEpisodes == 0 -> "No new episodes."
            result.failures.isEmpty() -> "${result.newEpisodes} new episode(s)."
            else -> "${result.newEpisodes} new, ${result.failures.size} feed(s) failed."
        }
    }

    fun refreshPodcast(podcastId: Long) = busyBg("Refreshing…") {
        val fresh = core.refreshFeed(podcastId)
        if (fresh > 0) "$fresh new episode(s)." else "No new episodes."
    }

    fun importOpml(document: String) = busyBg("Importing…") {
        val result = core.importOpml(document)
        if (result.failures.isEmpty()) {
            "Imported. ${result.newEpisodes} episode(s) fetched."
        } else {
            "Imported, but ${result.failures.size} feed(s) failed."
        }
    }

    suspend fun exportOpml(): String = withContext(Dispatchers.IO) { core.exportOpml() }

    // ------------------------------------------------------------ downloads

    /**
     * Pin or unpin a download.
     *
     * Pinned means the policy never evicts it — the user saying "I am taking
     * this on a plane". Unpinning does not delete anything; the next policy
     * pass decides, and it may well still be inside the window.
     */
    fun setKeepDownload(episodeId: Long, keep: Boolean) = bg {
        core.repo.setKeepDownload(episodeId, keep)
        onDownloadsChanged()
    }

    /** Skip forward/back length, on the same `app_state` keys the desktop uses. */
    fun setSkipSeconds(key: String, seconds: Long) = bg {
        core.repo.setState(key, seconds)
    }

    fun setDownloadAhead(choice: String) = bg {
        core.repo.setDownloadAhead(choice)
        onDownloadsChanged()
    }

    // ------------------------------------------------------------ device sync

    /**
     * Adopt another device's pairing code, then dial.
     *
     * Returns null on success, or why the code was rejected. The parse is pure
     * and runs here so the dialog can show the error inline; the keystore write
     * and the dial are I/O and go to the background.
     *
     * The dial is fired from here rather than at launch, which is what the spec
     * asks for: on iOS the local-network prompt then arrives with visible
     * context instead of ambushing the user at first start.
     */
    fun pairWithCode(code: String): String? {
        runCatching { Pairing.parseCode(code) }
            .onFailure { return it.message ?: "That code is not valid." }
        bg {
            core.pairing.pairWithCode(code)
            core.lan.dialNow()
        }
        return null
    }

    fun addPeerAddress(address: String, port: Int) {
        core.lan.addManualPeer(address.trim(), port)
    }

    fun dialPeersNow() {
        core.lan.dialNow()
    }

    /**
     * A fresh pairing code, which unpairs every device this one was paired with.
     */
    fun resetPairing() = bg { core.pairing.reset() }

    // ------------------------------------------------------------ settings

    fun setPodcastAutoAdd(podcastId: Long, value: Long?) = bg {
        core.repo.updatePodcastSettings(podcastId) { it.copy(autoAddToQueue = value) }
    }

    fun setPodcastQueuePosition(podcastId: Long, value: String?) = bg {
        core.repo.updatePodcastSettings(podcastId) { it.copy(autoQueuePosition = value) }
    }

    fun setPodcastSpeed(podcastId: Long, value: Double?) = bg {
        core.repo.updatePodcastSettings(podcastId) { it.copy(playbackSpeed = value) }
    }

    fun setHomeSections(order: List<String>) = bg {
        core.repo.setState(STATE_HOME_SECTIONS, order)
    }

    fun saveAccount(username: String, password: String, server: String) =
        busyBg("Signing in…") {
            credentials.save(
                GpodderCredentials(username.trim(), password, server.trim().ifEmpty { GPODDER_DEFAULT })
            )
            val result = core.syncNow()
            "Signed in. ${result.applied} action(s) applied, " +
                "${result.subscriptionsAdded.size} subscription(s) added."
        }

    fun signOut() = bg { credentials.clear() }

    fun syncNow() = busyBg("Syncing…") {
        val result = core.syncNow()
        "Synced. ${result.applied} action(s) applied."
    }

    // ------------------------------------------------------------ plumbing

    private fun bg(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching { block() }.onFailure { report(it) }
        }
    }

    /**
     * Run something slow, holding [busy] and reporting what happened either way.
     * The pending message goes out first so the user is not looking at a screen
     * that seems to have ignored them.
     */
    private fun busyBg(pending: String, block: suspend () -> String) {
        scope.launch(Dispatchers.IO) {
            _busy.value = true
            _messages.tryEmit(pending)
            runCatching { block() }
                .onSuccess { _messages.tryEmit(it) }
                .onFailure { report(it) }
            _busy.value = false
        }
    }

    /** Surface a message the UI itself produced — a file it could not read. */
    fun report(message: String) {
        _messages.tryEmit(message)
    }

    private fun report(error: Throwable) {
        _messages.tryEmit(error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong.")
    }

    companion object {
        const val STATE_HOME_SECTIONS = "home_sections"
        private const val GPODDER_DEFAULT = "https://gpodder.net"
    }
}
