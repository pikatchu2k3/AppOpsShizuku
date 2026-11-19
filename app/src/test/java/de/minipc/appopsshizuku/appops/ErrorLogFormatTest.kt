package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorLogFormatTest {

    @Test fun erfolgreicherBefehl_keinEintrag() {
        assertNull(ErrorLogFormat.shell(arrayOf("pm", "suspend", "x"), ShellResult(0, "ok", "")))
    }

    @Test fun fehlgeschlagenerBefehl_mitBefehlExitUndAusgabe() {
        val t = ErrorLogFormat.shell(arrayOf("pm", "disable-user", "com.x"), ShellResult(1, "", "Error: protected package"))!!
        assertTrue(t.startsWith("$ pm disable-user com.x\nexit=1"))
        assertTrue("stderr: Error: protected package" in t)
    }

    @Test fun exit0MitException_giltAlsFehler() {
        assertTrue(ErrorLogFormat.shell(arrayOf("cmd"), ShellResult(0, "java.lang.SecurityException: nope", "")) != null)
    }

    @Test fun langeAusgabeWirdGekuerzt() {
        val c = ErrorLogFormat.clip("a".repeat(1000), 10)
        assertEquals("aaaaaaaaaa … (+990 Zeichen)", c)
    }

    @Test fun wiederholungenInnerhalbDesFenstersUnterdrueckt() {
        assertTrue(ErrorLogFormat.shouldWrite(1000, null))
        assertFalse(ErrorLogFormat.shouldWrite(1000 + 60_000, 1000))
        assertTrue(ErrorLogFormat.shouldWrite(1000 + ErrorLogFormat.REPEAT_WINDOW_MS, 1000))
        // Zahlen (PIDs, Zeiten) machen keinen neuen Fehler
        assertEquals(ErrorLogFormat.key("SHELL", "pid 123 tot"), ErrorLogFormat.key("SHELL", "pid 456 tot"))
    }

    @Test fun kuerzenBehaeltNeuesteEintraegeAnEintragsgrenze() {
        val log = (1..50).joinToString("") { ErrorLogFormat.entry("t$it", "SHELL", "x".repeat(40)) }
        val cut = ErrorLogFormat.trim(log, max = 1000, keep = 500)
        assertTrue(cut.startsWith(ErrorLogFormat.ENTRY_PREFIX))
        assertTrue(cut.length <= 500)
        assertTrue("t50 · SHELL" in cut)
        assertFalse("t1 · SHELL" in cut)
    }

    @Test fun zaehlenUndLetzterZeitpunkt() {
        val log = ErrorLogFormat.entry("2026-11-19 10:00:00", "SHELL", "a") +
            ErrorLogFormat.entry("2026-11-19 11:00:00", "FEHLER X", "b")
        assertEquals(2, ErrorLogFormat.count(log))
        assertEquals("2026-11-19 11:00:00", ErrorLogFormat.lastTimestamp(log))
        assertNull(ErrorLogFormat.lastTimestamp(""))
    }
}
