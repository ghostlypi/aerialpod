package org.aerialpod.android.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import org.aerialpod.core.db.Library

/**
 * Long-press drag reordering for a queue list.
 *
 * Shared by the Queue screen and Home's queue section rather than written
 * twice — the two differ only in how much of the queue they show, and a second
 * copy of this would drift.
 *
 * The reorder is optimistic. The visible order changes the moment an item
 * crosses a neighbour, but `queue.move()` is called once, on drop: calling it
 * per crossing would record an intent, push a debounced snapshot to every peer
 * and reconcile the whole queue every few pixels of finger movement.
 *
 * Home shows only the first few rows, which is safe because that preview is the
 * top of the same order — an index within it is the same index in the queue.
 */
@Stable
class QueueReorder internal constructor(
    private val listState: LazyListState,
    private val keyOf: (Library.EpisodeRow) -> Any,
    private val onMove: (episodeId: Long, newIndex: Int) -> Unit,
) {
    internal var localOrder by mutableStateOf<List<Library.EpisodeRow>?>(null)
    private var draggingId by mutableStateOf<Long?>(null)
    private var startIndex = 0
    private var currentIndex = 0

    // A float state, not a boxed one: this changes on every pointer event of a
    // drag, and each write to a generic state allocates an Float object.
    var offsetY by mutableFloatStateOf(0f)
        private set

    /**
     * Snapshot state, not a plain field.
     *
     * [rows] is read inside a `LazyColumn` content lambda, which Compose is
     * free to skip re-running when nothing it observes has changed. A plain
     * field is not observed, so the list stayed at whatever it held on first
     * composition — an empty queue, because the flow had not emitted yet.
     */
    internal var stored: List<Library.EpisodeRow> by mutableStateOf(emptyList())

    /** The order to draw: the local one while dragging, the stored one after. */
    val rows: List<Library.EpisodeRow> get() = localOrder ?: stored

    fun isDragging(row: Library.EpisodeRow): Boolean = draggingId == row.episode.id

    /** Put this on the drag handle, not the row: a whole-row drag would fight the scroll. */
    fun Modifier.dragHandle(row: Library.EpisodeRow): Modifier = pointerInput(row.episode.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { begin(row) },
            onDrag = { change, amount ->
                change.consume()
                drag(amount.y)
            },
            onDragEnd = { finish() },
            onDragCancel = { cancel() },
        )
    }

    private fun begin(row: Library.EpisodeRow) {
        val current = rows
        val index = current.indexOfFirst { it.episode.id == row.episode.id }
        if (index < 0) return
        localOrder = current
        draggingId = row.episode.id
        startIndex = index
        currentIndex = index
        offsetY = 0f
    }

    private fun drag(delta: Float) {
        val current = localOrder ?: return
        if (draggingId == null) return
        val moved = offsetY + delta
        val height = rowHeight(current)
        when {
            moved > height / 2 && currentIndex < current.lastIndex -> {
                localOrder = current.swapped(currentIndex, currentIndex + 1)
                currentIndex += 1
                offsetY = moved - height
            }
            moved < -height / 2 && currentIndex > 0 -> {
                localOrder = current.swapped(currentIndex, currentIndex - 1)
                currentIndex -= 1
                offsetY = moved + height
            }
            else -> offsetY = moved
        }
    }

    private fun finish() {
        val id = draggingId
        draggingId = null
        offsetY = 0f
        if (id != null && currentIndex != startIndex) {
            // Held until the database emits the new order — clearing it here
            // would show the old one for a frame.
            onMove(id, currentIndex)
        } else {
            localOrder = null
        }
    }

    private fun cancel() {
        draggingId = null
        offsetY = 0f
        localOrder = null
    }

    internal fun idle(): Boolean = draggingId == null

    /**
     * The measured height of the row being dragged, found by key rather than
     * index: on Home this list also holds section headers and a podcast strip,
     * so a row's position in the lazy list is not its position in the queue.
     */
    private fun rowHeight(current: List<Library.EpisodeRow>): Float {
        val row = current.getOrNull(currentIndex) ?: return FALLBACK_ROW_HEIGHT
        val key = keyOf(row)
        return listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == key }?.size?.toFloat()
            ?: FALLBACK_ROW_HEIGHT
    }

    private companion object {
        /** Only reachable for a row scrolled out of view, which a finger cannot be on. */
        const val FALLBACK_ROW_HEIGHT = 200f
    }
}

private fun <T> List<T>.swapped(a: Int, b: Int): List<T> =
    toMutableList().also { it[a] = this[b]; it[b] = this[a] }

@Composable
fun rememberQueueReorder(
    stored: List<Library.EpisodeRow>,
    listState: LazyListState,
    keyOf: (Library.EpisodeRow) -> Any,
    onMove: (episodeId: Long, newIndex: Int) -> Unit,
): QueueReorder {
    val reorder = remember(listState) { QueueReorder(listState, keyOf, onMove) }
    reorder.stored = stored
    // The database has caught up — or reconcile decided otherwise. Either way
    // its answer is the authoritative one from here.
    LaunchedEffect(stored) { if (reorder.idle()) reorder.localOrder = null }
    return reorder
}
