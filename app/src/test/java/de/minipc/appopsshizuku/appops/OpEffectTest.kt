package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ehrlichkeitspruefung: ist der Op-Modus wirklich gesetzt worden?
 * Deckt Permission-Ops (pm grant/revoke, blocked/allow-Aequivalenz inkl. "foreground"),
 * reine AppOps (exakter Modus) und den Fall "nicht setzbar" ab.
 */
class OpEffectTest {

    private val PERM = "android.permission.CAMERA"

    // --- Permission-Op: blocked-Aequivalenz (deny/ignore) ---
    @Test fun perm_denyRequested_ignoreActual_istEffektiv() =
        assertTrue(OpEffect.isSetEffective(PERM, "deny", "ignore"))

    @Test fun perm_ignoreRequested_ignoreActual_istEffektiv() =
        assertTrue(OpEffect.isSetEffective(PERM, "ignore", "ignore"))

    @Test fun perm_denyRequested_denyActual_istEffektiv() =
        assertTrue(OpEffect.isSetEffective(PERM, "deny", "deny"))

    // --- Permission-Op: allow-Aequivalenz (allow/default), "foreground" ist bereits zu "allow" normalisiert ---
    @Test fun perm_allowRequested_allowActual_istEffektiv() =
        assertTrue(OpEffect.isSetEffective(PERM, "allow", "allow"))

    @Test fun perm_defaultRequested_allowActual_istEffektiv() =
        assertTrue(OpEffect.isSetEffective(PERM, "default", "allow"))

    @Test fun perm_allowRequested_defaultActual_istEffektiv() =
        assertTrue(OpEffect.isSetEffective(PERM, "allow", "default"))

    // --- Permission-Op: Wunsch blockiert, System offen -> NICHT effektiv ---
    @Test fun perm_denyRequested_allowActual_nichtEffektiv() =
        assertFalse(OpEffect.isSetEffective(PERM, "deny", "allow"))

    @Test fun perm_allowRequested_ignoreActual_nichtEffektiv() =
        assertFalse(OpEffect.isSetEffective(PERM, "allow", "ignore"))

    // --- Reine AppOps (keine Permission): Modus muss exakt stimmen ---
    @Test fun appop_denyRequested_denyActual_istEffektiv() =
        assertTrue(OpEffect.isSetEffective(null, "deny", "deny"))

    @Test fun appop_ignoreRequested_ignoreActual_istEffektiv() =
        assertTrue(OpEffect.isSetEffective(null, "ignore", "ignore"))

    @Test fun appop_allowRequested_allowActual_istEffektiv() =
        assertTrue(OpEffect.isSetEffective(null, "allow", "allow"))

    @Test fun appop_denyRequested_defaultActual_nichtEffektiv() =
        assertFalse(OpEffect.isSetEffective(null, "deny", "default"))

    // TURN_SCREEN_ON & Co: still ignoriert -> Wunsch deny, System default -> NICHT effektiv
    @Test fun appop_denyRequested_ignoreActual_nichtEffektiv() =
        assertFalse(OpEffect.isSetEffective(null, "deny", "ignore"))

    // --- "nicht setzbar": actual == null darf NIE als erfolgreich gelten ---
    @Test fun perm_actualNull_nichtEffektiv() =
        assertFalse(OpEffect.isSetEffective(PERM, "deny", null))

    @Test fun appop_actualNull_nichtEffektiv() =
        assertFalse(OpEffect.isSetEffective(null, "deny", null))
}
