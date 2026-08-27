package org.aerialpod.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.aerialpod.android.AppGraph
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.aerialpod.android.ui.components.MiniPlayer
import org.aerialpod.android.ui.nav.Routes
import org.aerialpod.android.ui.nav.TopLevel
import org.aerialpod.android.ui.screens.EpisodeDetailScreen
import org.aerialpod.android.ui.screens.HomeScreen
import org.aerialpod.android.ui.screens.InboxScreen
import org.aerialpod.android.ui.screens.PodcastDetailScreen
import org.aerialpod.android.ui.screens.PlayerScreen
import org.aerialpod.android.ui.screens.PodcastsScreen
import org.aerialpod.android.ui.screens.QueueScreen
import org.aerialpod.android.ui.screens.SettingsScreen

@Composable
fun AerialPodApp(graph: AppGraph) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val playerState by graph.player.state.collectAsStateWithLifecycle()

    // Connecting binds the media session, which is also what restores the bar
    // after the app is reopened while something is still playing.
    LaunchedEffect(graph) { graph.player.connect() }

    // Anything slow or fallible reports here — a feed that 404s or a wrong
    // gpodder password has to be visible, not swallowed by a coroutine.
    LaunchedEffect(graph) {
        graph.actions.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Hidden on detail screens, which get their own back arrow.
            if (TopLevel.entries.any { it.route == currentRoute }) {
                Column {
                MiniPlayer(
                    state = playerState,
                    onToggle = { graph.player.togglePlayPause() },
                    onOpen = { navController.navigate(Routes.PLAYER) },
                )
                NavigationBar {
                    TopLevel.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.switchTab(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevel.START.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevel.HOME.route) {
                HomeScreen(
                    graph = graph,
                    onOpenEpisode = { navController.navigate(Routes.episode(it)) },
                    onOpenPodcast = { navController.navigate(Routes.podcast(it)) },
                    onSeeAll = { key ->
                        val route = when (key) {
                            "queue" -> TopLevel.QUEUE.route
                            "inbox" -> TopLevel.INBOX.route
                            else -> TopLevel.PODCASTS.route
                        }
                        navController.switchTab(route)
                    },
                )
            }
            composable(TopLevel.QUEUE.route) {
                QueueScreen(graph) { navController.navigate(Routes.episode(it)) }
            }
            composable(TopLevel.INBOX.route) {
                InboxScreen(graph) { navController.navigate(Routes.episode(it)) }
            }
            composable(TopLevel.PODCASTS.route) {
                PodcastsScreen(graph) { navController.navigate(Routes.podcast(it)) }
            }
            composable(TopLevel.SETTINGS.route) {
                SettingsScreen(graph)
            }

            composable(Routes.PLAYER) {
                PlayerScreen(graph) { navController.popBackStack() }
            }

            composable(
                route = Routes.PODCAST_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_PODCAST) { type = NavType.LongType }),
            ) { entry ->
                PodcastDetailScreen(
                    graph = graph,
                    podcastId = entry.arguments?.getLong(Routes.ARG_PODCAST) ?: 0L,
                    onBack = { navController.popBackStack() },
                    onOpenEpisode = { navController.navigate(Routes.episode(it)) },
                )
            }
            composable(
                route = Routes.EPISODE_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_EPISODE) { type = NavType.LongType }),
            ) { entry ->
                EpisodeDetailScreen(
                    graph = graph,
                    episodeId = entry.arguments?.getLong(Routes.ARG_EPISODE) ?: 0L,
                    onBack = { navController.popBackStack() },
                    onOpenPodcast = { navController.navigate(Routes.podcast(it)) },
                )
            }
        }
    }
}

/**
 * Move between tabs without stacking them.
 *
 * Tapping through all five and then pressing back should leave the app, not
 * replay the tour — and returning to a tab should find it where it was left.
 */
private fun NavHostController.switchTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
