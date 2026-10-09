package org.aerialpod.android.playback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aerialpod.core.ads.AdSpan

/**
 * Ad skipping, as the UI sees it.
 *
 * [PlaybackCoordinator] does the skipping, inside the service, so it works with
 * the screen off and in Android Auto. The screens only need to show what it is
 * doing and to send back one thing — an undo — and the service and the UI share
 * a process, so a plain holder in the app graph is all the plumbing that takes.
 */
class AdSkips {
    /** An automatic skip, for the "Skipped ad · Undo" snackbar. */
    data class Skipped(val episodeId: Long, val startMs: Long, val endMs: Long)

    private val _spans = MutableStateFlow<List<AdSpan>>(emptyList())
    /** Spans that will be skipped in what is loaded now — for shading the seek bar. */
    val spans: StateFlow<List<AdSpan>> = _spans.asStateFlow()

    private val _foundElsewhere = MutableStateFlow(false)
    /** Spans exist, but for a copy of a different length, so none apply here. */
    val foundElsewhere: StateFlow<Boolean> = _foundElsewhere.asStateFlow()

    private val _skipped = MutableSharedFlow<Skipped>(extraBufferCapacity = 4)
    val skipped: SharedFlow<Skipped> = _skipped.asSharedFlow()

    private val _undo = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val undoRequests: SharedFlow<Unit> = _undo.asSharedFlow()

    fun undo() {
        _undo.tryEmit(Unit)
    }

    internal fun publish(spans: List<AdSpan>, foundElsewhere: Boolean) {
        _spans.value = spans
        _foundElsewhere.value = foundElsewhere
    }

    internal fun announce(skipped: Skipped) {
        _skipped.tryEmit(skipped)
    }
}
