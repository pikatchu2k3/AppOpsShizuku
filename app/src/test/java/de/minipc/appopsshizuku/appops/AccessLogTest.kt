package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessLogTest {

    private val day = 86_400_000L

    @Test
    fun dauerWirdGelesen() {
        assertEquals(2 * day + 3 * 3_600_000L + 4 * 60_000L + 5_000L + 123L, AccessLog.parseDuration("+2d3h4m5s123ms"))
        assertEquals(1_023L, AccessLog.parseDuration("+1s23ms"))
        assertEquals(0L, AccessLog.parseDuration("0"))
        assertNull(AccessLog.parseDuration("abc"))
    }

    @Test
    fun zeilenMitZugriffAblehnungUndDauer() {
        val out = """
            Uid mode: CAMERA: foreground
            CAMERA: allow; time=+2d3h ago; duration=+1s
            READ_CLIPBOARD: ignore; rejectTime=+5m ago
            RECORD_AUDIO: allow; time=+1s ago (running)
            WAKE_LOCK: allow
        """.trimIndent()
        val log = AccessLog.parse(out)
        assertEquals(2 * day + 3 * 3_600_000L, log["CAMERA"]?.lastAccessAgoMs)
        assertEquals(1_000L, log["CAMERA"]?.lastDurationMs)
        assertNull(log["CAMERA"]?.lastRejectAgoMs)
        assertEquals(5 * 60_000L, log["READ_CLIPBOARD"]?.lastRejectAgoMs)
        assertNull(log["READ_CLIPBOARD"]?.lastAccessAgoMs)
        assertTrue(log["RECORD_AUDIO"]!!.running)
        assertNull(log["WAKE_LOCK"]?.lastAccessAgoMs)
    }

    @Test
    fun uidZeileIstKeinEintrag() {
        assertFalse(AccessLog.parse("Uid mode: CAMERA: ignore").containsKey("CAMERA"))
    }

    @Test
    fun ungenutzt() {
        assertTrue(Recommendations.isUnused(OpAccess("CAMERA", lastAccessAgoMs = 40 * day), installedAgoMs = 400 * day))
        assertFalse(Recommendations.isUnused(OpAccess("CAMERA", lastAccessAgoMs = 2 * day), installedAgoMs = 400 * day))
        // Nie benutzt, aber erst seit 3 Tagen installiert -> noch keine Aussage
        assertFalse(Recommendations.isUnused(null, installedAgoMs = 3 * day))
        assertTrue(Recommendations.isUnused(null, installedAgoMs = 90 * day))
        assertFalse(Recommendations.isUnused(OpAccess("RECORD_AUDIO", lastAccessAgoMs = 90 * day, running = true), 90 * day))
    }

    @Test
    fun vorschlaegeNurFuerErteilteBerechtigungen() {
        val states = mapOf("CAMERA" to "allow", "READ_CONTACTS" to "allow", "RECORD_AUDIO" to "deny", "READ_CLIPBOARD" to "allow")
        val access = mapOf(
            "CAMERA" to OpAccess("CAMERA", lastAccessAgoMs = day),
            "READ_CONTACTS" to OpAccess("READ_CONTACTS", lastAccessAgoMs = day)
        )
        val perms = setOf("CAMERA", "READ_CONTACTS", "RECORD_AUDIO")
        // Spiel: Kontakte unueblich, Kamera bei Spielen ebenfalls; Zwischenablage ist keine Permission-Op
        val game = Recommendations.suggest(states, access, perms, Recommendations.CATEGORY_GAME, 400 * day)
        assertEquals(listOf("CAMERA", "READ_CONTACTS"), game.map { it.opName })
        assertTrue(game.all { it.reason == Recommendations.Reason.CATEGORY })
        // Unbekannte Kategorie, alles kuerzlich benutzt -> keine Vorschlaege
        assertEquals(emptyList<Recommendations.Suggestion>(), Recommendations.suggest(states, access, perms, -1, 400 * day))
    }
}
