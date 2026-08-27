package org.aerialpod.android.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.aerialpod.core.AerialPodCore
import java.io.File

/**
 * One episode as a `MediaItem`.
 *
 * The episode id travels as the `mediaId`, which is what lets the coordinator
 * and the notification work out what is playing without holding state of their
 * own — the player is the single source of truth for "current episode".
 *
 * A downloaded file wins over the stream URL, matching the desktop's
 * `play_episode`, and the existence check matters: a `download_state` of `done`
 * with the file since deleted would otherwise fail to open with no fallback.
 *
 * Must be called off the main thread — it reads the database.
 */
fun mediaItemFor(episodeId: Long, core: AerialPodCore): MediaItem? {
    val episode = core.repo.episodeById(episodeId) ?: return null
    val podcast = core.repo.podcastById(episode.podcast_id)

    val local = episode.downloaded_path
        ?.takeIf { episode.download_state == "done" && File(it).exists() }
    val source = local ?: episode.media_url

    val metadata = MediaMetadata.Builder()
        .setTitle(episode.title ?: "(untitled)")
        .setArtist(podcast?.let { core.repo.displayTitle(it) })
        .setAlbumTitle(podcast?.let { core.repo.displayTitle(it) })
        .setArtworkUri((episode.image_url ?: podcast?.image_url)?.toUri())
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()

    return MediaItem.Builder()
        .setMediaId(episodeId.toString())
        .setUri(source.toUri())
        .setMediaMetadata(metadata)
        .build()
}
