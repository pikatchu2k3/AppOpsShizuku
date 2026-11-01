package de.minipc.appopsshizuku

import de.minipc.appopsshizuku.ui.CardColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardColorsTest {

    @Test
    fun weisseSchriftAufJederKartenfarbeLesbar() {
        // Viele Farbtoene, Saettigungen, Helligkeiten - auch sehr helle Icons (Gelb, Weiss)
        val seeds = mutableListOf<Int?>(null, 0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF000000.toInt())
        for (h in 0 until 360 step 15) for (v in listOf(0.4f, 0.7f, 1f)) seeds += CardColors.fromHsv(h.toFloat(), 0.8f, v)
        for (seed in seeds) {
            val c = CardColors.cardStart(seed)
            assertTrue("Kontrast fuer ${seed?.let { Integer.toHexString(it) }}", CardColors.contrast(c, CardColors.WHITE) >= CardColors.MIN_CONTRAST)
        }
        assertTrue(CardColors.contrast(CardColors.MID, CardColors.WHITE) >= 7.0)
        assertTrue(CardColors.contrast(CardColors.END, CardColors.WHITE) >= 7.0)
    }

    @Test
    fun hauptfarbeAusPixeln() {
        val red = 0xFFE53935.toInt()
        val px = IntArray(100) { if (it < 70) red else 0x00000000 } // Rest transparent
        val dom = CardColors.dominant(px)!!
        val h = CardColors.toHsv(dom)[0]
        assertTrue("Farbton rot erwartet, war $h", h < 15f || h > 345f)
        // Nur Grau/Weiss -> keine Hauptfarbe
        assertNull(CardColors.dominant(IntArray(50) { 0xFFCCCCCC.toInt() }))
    }

    @Test
    fun hsvHinUndZurueck() {
        for (c in listOf(0xFF336699.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt())) {
            val hsv = CardColors.toHsv(c)
            val back = CardColors.fromHsv(hsv[0], hsv[1], hsv[2])
            for (shift in listOf(16, 8, 0)) {
                assertTrue(Math.abs(((c shr shift) and 0xFF) - ((back shr shift) and 0xFF)) <= 2)
            }
        }
        assertEquals(0xFF, CardColors.fromHsv(0f, 0f, 1f) and 0xFF)
    }
}
