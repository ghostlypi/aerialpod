package org.aerialpod.android.ui.theme

/**
 * The desktop's palette, ported.
 *
 * `src/aerialpod/ui/theming.py` builds every colour from two inputs — dark or
 * light, and one accent hex — and the phone does the same, from the same two
 * `app_state` keys. A device that syncs its settings across should not change
 * colour when it does.
 *
 * Deliberately free of Compose types so it can be tested without an emulator;
 * `Theme.kt` is the only thing that turns these into a `ColorScheme`.
 */

/** repo.DEFAULTS["accent"] — GNOME blue. */
const val DEFAULT_ACCENT = "#3584e4"

const val STATE_ACCENT = "accent"
const val STATE_THEME_MODE = "theme_mode"

/** The desktop's accent menu, in its order (`ui/settings_page.py`). */
val ACCENT_PRESETS: List<Pair<String, String>> = listOf(
    "GNOME Blue" to "#3584e4",
    "Green" to "#2ec27e",
    "Orange" to "#e66100",
    "Red" to "#c01c28",
    "Purple" to "#813d9c",
    "Brown" to "#986a44",
    "Teal" to "#218787",
    "Pink" to "#d56199",
)

enum class ThemeMode(val stateValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        /** Anything unrecognised is 'system', which is what the desktop does
         *  by falling through both its equality checks. */
        fun fromState(value: String?): ThemeMode =
            entries.firstOrNull { it.stateValue == value?.trim()?.lowercase() } ?: SYSTEM
    }
}

data class ThemePrefs(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: String = DEFAULT_ACCENT,
)

/**
 * `#rrggbb` (or `#rgb`) to a packed 0xRRGGBB, or null if it is not a colour.
 *
 * Null rather than a throw because the source is `app_state`, which is shared
 * with the desktop and editable by hand: one bad row must not be the reason
 * the app cannot draw its first frame.
 */
fun parseHexColor(value: String?): Int? {
    val text = value?.trim()?.removePrefix("#") ?: return null
    val expanded = when (text.length) {
        3 -> buildString { for (ch in text) { append(ch); append(ch) } }
        6 -> text
        else -> return null
    }
    if (!expanded.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return expanded.toInt(16)
}

/** `_mix` from theming.py: `ratio` of the way from [a] towards [b]. */
fun mixColor(a: Int, b: Int, ratio: Double): Int {
    fun channel(shift: Int): Int {
        val from = (a shr shift) and 0xFF
        val to = (b shr shift) and 0xFF
        return Math.round(from * (1 - ratio) + to * ratio).toInt().coerceIn(0, 255)
    }
    return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** The token set `_palette()` returns, same names. */
data class Palette(
    val bg: Int,
    val surface: Int,
    val surfaceHover: Int,
    val text: Int,
    val textDim: Int,
    val border: Int,
    val accent: Int,
    val accentHover: Int,
    val onAccent: Int,
    val danger: Int,
)

fun palette(dark: Boolean, accent: String): Palette {
    val accentValue = parseHexColor(accent) ?: parseHexColor(DEFAULT_ACCENT)!!
    return if (dark) {
        Palette(
            bg = 0x1E1E1E,
            surface = 0x2A2A2A,
            surfaceHover = 0x333333,
            text = 0xEEEEEC,
            textDim = 0x9A9996,
            border = 0x3D3D3D,
            accent = accentValue,
            accentHover = mixColor(accentValue, 0xFFFFFF, 0.15),
            onAccent = 0xFFFFFF,
            danger = 0xFF7B63,
        )
    } else {
        Palette(
            bg = 0xFAFAFA,
            surface = 0xF0F0EE,
            surfaceHover = 0xE6E6E4,
            text = 0x2E3436,
            textDim = 0x77767B,
            border = 0xD5D5D3,
            accent = accentValue,
            accentHover = mixColor(accentValue, 0x000000, 0.15),
            onAccent = 0xFFFFFF,
            danger = 0xE01B24,
        )
    }
}
