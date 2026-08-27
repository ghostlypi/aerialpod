package org.aerialpod.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.aerialpod.android.downloads.Downloads
import org.aerialpod.android.sync.BackgroundSync
import org.aerialpod.android.platform.NetworkMonitor
import org.aerialpod.android.platform.PeerLifecycle
import org.aerialpod.android.platform.PeerTriggers
import org.aerialpod.android.playback.PlayerController
import org.aerialpod.android.platform.PlaybackSignals
import org.aerialpod.android.store.KeystoreGpodderCredentialStore
import org.aerialpod.android.store.KeystoreSecretStore
import org.aerialpod.android.ui.theme.ThemeSettings
import org.aerialpod.core.AerialPodCore
import org.aerialpod.core.db.AndroidDriverFactory
import org.aerialpod.core.db.openDatabase

/**
 * The app's singletons, in one place and created once per process.
 *
 * Not a DI framework. [AerialPodCore] is already the composition root for
 * everything that matters — the queue, the peer mesh, gpodder, feeds — and it
 * assembles itself. What is left for the app is the four things the core cannot
 * know: where the database file lives, which HTTP engine to use, where secrets
 * are kept, and what this device is called. A container to express that would
 * be more machinery than the thing it wires.
 *
 * Everything is `lazy`, so nothing touches the disk until something asks. In
 * particular constructing the graph does not open the database; [warmUp] does
 * that off the main thread.
 */
class AppGraph(private val app: Application) {

    /**
     * Process-lifetime scope. A [SupervisorJob] because these are independent
     * long-running collectors: a gpodder sync failing must not take the peer
     * mesh down with it.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val secretPrefs: SharedPreferences by lazy {
        app.getSharedPreferences(SECRETS_PREFS, Context.MODE_PRIVATE)
    }

    val secrets by lazy { KeystoreSecretStore(secretPrefs) }

    val credentials by lazy { KeystoreGpodderCredentialStore(secretPrefs) }

    val http: HttpClient by lazy {
        HttpClient(OkHttp) {
            // No User-Agent here on purpose: FeedFetcher and GpodderClient each
            // set their own per request, matching what the desktop sends.
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                requestTimeoutMillis = 60_000
                socketTimeoutMillis = 30_000
            }
        }
    }

    val core: AerialPodCore by lazy {
        AerialPodCore(
            database = openDatabase(AndroidDriverFactory(app)),
            secretStore = secrets,
            credentials = credentials,
            httpClient = http,
            deviceCaption = deviceCaption(),
            scope = scope,
            ioDispatcher = Dispatchers.IO,
        )
    }

    val repo get() = core.repo

    val theme: ThemeSettings by lazy { ThemeSettings(repo, scope, Dispatchers.IO) }

    val actions: AppActions by lazy {
        AppActions(core, credentials, scope) { Downloads.request(app) }
    }

    /** How playback reports to the peer mesh. */
    val playback: PlaybackSignals by lazy {
        PlaybackSignals(object : PeerTriggers {
            override fun onPlaybackStarted() = core.lan.onPlaybackStarted()
            override fun onTransportEvent(episodeId: Long?) = core.lan.onTransportEvent(episodeId)
        })
    }

    val player: PlayerController by lazy { PlayerController(app, core, scope) }

    private val network: NetworkMonitor by lazy {
        NetworkMonitor(app) { core.lan.onPathChanged() }
    }

    private val peerLifecycle: PeerLifecycle by lazy {
        PeerLifecycle(core, playback, network)
    }

    val library get() = core.library

    /**
     * Open the database off the main thread, and hand the peer service its
     * lifecycle.
     *
     * The first query is the one that runs the migrations, so doing it here
     * means the first screen is not the thing that waits for them.
     *
     * Attaching the lifecycle observer is what actually starts device sync:
     * `PeerLifecycle.onStart` calls `core.start()` on the first foreground. It
     * is not started here directly, because a service running with no path or
     * lifecycle callbacks would connect once and never reconnect.
     */
    fun warmUp() {
        peerLifecycle.attach()
        scope.launch(Dispatchers.IO) {
            runCatching { repo.lanDeviceId() }
            // Catch up on whatever the policy wants now — the queue may have
            // moved while the app was not running, most obviously because a
            // peer's snapshot landed on the last run.
            Downloads.request(app)
        }
        scope.launch {
            core.queueChanged.collect { Downloads.request(app) }
        }
        // Idempotent, and cheap enough to re-assert on every start: it is what
        // repairs the schedule after an app update or a force-stop, both of
        // which leave the app looking fine while quietly never syncing again.
        BackgroundSync.schedule(app)
    }

    /**
     * What peers call this device.
     *
     * The desktop sends its hostname; a phone's hostname is a meaningless
     * `localhost` or a random string, so the marketing name is used instead —
     * it is what the user would call the device themselves.
     */
    private fun deviceCaption(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val caption = when {
            model.isEmpty() -> manufacturer
            manufacturer.isEmpty() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        return caption.ifEmpty { "Android device" }
    }

    private companion object {
        const val SECRETS_PREFS = "aerialpod_secrets"
    }
}
