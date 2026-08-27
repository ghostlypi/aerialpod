package org.aerialpod.android.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

class PaletteTest {

    @Test
    fun parsesTheDesktopsAccentFormat() {
        assertEquals(0x3584E4, parseHexColor("#3584e4"))
        assertEquals(0x3584E4, parseHexColor("#3584E4"))
        assertEquals(0x3584E4, parseHexColor("  #3584e4  "))
        assertEquals(0x3584E4, parseHexColor("3584e4"))
    }

    @Test
    fun expandsThreeDigitShorthand() {
        // QColor accepts #rgb, so a hand-edited app_state row might hold one.
        assertEquals(0xFF0000, parseHexColor("#f00"))
        assertEquals(0xAABBCC, parseHexColor("#abc"))
    }

    @Test
    fun rejectsAnythingThatIsNotAColour() {
        // app_state is shared with the desktop and editable by hand. Every one
        // of these has to end as a fallback rather than an exception on the
        // path to the first frame.
        for (bad in listOf(null, "", "#", "#12345", "#1234567", "blue", "#gggggg", "#12 34 56")) {
            assertNull(parseHexColor(bad), "expected null for $bad")
        }
    }

    @Test
    fun aGarbageAccentFallsBackToTheDefaultRatherThanThrowing() {
        assertEquals(parseHexColor(DEFAULT_ACCENT), palette(dark = false, accent = "nonsense").accent)
        assertEquals(parseHexColor(DEFAULT_ACCENT), palette(dark = true, accent = "").accent)
    }

    @Test
    fun mixMatchesTheDesktopsRounding() {
        // theming.py rounds each channel independently after linear interpolation.
        // Python's round() is banker's and Math.round() is half-up, so the two
        // could differ by one on an exact .5 — which 0.15 and 0.85 weights do
        // not produce for any 8-bit pair. Worth knowing, not worth guarding.
        assertEquals(0x808080, mixColor(0x000000, 0xFFFFFF, 0.5))
        assertEquals(0x000000, mixColor(0x000000, 0xFFFFFF, 0.0))
        assertEquals(0xFFFFFF, mixColor(0x000000, 0xFFFFFF, 1.0))
        // 0x35 -> 53*0.85 + 255*0.15 = 45.05 + 38.25 = 83.3 -> 83 = 0x53
        assertEquals(0x5396E8, mixColor(0x3584E4, 0xFFFFFF, 0.15))
    }

    @Test
    fun accentHoverLightensInDarkAndDarkensInLight() {
        // The desktop mixes towards white on dark and towards black on light,
        // so a hover state stays visible against its own background.
        val dark = palette(dark = true, accent = DEFAULT_ACCENT)
        val light = palette(dark = false, accent = DEFAULT_ACCENT)
        assertEquals(0x5396E8, dark.accentHover)
        assertEquals(0x2D70C2, light.accentHover)
        assertNotEquals(dark.accentHover, light.accentHover)
    }

    @Test
    fun tokensMatchTheDesktopsPalette() {
        val dark = palette(dark = true, accent = DEFAULT_ACCENT)
        assertEquals(0x1E1E1E, dark.bg)
        assertEquals(0x2A2A2A, dark.surface)
        assertEquals(0xEEEEEC, dark.text)
        assertEquals(0xFF7B63, dark.danger)

        val light = palette(dark = false, accent = DEFAULT_ACCENT)
        assertEquals(0xFAFAFA, light.bg)
        assertEquals(0xF0F0EE, light.surface)
        assertEquals(0x2E3436, light.text)
        assertEquals(0xE01B24, light.danger)
    }

    @Test
    fun everyAccentPresetIsAColour() {
        // A typo in the preset list would silently paint that choice blue.
        val parsed = ACCENT_PRESETS.map { (name, hex) ->
            name to (parseHexColor(hex) ?: error("preset $name is not a colour: $hex"))
        }
        assertEquals(8, parsed.size)
        assertEquals(parsed.size, parsed.map { it.second }.toSet().size)
        assertEquals(0x3584E4, parsed.first().second)
    }

    @Test
    fun themeModeReadsTheDesktopsStateValues() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromState("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromState("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromState("dark"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromState(" Dark "))
        // theming.py's _dark() falls through to the system scheme for anything
        // that is neither 'light' nor 'dark'.
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromState(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromState("midnight"))
    }
}
