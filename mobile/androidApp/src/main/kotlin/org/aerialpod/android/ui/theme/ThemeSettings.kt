package org.aerialpod.android.ui.theme

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.aerialpod.core.db.Repo

/**
 * Appearance, read from and written to the same `app_state` rows the desktop
 * uses.
 *
 * Loaded asynchronously and seeded with the defaults, because the alternative
 * is a database read on the main thread before the first frame — which on a
 * cold start is also the read that runs the migrations.
 */
class ThemeSettings(
    private val repo: Repo,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _prefs = MutableStateFlow(ThemePrefs())
    val prefs: StateFlow<ThemePrefs> = _prefs.asStateFlow()

    init {
        scope.launch(io) {
            _prefs.value = ThemePrefs(
                mode = ThemeMode.fromState(repo.stateString(STATE_THEME_MODE, ThemeMode.SYSTEM.stateValue)),
                accent = repo.stateString(STATE_ACCENT, DEFAULT_ACCENT),
            )
        }
    }

    fun setMode(mode: ThemeMode) {
        _prefs.value = _prefs.value.copy(mode = mode)
        scope.launch(io) { repo.setState(STATE_THEME_MODE, mode.stateValue) }
    }

    fun setAccent(hex: String) {
        _prefs.value = _prefs.value.copy(accent = hex)
        scope.launch(io) { repo.setState(STATE_ACCENT, hex) }
    }
}
