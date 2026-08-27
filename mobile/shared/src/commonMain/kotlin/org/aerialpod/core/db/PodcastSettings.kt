package org.aerialpod.core.db

/**
 * The replicated per-podcast settings, as a value the caller can `copy()`.
 *
 * Exists so a local edit can change one field without restating the others —
 * see [Repo.updatePodcastSettings]. Adding a setting means touching this, the
 * schema, and `SettingsRecord` on the wire; the desktop drives all three from
 * `repo.SETTING_KEYS` instead, which is the one place the two implementations
 * genuinely diverge.
 */
data class PodcastSettings(
    val customTitle: String? = null,
    val playbackSpeed: Double? = null,
    val skipIntroSecs: Long? = null,
    val skipOutroSecs: Long? = null,
    val autoAddToQueue: Long? = null,
    val autoQueuePosition: String? = null,
) {
    companion object {
        fun from(row: Podcast_settings?): PodcastSettings = PodcastSettings(
            customTitle = row?.custom_title,
            playbackSpeed = row?.playback_speed,
            skipIntroSecs = row?.skip_intro_secs,
            skipOutroSecs = row?.skip_outro_secs,
            autoAddToQueue = row?.auto_add_to_queue,
            autoQueuePosition = row?.auto_queue_position,
        )
    }
}
