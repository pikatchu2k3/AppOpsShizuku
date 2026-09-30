package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.appops.FakeDevice.Companion.OWN
import de.minipc.appopsshizuku.appops.FakeDevice.Companion.PKG
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 3 "Lifecycle-Profil": Reihenfolge, Messfalle, Ruecknahme, Ausschluesse. */
class LifecycleProfileTest {

    private fun outcome(r: LifecycleResult, step: LifecycleStep) = r.outcomes.single { it.step == step }

    // ---------------------------------------------------------------- Reihenfolge

    @Test
    fun planSetztAusnahmeVorStufe() {
        val plan = applyPlan(LifecycleProfile())
        assertTrue(plan.indexOf(LifecycleStep.BATTERY_EXEMPT) < plan.indexOf(LifecycleStep.STANDBY_BUCKET))
        assertEquals(
            listOf(
                LifecycleStep.BATTERY_EXEMPT, LifecycleStep.STANDBY_BUCKET,
                LifecycleStep.RUN_IN_BACKGROUND, LifecycleStep.RUN_ANY_IN_BACKGROUND
            ),
            plan
        )
    }

    @Test
    fun optimierenNurMitHaken() {
        assertFalse(LifecycleStep.COMPILE in applyPlan(LifecycleProfile(compile = false)))
        assertEquals(LifecycleStep.COMPILE, applyPlan(LifecycleProfile(compile = true)).last())
    }

    @Test
    fun geraetBekommtAusnahmeBefehlVorStufenBefehl() {
        val dev = FakeDevice()
        LifecycleManager(dev, OWN).apply(PKG, LifecycleProfile(standbyBucket = 10), isSystemApp = false)
        val whitelist = dev.indexOf("cmd", "deviceidle", "whitelist")
        val bucket = dev.indexOf("am", "set-standby-bucket")
        assertTrue(whitelist >= 0 && bucket >= 0)
        assertTrue("Ausnahme muss VOR der Stufe gesetzt werden", whitelist < bucket)
        // Stufe wird NACH dem Setzen erneut gelesen
        assertTrue(dev.commands.drop(bucket + 1).any { it.take(3) == listOf("cmd", "activity", "get-standby-bucket") })
        assertTrue(dev.indexOf("cmd", "appops", "set") > bucket)
        assertEquals(-1, dev.indexOf("cmd", "package", "compile"))
    }

    @Test
    fun rueckname_umgekehrteReihenfolge_ausnahmeVorStufe() {
        val plan = revertPlan()
        assertTrue(plan.indexOf(LifecycleStep.RUN_ANY_IN_BACKGROUND) < plan.indexOf(LifecycleStep.RUN_IN_BACKGROUND))
        assertTrue(plan.indexOf(LifecycleStep.RUN_IN_BACKGROUND) < plan.indexOf(LifecycleStep.BATTERY_EXEMPT))
        assertTrue(plan.indexOf(LifecycleStep.BATTERY_EXEMPT) < plan.indexOf(LifecycleStep.STANDBY_BUCKET))

        val dev = FakeDevice()
        LifecycleManager(dev, OWN).revert(
            PKG, LifecycleSnapshot(false, 40, "ignore", "ignore"), isSystemApp = false
        )
        val any = dev.commands.indexOf(listOf("cmd", "appops", "set", PKG, "RUN_ANY_IN_BACKGROUND", "ignore"))
        val run = dev.commands.indexOf(listOf("cmd", "appops", "set", PKG, "RUN_IN_BACKGROUND", "ignore"))
        val exempt = dev.commands.indexOf(listOf("cmd", "deviceidle", "whitelist", "-$PKG"))
        val bucket = dev.commands.indexOf(listOf("am", "set-standby-bucket", PKG, "rare"))
        assertTrue(any in 0 until run)
        assertTrue(run < exempt)
        assertTrue("Ausnahme erst entfernen, dann Stufe", exempt < bucket)
    }

    // ---------------------------------------------------------------- Messfalle

    @Test
    fun ausnahmeHaeltStufeAuf5_keineFalscheErfolgsmeldung() {
        val dev = FakeDevice(exempt = false, userBucket = 40)
        val r = LifecycleManager(dev, OWN).apply(PKG, LifecycleProfile(10, batteryExempt = true), false)

        assertTrue(outcome(r, LifecycleStep.BATTERY_EXEMPT).ok)
        val b = outcome(r, LifecycleStep.STANDBY_BUCKET)
        assertEquals(StepOutcome.Status.FAILED, b.status)
        assertEquals("10 (Aktiv)", b.wanted)
        assertEquals("5 (vom System ausgenommen)", b.actual)
        assertTrue(b.reason!!.contains("Akku-Ausnahme"))
        assertTrue(b in r.failures)
    }

    @Test
    fun ohneAusnahmeWirdStufeUebernommen() {
        val dev = FakeDevice(exempt = true, userBucket = 40)
        val r = LifecycleManager(dev, OWN).apply(PKG, LifecycleProfile(20, batteryExempt = false), false)
        assertTrue(r.failures.isEmpty())
        assertEquals(20, dev.bucket)
        assertEquals("allow", dev.modes["RUN_IN_BACKGROUND"])
        assertEquals("allow", dev.modes["RUN_ANY_IN_BACKGROUND"])
        // Vorher-Zustand korrekt gemerkt (Ausnahme -> Stufe 5)
        assertEquals(LifecycleSnapshot(true, 5, "default", "default"), r.before)
    }

    @Test
    fun stufe5IstNieErfolg_undWirdNichtGesendet() {
        val dev = FakeDevice(exempt = true)
        val r = LifecycleManager(dev, OWN).apply(PKG, LifecycleProfile(5, batteryExempt = true), false)
        val b = outcome(r, LifecycleStep.STANDBY_BUCKET)
        // Geraet steht zwar auf 5, aber setzen ist unmoeglich -> nie "Erfolg"
        assertEquals(StepOutcome.Status.NOT_SETTABLE, b.status)
        assertEquals(-1, dev.indexOf("am", "set-standby-bucket"))
    }

    @Test
    fun geraetBehieltAltenWert_wirdErkannt() {
        val dev = FakeDevice(modes = mutableMapOf("RUN_ANY_IN_BACKGROUND" to "ignore"),
            ignoredOps = setOf("RUN_ANY_IN_BACKGROUND"))
        val r = LifecycleManager(dev, OWN).apply(PKG, LifecycleProfile(batteryExempt = false), false)
        val o = outcome(r, LifecycleStep.RUN_ANY_IN_BACKGROUND)
        assertEquals(StepOutcome.Status.FAILED, o.status)
        assertEquals("allow", o.wanted)
        assertEquals("ignore", o.actual)
        assertEquals("Gerät behielt den alten Wert", o.reason)
        // Die anderen Schritte bleiben einzeln OK - kein Sammel-Ergebnis
        assertTrue(outcome(r, LifecycleStep.RUN_IN_BACKGROUND).ok)
        assertEquals(1, r.failures.size)
    }

    @Test
    fun behieltAltenWert_reineLogik() {
        assertTrue(deviceKeptOldValue(40, 10, 40))
        assertFalse(deviceKeptOldValue(40, 10, 10))   // uebernommen
        assertFalse(deviceKeptOldValue(40, 10, 30))   // anderer Wert, nicht der alte
        assertFalse(deviceKeptOldValue(null, 10, 40)) // alter Wert unbekannt -> nichts behaupten
        assertEquals("Gerät behielt den alten Wert", bucketFailureReason(40, 10, 40, false))
        assertTrue(bucketFailureReason(40, 10, 5, true).contains("Akku-Ausnahme"))
        assertEquals("Stufe danach nicht lesbar", bucketFailureReason(40, 10, null, false))
    }

    // ---------------------------------------------------------------- Ruecknahme

    @Test
    fun rundlaufAnwendenUndZuruecknehmenStelltUrsprungHer() {
        val dev = FakeDevice(exempt = false, userBucket = 40, modes = mutableMapOf("RUN_ANY_IN_BACKGROUND" to "ignore"))
        val mgr = LifecycleManager(dev, OWN)
        val applied = mgr.apply(PKG, LifecycleProfile(10, batteryExempt = true), false)
        assertTrue(dev.exempt)

        val reverted = mgr.revert(PKG, applied.before!!, false)
        assertTrue(reverted.failures.isEmpty())
        assertTrue(shouldClearSnapshot(reverted))
        assertFalse(dev.exempt)
        assertEquals(40, dev.bucket)
        assertEquals("ignore", dev.modes["RUN_ANY_IN_BACKGROUND"])
        assertEquals("default", dev.modes["RUN_IN_BACKGROUND"])
    }

    @Test
    fun rueckname_alteStufe5DurchAusnahme_ohneSetzBefehl() {
        val dev = FakeDevice(exempt = false, userBucket = 10)
        val r = LifecycleManager(dev, OWN).revert(PKG, LifecycleSnapshot(true, 5, null, null), false)
        assertTrue(dev.exempt)
        assertEquals(StepOutcome.Status.OK, outcome(r, LifecycleStep.STANDBY_BUCKET).status)
        assertEquals(-1, dev.indexOf("am", "set-standby-bucket"))
        // Nicht lesbare Werte werden nicht angefasst
        assertEquals(StepOutcome.Status.SKIPPED, outcome(r, LifecycleStep.RUN_IN_BACKGROUND).status)
        assertEquals(-1, dev.indexOf("cmd", "appops", "set"))
    }

    @Test
    fun ursprungszustandWirdBeimErneutenAnwendenNichtUeberschrieben() {
        val original = LifecycleSnapshot(false, 40, "default", "ignore")
        val afterProfile = LifecycleSnapshot(true, 5, "allow", "allow")
        assertEquals(original, snapshotToKeep(original, afterProfile))
        assertEquals(afterProfile, snapshotToKeep(null, afterProfile))
        assertNull(snapshotToKeep(null, null))
    }

    @Test
    fun snapshotBleibtBeiFehlernGemerkt() {
        val fail = StepOutcome(LifecycleStep.STANDBY_BUCKET, StepOutcome.Status.FAILED, "40", "5")
        val ok = StepOutcome(LifecycleStep.BATTERY_EXEMPT, StepOutcome.Status.OK, "aus", "aus")
        assertFalse(shouldClearSnapshot(LifecycleResult(null, listOf(ok, fail))))
        assertTrue(shouldClearSnapshot(LifecycleResult(null, listOf(ok))))
        assertFalse(shouldClearSnapshot(LifecycleResult(null, emptyList(), refused = "x")))
    }

    // ---------------------------------------------------------------- Ausschluesse

    @Test
    fun eigeneAppWirdNieAngefasst() {
        val dev = FakeDevice()
        val r = LifecycleManager(dev, OWN).apply(OWN, LifecycleProfile(), false)
        assertNotNull(r.refused)
        assertTrue(dev.commands.isEmpty())
        assertTrue(LifecycleManager(dev, OWN).revert(OWN, LifecycleSnapshot(false, 40), false).refused != null)
        assertTrue(dev.commands.isEmpty())
    }

    @Test
    fun systemAppWirdNieAngefasst() {
        val dev = FakeDevice()
        val r = LifecycleManager(dev, OWN).apply("com.android.systemui", LifecycleProfile(), isSystemApp = true)
        assertEquals("Nicht auf System-Apps anwendbar", r.refused)
        assertTrue(dev.commands.isEmpty())
    }

    @Test
    fun ohneShizukuEhrlichNichtMoeglich() {
        val r = LifecycleManager(NoopBackend(), OWN).apply(PKG, LifecycleProfile(), false)
        assertTrue(r.refused!!.startsWith("Nicht möglich"))
        assertTrue(r.outcomes.isEmpty())
        assertNull(lifecycleRefusal(PKG, OWN, false, true))
    }

    // ---------------------------------------------------------------- Bucket-Zuordnung

    @Test
    fun bucketZuordnungInklNichtSetzbarerWerte() {
        for (b in listOf(10, 20, 30, 40, 45, 50)) assertNull(bucketNotSettableReason(b))
        assertTrue(bucketNotSettableReason(5)!!.contains("exempted"))
        assertTrue(bucketNotSettableReason(99)!!.contains("99"))
        assertEquals("10 (Aktiv)", bucketText(10))
        assertEquals("5 (vom System ausgenommen)", bucketText(5))
        assertEquals("Stufe 99", bucketText(99))
    }

    @Test
    fun fehlerzeileEnthaeltWunschIstUndRohmeldung() {
        val line = StepOutcome(
            LifecycleStep.STANDBY_BUCKET, StepOutcome.Status.FAILED, "10 (Aktiv)", "5 (vom System ausgenommen)",
            raw = "set: | ist: 5", reason = "Akku-Ausnahme"
        ).describe()
        assertTrue(line.startsWith("✗ Standby-Stufe"))
        assertTrue(line.contains("Wunsch 10 (Aktiv)"))
        assertTrue(line.contains("Gerät 5 (vom System ausgenommen)"))
        assertTrue(line.contains("Gerät meldet: set: | ist: 5"))
    }

    @Test
    fun optimierenErfolgNurBeiSuccess() {
        assertTrue(compileSucceeded(ShellResult(0, "Success", "")))
        assertFalse(compileSucceeded(ShellResult(0, "Failure: package not found", "")))
        assertFalse(compileSucceeded(ShellResult(1, "Success", "")))
    }

    // ---------------------------------------------------------------- JSON (Format v2)

    @Test
    fun jsonRundlaufErhaeltAlleFelder() {
        val e = LifecycleEntry(
            standbyBucket = 20, batteryExempt = false, compile = true,
            before = LifecycleSnapshot(true, 5, "default", "ignore"),
            screenOffExit = true, screenOffEffect = ScreenOffEffect.FORCE_STOP, releaseOnScreenOn = false
        )
        val back = lifecycleFromJson(lifecycleToJson(mapOf(PKG to e)))
        assertEquals(e, back[PKG])
    }

    @Test
    fun fehlendeFelderBleibenNull_undMergeFasstSieNichtAn() {
        val parsed = lifecycleFromJson(org.json.JSONObject("""{"com.x":{"screenOffExit":true}}"""))["com.x"]!!
        assertNull(parsed.standbyBucket)
        assertNull(parsed.before)
        assertNull(parsed.screenOffEffect)

        val local = LifecycleEntry(standbyBucket = 30, screenOffExit = false,
            screenOffEffect = ScreenOffEffect.FORCE_STOP, before = LifecycleSnapshot(false, 40))
        val merged = local.mergedWith(parsed)
        assertEquals(30, merged.standbyBucket)
        assertEquals(true, merged.screenOffExit)
        assertEquals(ScreenOffEffect.FORCE_STOP, merged.screenOffEffect)
        assertEquals(LifecycleSnapshot(false, 40), merged.before)
        // Standardprofil, wenn nichts gespeichert ist
        assertEquals(LifecycleProfile(), LifecycleEntry().profile())
        assertEquals(false, LifecycleEntry().screenOffExit == true)
    }

    @Test
    fun unbekannteWirkungWirdNichtGeraten() {
        val parsed = lifecycleFromJson(org.json.JSONObject("""{"com.x":{"screenOffEffect":"sprengen"}}"""))
        assertNull(parsed["com.x"]!!.screenOffEffect)
    }

    @Test
    fun v2MitLifecycleWirdGelesen() {
        val doc = """
            {"version":2,
             "profiles":[{"packageName":"com.brave.browser","opModes":{"CAMERA":"ignore"}}],
             "behavior":{"com.brave.browser":{"standbyBucket":20}},
             "lifecycle":{"com.brave.browser":{"standbyBucket":10,"batteryExempt":true,
                "screenOffExit":true,"screenOffEffect":"suspend",
                "before":{"batteryExempt":false,"standbyBucket":40,"runAnyInBackground":"ignore"}}}}
        """.trimIndent()
        val (profiles, behavior) = ProfileBackup.parseDocument(doc)
        assertEquals("ignore", profiles[PKG]?.get("CAMERA"))
        assertEquals(20, behavior[PKG]?.standbyBucket)
        val l = ProfileBackup.parseLifecycle(doc)[PKG]!!
        assertEquals(10, l.standbyBucket)
        assertEquals(ScreenOffEffect.SUSPEND, l.screenOffEffect)
        assertEquals(LifecycleSnapshot(false, 40, null, "ignore"), l.before)
    }

    @Test
    fun altformateOhneLifecycleBleibenLesbar() {
        val v2alt = """{"version":2,"profiles":[],"behavior":{"com.x":{"standbyBucket":20}}}"""
        assertTrue(ProfileBackup.parseLifecycle(v2alt).isEmpty())
        assertEquals(20, ProfileBackup.parseDocument(v2alt).second["com.x"]?.standbyBucket)

        val array = """[{"packageName":"com.x","opModes":{"CAMERA":"deny"}}]"""
        assertTrue(ProfileBackup.parseLifecycle(array).isEmpty())
        assertEquals("deny", ProfileBackup.parseDocument(array).first["com.x"]?.get("CAMERA"))
    }
}
