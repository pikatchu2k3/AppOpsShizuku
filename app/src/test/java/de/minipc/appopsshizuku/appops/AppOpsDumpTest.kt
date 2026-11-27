package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Eine Gesamtausgabe von `cmd appops get <pkg>` ergibt je Op dasselbe wie die Einzelabfrage. */
class AppOpsDumpTest {

    private val controller = AppOpsController(NoopBackend())
    private fun op(name: String, perm: String? = null) = AppOp(name, name, perm)

    // Echte Form (Android 15/16): erst Uid-Modi, dann Paket-Ops mit Zeiten
    private val dump = """
        Uid mode: CAMERA: ignore
        Uid mode: RECORD_AUDIO: foreground
        Uid mode: COARSE_LOCATION: allow
        CAMERA: allow; time=+2d3h4m5s123ms ago; duration=+1s23ms
        READ_CLIPBOARD: deny; rejectTime=+5m ago
        COARSE_LOCATION: allow; time=+1h ago
        FINE_LOCATION: ignore
        RUN_IN_BACKGROUND: allow
    """.trimIndent()

    @Test fun uidModeHatVorrangVorPaketZeile() =
        assertEquals("ignore", AppOpsDump.mode(controller, dump, op("CAMERA", "android.permission.CAMERA")))

    @Test fun foregroundIstAllow() =
        assertEquals("allow", AppOpsDump.mode(controller, dump, op("RECORD_AUDIO", "android.permission.RECORD_AUDIO")))

    @Test fun reineAppOpLiestPaketZeile() =
        assertEquals("deny", AppOpsDump.mode(controller, dump, op("READ_CLIPBOARD")))

    @Test fun praefixOpsVermischenSichNicht() {
        // COARSE_LOCATION darf FINE_LOCATION nicht beeinflussen und umgekehrt
        assertEquals("ignore", AppOpsDump.mode(controller, dump, op("FINE_LOCATION")))
        assertEquals("allow", AppOpsDump.mode(controller, dump, op("COARSE_LOCATION")))
        // ein Op-Name, der nur Teil eines anderen ist
        assertEquals("", AppOpsDump.slice(dump, "LOCATION"))
        assertEquals("", AppOpsDump.slice(dump, "RUN_IN"))
    }

    @Test fun opOhneEintragIstDefault() =
        assertEquals("default", AppOpsDump.mode(controller, dump, op("TURN_SCREEN_ON")))

    @Test fun leereAusgabeIstDefault() =
        assertEquals("default", AppOpsDump.mode(controller, "No operations.", op("CAMERA")))

    @Test fun sliceEntsprichtEinzelabfrage() {
        val single = AppOpsDump.slice(dump, "CAMERA").lines()
        assertEquals(listOf("Uid mode: CAMERA: ignore", "CAMERA: allow; time=+2d3h4m5s123ms ago; duration=+1s23ms"), single)
    }

    @Test fun defaultModeZeileWirdIgnoriert() {
        val d = "Default mode: allow\nCAMERA: deny"
        assertEquals("deny", AppOpsDump.mode(controller, d, op("CAMERA")))
        assertEquals("default", AppOpsDump.mode(controller, d, op("RECORD_AUDIO")))
    }

    // ---- Unterstuetzung dieses Geraets ----

    @Test fun klassifiziereAntwort() {
        assertEquals(true, AppOpsDump.Support.classify(ShellResult(0, "No operations.", "")))
        assertEquals(false, AppOpsDump.Support.classify(ShellResult(255, "", "Error: Unknown operation string: FOO")))
        assertNull(AppOpsDump.Support.classify(ShellResult(1, "", "keine Shizuku-Berechtigung")))
        assertNull(AppOpsDump.Support.classify(ShellResult(124, "", "timeout")))
    }

    @Test fun nurEindeutigesWirdGemerkt() {
        val s = AppOpsDump.Support()
        var calls = 0
        // unklarer Fehler: nicht merken, beim naechsten Mal erneut fragen
        assertNull(s.isKnown("CAMERA") { calls++; ShellResult(1, "", "boom") })
        assertEquals(true, s.isKnown("CAMERA") { calls++; ShellResult(0, "", "") })
        // gemerkt: kein weiterer Aufruf
        assertEquals(true, s.isKnown("CAMERA") { calls++; ShellResult(1, "", "boom") })
        assertEquals(2, calls)
        assertEquals(false, s.isKnown("FOO") { ShellResult(255, "", "Unknown operation string: FOO") })
        assertEquals(false, s.isKnown("FOO") { error("darf nicht erneut gefragt werden") })
        s.clear()
        assertTrue(s.isKnown("FOO") { ShellResult(0, "", "") } == true)
    }
}
