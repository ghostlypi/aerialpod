package org.aerialpod.android.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The bar's five tabs, mirroring the desktop's sidebar. Detail routes live in
 * [Routes] — they are pushed on top of whichever tab opened them, and the bar
 * hides while one is showing.
 */
enum class TopLevel(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "Home", Icons.Outlined.Home),
    QUEUE("queue", "Queue", Icons.AutoMirrored.Outlined.PlaylistPlay),
    INBOX("inbox", "Inbox", Icons.Outlined.Inbox),
    PODCASTS("podcasts", "Podcasts", Icons.Outlined.Podcasts),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings);

    companion object {
        val START = HOME
    }
}

/** Detail routes, which sit on top of whichever tab opened them. */
object Routes {
    const val ARG_PODCAST = "podcastId"
    const val ARG_EPISODE = "episodeId"
    const val PODCAST_PATTERN = "podcast/{$ARG_PODCAST}"
    const val EPISODE_PATTERN = "episode/{$ARG_EPISODE}"

    const val PLAYER = "player"

    fun podcast(id: Long) = "podcast/$id"
    fun episode(id: Long) = "episode/$id"
}
