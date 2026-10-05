package de.minipc.appopsshizuku

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Belegt die WCAG-Kontrastwerte der M3-Palette (siehe res/values/colors.xml
 * und res/values-night/colors.xml) rechnerisch, statt sie nur zu behaupten.
 * Ziel laut Bauauftrag: >= 4.5:1 fuer normalen Text, >= 3:1 fuer grosse
 * Schrift/Icons. Die Hex-Werte hier sind Kopien der Rollenwerte aus den
 * colors.xml-Dateien - bei einer Palettenaenderung muessen beide Stellen
 * zusammen aktualisiert werden.
 */
class PaletteContrastTest {

    private fun relativeLuminance(hex: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((hex shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = channel(16)
        val g = channel(8)
        val b = channel(0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG-Kontrastverhaeltnis zweier sRGB-Farben (0xRRGGBB), >= 1.0. */
    private fun contrast(c1: Long, c2: Long): Double {
        val l1 = relativeLuminance(c1)
        val l2 = relativeLuminance(c2)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private val minText = 4.5
    private val minLargeText = 3.0

    // ---- Hell (res/values/colors.xml) ----
    private val lightOnSurface = 0x151D1EL
    private val lightSurface = 0xF0FBFDL
    private val lightOnSurfaceVariant = 0x374A4DL
    private val lightSurfaceVariant = 0xD0E7EBL
    private val lightOnPrimary = 0xFFFFFFL
    private val lightPrimary = 0x036874L
    private val lightOnPrimaryContainer = 0x011F24L
    private val lightPrimaryContainer = 0x99F0FFL
    private val lightOnTertiaryContainer = 0x001C37L
    private val lightTertiaryContainer = 0xD6E3FFL
    private val lightOnErrorContainer = 0x410E0BL
    private val lightErrorContainer = 0xF9DEDCL
    private val lightOnWarningContainer = 0x271902L
    private val lightWarningContainer = 0xFFDDB7L

    // ---- Dunkel (res/values-night/colors.xml) ----
    private val darkOnSurface = 0xD9E4E6L
    private val darkSurface = 0x0B1516L
    private val darkOnSurfaceVariant = 0xB4CACEL
    private val darkSurfaceVariant = 0x374A4DL
    private val darkOnPrimary = 0x01363DL
    private val darkPrimary = 0x13DAF1L
    private val darkOnPrimaryContainer = 0x99F0FFL
    private val darkPrimaryContainer = 0x054E58L
    private val darkOnTertiaryContainer = 0xD6E3FFL
    private val darkTertiaryContainer = 0x194878L
    private val darkOnErrorContainer = 0xF9DEDCL
    private val darkErrorContainer = 0x8C1D18L
    private val darkOnWarningContainer = 0xFFDDB7L
    private val darkWarningContainer = 0x633F02L

    @Test
    fun hellErreichtMindestkontrastFuerText() {
        assertTrue("onSurface/surface", contrast(lightOnSurface, lightSurface) >= minText)
        assertTrue(
            "onSurfaceVariant/surfaceVariant",
            contrast(lightOnSurfaceVariant, lightSurfaceVariant) >= minText
        )
        assertTrue("onPrimary/primary", contrast(lightOnPrimary, lightPrimary) >= minLargeText)
        assertTrue(
            "onPrimaryContainer/primaryContainer",
            contrast(lightOnPrimaryContainer, lightPrimaryContainer) >= minText
        )
        assertTrue(
            "onTertiaryContainer/tertiaryContainer",
            contrast(lightOnTertiaryContainer, lightTertiaryContainer) >= minText
        )
        assertTrue(
            "onErrorContainer/errorContainer",
            contrast(lightOnErrorContainer, lightErrorContainer) >= minText
        )
        assertTrue(
            "onWarningContainer/warningContainer",
            contrast(lightOnWarningContainer, lightWarningContainer) >= minText
        )
    }

    @Test
    fun dunkelErreichtMindestkontrastFuerText() {
        assertTrue("onSurface/surface", contrast(darkOnSurface, darkSurface) >= minText)
        assertTrue(
            "onSurfaceVariant/surfaceVariant",
            contrast(darkOnSurfaceVariant, darkSurfaceVariant) >= minText
        )
        assertTrue("onPrimary/primary", contrast(darkOnPrimary, darkPrimary) >= minLargeText)
        assertTrue(
            "onPrimaryContainer/primaryContainer",
            contrast(darkOnPrimaryContainer, darkPrimaryContainer) >= minText
        )
        assertTrue(
            "onTertiaryContainer/tertiaryContainer",
            contrast(darkOnTertiaryContainer, darkTertiaryContainer) >= minText
        )
        assertTrue(
            "onErrorContainer/errorContainer",
            contrast(darkOnErrorContainer, darkErrorContainer) >= minText
        )
        assertTrue(
            "onWarningContainer/warningContainer",
            contrast(darkOnWarningContainer, darkWarningContainer) >= minText
        )
    }
}
