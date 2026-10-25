package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.appops.FakeDevice.Companion.OWN
import de.minipc.appopsshizuku.appops.FakeDevice.Companion.PKG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Nur bei Bedarf": sperren/freigeben mit Pruefung, Verlassen erkennen, Frist, Abgrenzung zu Screen-Off-Exit. */
class OnDemandTest {

    private val onDemand = LifecycleEntry(onDemand = true)
    private val system = setOf("com.android.systemui")

    // --- Sperren / Freigeben ---

    @Test fun sperrenEinfrieren_beendetZuerst_undPrueftDenZustand() {
        val dev = FakeDevice()
        val r = OnDemandExecutor(dev).lock(PKG, OnDemandLock.SUSPEND)
        assertTrue(r.ok)
        assertEquals(true, r.state.suspended)
        assertEquals(true, r.state.stopped)
        assertTrue(dev.indexOf("am", "force-stop") < dev.indexOf("pm", "suspend"))
    }

    @Test fun sperrenDeaktivieren_liestEnabledAusDerUser0Zeile() {
        val dev = FakeDevice()
        val r = OnDemandExecutor(dev).lock(PKG, OnDemandLock.DISABLE)
        assertTrue(r.ok)
        assertEquals(true, r.state.disabled)
        assertEquals(false, r.state.suspended)
    }

    @Test fun geraetIgnoriertDisable_exit0_trotzdemKeinErfolg() {
        val dev = FakeDevice(ignoreDisable = true)
        val r = OnDemandExecutor(dev).lock(PKG, OnDemandLock.DISABLE)
        assertFalse(r.ok)
        assertTrue(r.raw.contains("cannot change"))
    }

    @Test fun geraetIgnoriertSuspend_keinErfolg() {
        assertFalse(OnDemandExecutor(FakeDevice(ignoreSuspend = true)).lock(PKG, OnDemandLock.SUSPEND).ok)
    }

    @Test fun freigeben_hebtBeideSperrartenAuf() {
        val dev = FakeDevice(suspended = true, enabledState = 3)
        val r = OnDemandExecutor(dev).unlock(PKG)
        assertTrue(r.ok)
        assertTrue(r.state.isUnlocked)
    }

    @Test fun freigeben_freieAppBekommtKeineBefehle() {
        val dev = FakeDevice()
        assertTrue(OnDemandExecutor(dev).unlock(PKG).ok)
        assertEquals(-1, dev.indexOf("pm", "unsuspend"))
        assertEquals(-1, dev.indexOf("pm", "enable"))
    }

    @Test fun zustandParser() {
        val st = parseLockState("  enabled=1\n    User 0: installed=true suspended=true stopped=false enabled=3 instant=false")
        assertEquals(LockState(suspended = true, disabled = true, stopped = false), st)
        assertNull(parseLockState("nichts").suspended)
    }

    // --- Verlassen erkennen ---

    @Test fun vordergrundAusEreignissen() {
        assertEquals(true, OnDemand.foregroundFromEvents(listOf(OnDemand.EVENT_PAUSED, OnDemand.EVENT_RESUMED)))
        // Activity-Wechsel innerhalb der App: PAUSED(A) -> RESUMED(B) -> weiterhin vorne
        assertEquals(true, OnDemand.foregroundFromEvents(listOf(1, 2, 1)))
        assertEquals(false, OnDemand.foregroundFromEvents(listOf(1, 2)))
        assertEquals(false, OnDemand.foregroundFromEvents(listOf(1, 2, 23)))
        // Andere Typen (z. B. 5 = Konfiguration) aendern nichts; keine Ereignisse = keine Aussage
        assertEquals(true, OnDemand.foregroundFromEvents(listOf(1, 5)))
        assertNull(OnDemand.foregroundFromEvents(emptyList()))
    }

    @Test fun vordergrundAusDumpsys() {
        val dump = """
            Display #0
              topResumedActivity=ActivityRecord{1a2b u0 $PKG/.MainActivity t42}
        """.trimIndent()
        assertEquals(true, OnDemand.foregroundFromDumpsys(dump, PKG))
        assertEquals(false, OnDemand.foregroundFromDumpsys(dump, "com.other"))
        // Praefix eines anderen Pakets zaehlt nicht
        assertEquals(false, OnDemand.foregroundFromDumpsys(dump.replace(PKG, "$PKG.x"), PKG))
        assertNull(OnDemand.foregroundFromDumpsys("keine passende Zeile", PKG))
    }

    @Test fun fristRegel() {
        assertFalse(OnDemand.shouldLock(10_000, null, 10))
        assertFalse(OnDemand.shouldLock(10_000, 1_000, 10))
        assertTrue(OnDemand.shouldLock(11_000, 1_000, 10))
        // Frist 0 = nur Bildschirm-Aus sperrt
        assertFalse(OnDemand.shouldLock(10_000_000, 1_000, 0))
    }

    // --- Abgrenzung zu "Beim Bildschirm-Aus anhalten" ---

    @Test fun bildschirmAn_gibtNurBeiBedarfNieFrei() {
        val entries = mapOf(PKG to onDemand.copy(screenOffExit = true, releaseOnScreenOn = true))
        val on = screenActions(entries, screenOn = true, OWN, system, suspendedByUs = setOf(PKG))
        assertTrue(on.isEmpty())
        val off = screenActions(entries, screenOn = false, OWN, system, suspendedByUs = emptySet())
        assertTrue(off.isEmpty())
    }

    @Test fun dienstNoetigAuchNurFuerBedarfsApps_aberNieFuerEigeneOderSystemApps() {
        assertTrue(screenOffServiceNeeded(mapOf(PKG to onDemand), OWN, system))
        assertFalse(screenOffServiceNeeded(mapOf(OWN to onDemand), OWN, system))
        assertFalse(screenOffServiceNeeded(mapOf("com.android.systemui" to onDemand), OWN, system))
        assertEquals(setOf(PKG), OnDemand.apps(mapOf(PKG to onDemand, OWN to onDemand, "x.y" to LifecycleEntry()), OWN, system))
    }

    @Test fun standardwerte_undJsonRundweg() {
        assertEquals(OnDemandLock.SUSPEND, OnDemand.lockOf(onDemand))
        assertEquals(OnDemand.DEFAULT_GRACE_SECONDS, OnDemand.graceOf(onDemand))
        val e = onDemand.copy(onDemandLock = OnDemandLock.DISABLE, onDemandGraceSeconds = 0)
        assertEquals(e, lifecycleEntryFromJson(lifecycleEntryToJson(e)))
        // Fehlende Felder bleiben beim Import unangetastet
        val merged = e.mergedWith(LifecycleEntry(onDemandGraceSeconds = 300))
        assertEquals(OnDemandLock.DISABLE, merged.onDemandLock)
        assertEquals(300, merged.onDemandGraceSeconds)
    }
}
