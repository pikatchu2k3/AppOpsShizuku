package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.appops.FakeDevice.Companion.OWN
import de.minipc.appopsshizuku.appops.FakeDevice.Companion.PKG
import de.minipc.appopsshizuku.appops.ScreenAction.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 3 "Screen-Off-Exit": Entscheidungslogik je App + ehrliche Zustandspruefung. */
class ScreenOffExitTest {

    private val on = LifecycleEntry(screenOffExit = true)
    private val system = setOf("com.android.systemui")

    private fun off(entries: Map<String, LifecycleEntry>, already: Set<String> = emptySet()) =
        screenActions(entries, screenOn = false, OWN, system, suspendedByUs = emptySet(), alreadySuspended = already)

    @Test
    fun standardIstAus() {
        assertTrue(off(mapOf(PKG to LifecycleEntry())).isEmpty())
        assertTrue(off(mapOf(PKG to LifecycleEntry(screenOffExit = false))).isEmpty())
        assertFalse(screenOffServiceNeeded(mapOf(PKG to LifecycleEntry()), OWN, system))
    }

    @Test
    fun bildschirmAus_suspendIstStandardwirkung() {
        assertEquals(listOf(ScreenAction(PKG, Kind.SUSPEND)), off(mapOf(PKG to on)))
    }

    @Test
    fun bildschirmAus_forceStopAlsHarteOption() {
        val e = on.copy(screenOffEffect = ScreenOffEffect.FORCE_STOP)
        assertEquals(listOf(ScreenAction(PKG, Kind.FORCE_STOP)), off(mapOf(PKG to e)))
    }

    @Test
    fun eigeneAppUndSystemAppsNie() {
        val entries = mapOf(OWN to on, "com.android.systemui" to on)
        assertTrue(off(entries).isEmpty())
        assertTrue(screenActions(entries, true, OWN, system, suspendedByUs = entries.keys).isEmpty())
        assertFalse(screenOffServiceNeeded(entries, OWN, system))
        assertFalse(screenOffAllowed(OWN, OWN, false))
        assertFalse(screenOffAllowed(PKG, OWN, true))
        assertTrue(screenOffAllowed(PKG, OWN, false))
    }

    @Test
    fun dienstNurSolangeMindestensEineAppDenHakenHat() {
        assertTrue(screenOffServiceNeeded(mapOf(PKG to on, "com.x" to LifecycleEntry()), OWN, system))
        assertFalse(screenOffServiceNeeded(emptyMap(), OWN, system))
    }

    @Test
    fun selbstEingefroreneAppWirdNichtAngefasst() {
        assertTrue(off(mapOf(PKG to on), already = setOf(PKG)).isEmpty())
    }

    @Test
    fun bildschirmAn_nurVonUnsEingefroreneFreigeben() {
        val entries = mapOf(PKG to on, "com.other" to on)
        val actions = screenActions(entries, true, OWN, system, suspendedByUs = setOf(PKG))
        assertEquals(listOf(ScreenAction(PKG, Kind.UNSUSPEND)), actions)
    }

    @Test
    fun bildschirmAn_ohneFreigabeBleibtEingefroren() {
        val e = on.copy(releaseOnScreenOn = false)
        assertTrue(screenActions(mapOf(PKG to e), true, OWN, system, setOf(PKG)).isEmpty())
    }

    @Test
    fun bildschirmAn_hakenInzwischenAus_wirdTrotzdemFreigegeben() {
        val e = LifecycleEntry(screenOffExit = false, releaseOnScreenOn = false)
        assertEquals(
            listOf(ScreenAction(PKG, Kind.UNSUSPEND)),
            screenActions(mapOf(PKG to e), true, OWN, system, setOf(PKG))
        )
        // auch wenn der Eintrag ganz fehlt
        assertEquals(
            listOf(ScreenAction(PKG, Kind.UNSUSPEND)),
            screenActions(emptyMap(), true, OWN, system, setOf(PKG))
        )
    }

    @Test
    fun bildschirmAn_forceStopHatNichtsFreizugeben() {
        val e = on.copy(screenOffEffect = ScreenOffEffect.FORCE_STOP)
        assertTrue(screenActions(mapOf(PKG to e), true, OWN, system, emptySet()).isEmpty())
    }

    @Test
    fun zustandWirdAusDumpsysGeprueft() {
        val dev = FakeDevice()
        val exec = ScreenOffExecutor(dev)
        val r = exec.run(listOf(ScreenAction(PKG, Kind.SUSPEND), ScreenAction(PKG, Kind.FORCE_STOP)))
        assertTrue(r.all { it.ok })
        assertEquals(true, r[0].suspended)
        assertEquals(true, r[1].stopped)
        assertTrue(dev.commands.any { it == listOf("dumpsys", "package", PKG) })

        val back = exec.run(listOf(ScreenAction(PKG, Kind.UNSUSPEND)))
        assertTrue(back.single().ok)
        assertFalse(dev.suspended)
    }

    @Test
    fun geraetIgnoriertSuspend_ehrlichNichtOk() {
        val dev = FakeDevice(ignoreSuspend = true)
        val r = ScreenOffExecutor(dev).run(listOf(ScreenAction(PKG, Kind.SUSPEND))).single()
        assertFalse(r.ok)
        assertTrue(r.describe().contains("NICHT eingefroren"))
        // nicht als "von uns eingefroren" merken -> beim Einschalten nichts falsch freigeben
        assertTrue(updateSuspendedByUs(emptySet(), listOf(r)).isEmpty())
    }

    @Test
    fun vonUnsEingefroreneWerdenGemerktUndWiederEntfernt() {
        val a = ScreenActionResult(ScreenAction(PKG, Kind.SUSPEND), true, true, false, "")
        val set = updateSuspendedByUs(emptySet(), listOf(a))
        assertEquals(setOf(PKG), set)
        val b = ScreenActionResult(ScreenAction(PKG, Kind.UNSUSPEND), true, false, false, "")
        assertTrue(updateSuspendedByUs(set, listOf(b)).isEmpty())
        val c = ScreenActionResult(ScreenAction("com.x", Kind.FORCE_STOP), true, false, true, "")
        assertEquals(set, updateSuspendedByUs(set, listOf(c)))
    }

    @Test
    fun stoppedWirdAusEchterZeileGelesen() {
        val line = "    User 0: ceDataInode=2622625 installed=true hidden=false suspended=false stopped=true enabled=0"
        assertEquals(true, parseStopped(line))
        assertEquals(false, parseStopped(line.replace("stopped=true", "stopped=false")))
        assertNull(parseStopped("kein passendes Feld"))
        assertTrue(screenActionVerified(Kind.FORCE_STOP, false, true))
        assertFalse(screenActionVerified(Kind.SUSPEND, null, null))
    }

    @Test
    fun wirkungAusJson() {
        assertEquals(ScreenOffEffect.SUSPEND, ScreenOffEffect.fromJson("suspend"))
        assertEquals(ScreenOffEffect.FORCE_STOP, ScreenOffEffect.fromJson("force_stop"))
        assertNull(ScreenOffEffect.fromJson(""))
        assertNull(ScreenOffEffect.fromJson(null))
    }
}
