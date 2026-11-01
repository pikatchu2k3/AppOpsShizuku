package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionStatusTest {

    @Test
    fun bootHintNurWennSchutzUndKeinShizuku() {
        assertEquals(ProtectionStatus.HintKind.SHIZUKU_MISSING, ProtectionStatus.bootHint(3, false))
        assertNull(ProtectionStatus.bootHint(3, true))
        assertNull(ProtectionStatus.bootHint(0, false))
    }

    @Test
    fun driftNurBeiErzwingendenModi() {
        // Wunsch deny, Ist allow -> Abweichung (Berechtigungs-Op)
        assertTrue(DriftCheck.isDrift("android.permission.CAMERA", "deny", "allow"))
        assertTrue(DriftCheck.isDrift("android.permission.CAMERA", "deny", null))
        // Wunsch deny, Ist ignore -> fuer Permission-Ops gleichwertig blockiert -> keine Abweichung
        assertFalse(DriftCheck.isDrift("android.permission.CAMERA", "deny", "ignore"))
        // Reine AppOp: ignore gewuenscht, default Ist -> Abweichung
        assertTrue(DriftCheck.isDrift(null, "ignore", "default"))
        assertFalse(DriftCheck.isDrift(null, "ignore", "ignore"))
        // "default"/"allow" erzwingen nichts
        assertFalse(DriftCheck.isDrift(null, "default", "ignore"))
        assertFalse(DriftCheck.isDrift("android.permission.CAMERA", "allow", "deny"))
    }
}
