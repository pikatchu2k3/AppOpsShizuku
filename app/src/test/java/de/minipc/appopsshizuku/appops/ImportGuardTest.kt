package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fremde Sicherungsdateien duerfen nichts mit Shell-Rechten einschleusen. */
class ImportGuardTest {

    private val protectedPkgs = setOf("com.android.systemui", "de.minipc.appopsshizuku")
    private fun isProtected(p: String) = p in protectedPkgs

    @Test fun paketnamen() {
        assertTrue(ImportGuard.validPackage("com.example.app"))
        assertTrue(ImportGuard.validPackage("org.fdroid.fdroid_2"))
        assertFalse(ImportGuard.validPackage(null))
        assertFalse(ImportGuard.validPackage(""))
        assertFalse(ImportGuard.validPackage("nodot"))
        assertFalse(ImportGuard.validPackage("--user"))
        assertFalse(ImportGuard.validPackage("-a.b"))
        assertFalse(ImportGuard.validPackage("com.x; rm -rf /"))
        assertFalse(ImportGuard.validPackage("com.x\ncom.y"))
        assertFalse(ImportGuard.validPackage("com." + "a".repeat(300)))
    }

    @Test fun profileBehaltenNurKatalogOpsUndBekannteModi() {
        val raw = mapOf(
            "com.ok" to mapOf("CAMERA" to "deny", "READ_CONTACTS" to "ignore", "EVIL_OP" to "deny", "CAMERA2" to "x"),
            "--evil" to mapOf("CAMERA" to "deny"),
            "com.modes" to mapOf("RECORD_AUDIO" to "foreground-grant-everything")
        )
        val r = ImportGuard.profiles(raw)
        assertEquals(setOf("com.ok"), r.value.keys)
        assertEquals(mapOf("CAMERA" to "deny", "READ_CONTACTS" to "ignore"), r.value["com.ok"])
        // EVIL_OP, CAMERA2, "--evil" (1 Op), "foreground-grant-everything"
        assertEquals(4, r.dropped)
    }

    @Test fun verhaltenGeschuetztePaketeWerdenNichtDeaktiviertOderEingefroren() {
        val raw = mapOf(
            "com.android.systemui" to BehaviorState(disabled = true, frozen = true, batteryExempt = true),
            "com.user.app" to BehaviorState(disabled = true, frozen = true),
            "bad name" to BehaviorState(disabled = true)
        )
        val r = ImportGuard.behavior(raw, ::isProtected)
        // geschuetzt: nur harmlose Felder bleiben
        assertEquals(BehaviorState(batteryExempt = true), r.value["com.android.systemui"])
        // normale App unveraendert
        assertEquals(BehaviorState(disabled = true, frozen = true), r.value["com.user.app"])
        assertFalse("bad name" in r.value)
        assertEquals(2, r.dropped)
    }

    @Test fun verhaltenEintragOhneRestWirdEntfernt() {
        val r = ImportGuard.behavior(
            mapOf("com.android.systemui" to BehaviorState(disabled = true)), ::isProtected
        )
        assertTrue(r.value.isEmpty())
    }

    @Test fun unbekannteStandbyStufeWirdVerworfen() {
        val r = ImportGuard.behavior(
            mapOf("com.x.y" to BehaviorState(standbyBucket = 99, batteryExempt = false)), ::isProtected
        )
        assertNull(r.value["com.x.y"]?.standbyBucket)
        assertEquals(false, r.value["com.x.y"]?.batteryExempt)
        assertEquals(1, r.dropped)
        assertEquals(20, ImportGuard.behavior(mapOf("com.x.y" to BehaviorState(standbyBucket = 20)), ::isProtected)
            .value["com.x.y"]?.standbyBucket)
    }

    @Test fun regelnWerdenBegrenztUndGefiltert() {
        val rule = AutoRule(
            id = "r1", name = "n".repeat(500), days = 0xFFFF, startMin = -5, endMin = 99999,
            ssid = "s".repeat(100), action = RuleAction.APPS_OFFLINE,
            packages = listOf("com.good.app", "com.android.systemui", "not a package", "-rf.x")
        )
        val r = ImportGuard.rules(listOf(rule, rule.copy(id = "")), ::isProtected)
        assertEquals(1, r.value.size)
        val x = r.value[0]
        assertEquals(80, x.name.length)
        assertEquals(0b1111111, x.days)
        assertEquals(0, x.startMin)
        assertEquals(24 * 60 - 1, x.endMin)
        assertEquals(32, x.ssid.length)
        assertEquals(listOf("com.good.app"), x.packages)
        assertEquals(3 + 1, r.dropped) // 3 Pakete + die Regel ohne id
    }

    @Test fun sanitizeFasstAllesZusammen() {
        val doc = mapOf("com.a.b" to mapOf("CAMERA" to "deny")) to
            mapOf("com.android.systemui" to BehaviorState(disabled = true))
        val extras = BackupExtras(whileInUse = mapOf("com.a.b" to setOf("CAMERA"), "bad" to setOf("CAMERA")))
        val r = ProfileBackup.sanitize(doc, emptyMap(), extras, ::isProtected)
        assertEquals(setOf("com.a.b"), r.profiles.keys)
        assertTrue(r.behavior.isEmpty())
        assertEquals(setOf("com.a.b"), r.extras.whileInUse.keys)
        assertEquals(2, r.dropped)
        assertEquals(0, r.disableOrFreezeCount)
    }

    @Test fun anzahlDerAbschaltungenFuerDieVorschau() {
        val r = ProfileBackup.ImportResult(
            profiles = emptyMap(),
            behavior = mapOf(
                "a.b" to BehaviorState(disabled = true),
                "c.d" to BehaviorState(frozen = true),
                "e.f" to BehaviorState(batteryExempt = true)
            )
        )
        assertEquals(2, r.disableOrFreezeCount)
    }
}
