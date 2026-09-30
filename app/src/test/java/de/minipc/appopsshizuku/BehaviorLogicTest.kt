package de.minipc.appopsshizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests fuer die ehrliche Zustandspruefung des Reiters "Verhalten".
 *
 * Hintergrund: `cmd deviceidle whitelist +<pkg>` meldet Erfolg, auch wenn nichts
 * passiert ist. Deshalb wird der Zustand danach aus `dumpsys deviceidle whitelist`
 * zurueckgelesen - dieses Einlesen wird hier gegen echte Ausgabeformate geprueft.
 */
class BehaviorLogicTest {

    /** Echte Ausgabeformate von `dumpsys deviceidle whitelist` (Android 14). */
    private val dump = """
        system-excidle,com.android.providers.calendar,10116
        system-excidle,com.android.providers.downloads,10106
        system,com.android.messaging,10154
        com.brave.browser,10234
        com.android.dreams.phototable
    """.trimIndent()

    @Test
    fun findetPaketInZeileMitVorstufeUndUid() {
        assertTrue(whitelistContains(dump, "com.android.messaging"))
        assertTrue(whitelistContains(dump, "com.android.providers.downloads"))
    }

    @Test
    fun findetPaketOhneUid() {
        assertTrue(whitelistContains(dump, "com.android.dreams.phototable"))
    }

    @Test
    fun findetPaketInPlainZeile() {
        assertTrue(whitelistContains(dump, "com.brave.browser"))
    }

    @Test
    fun lehntNichtEnthaltenePaketeAb() {
        assertFalse(whitelistContains(dump, "com.example.nicht.da"))
    }

    @Test
    fun keineTeiltreffer() {
        // "com.android.providers.calendar" darf NICHT als Treffer fuer "calendar" gelten
        assertFalse(whitelistContains(dump, "calendar"))
        assertFalse(whitelistContains(dump, "com.android.providers"))
    }

    @Test
    fun leereAusgabeIstKeinTreffer() {
        assertFalse(whitelistContains("", "com.brave.browser"))
        // Befehlsfehler: Ausgabe enthaelt nur eine Fehlermeldung
        assertFalse(whitelistContains("Exception occurred while executing 'deviceidle'", "com.brave.browser"))
    }

    @Test
    fun crlfUndLeerzeilenStoerenNicht() {
        val winDump = "com.brave.browser,10234\r\n\r\n  com.android.messaging,10154  \r\n"
        assertTrue(whitelistContains(winDump, "com.brave.browser"))
        assertTrue(whitelistContains(winDump, "com.android.messaging"))
    }
}
