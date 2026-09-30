package de.minipc.appopsshizuku.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fehlerheuristik: exit 0 kann trotzdem ein Fehler sein, wenn die Meldung
 * (SecurityException / "cannot change" / "Error") in stdout/stderr steht.
 */
class ShellResultTest {

    @Test fun exit0_leereAusgabe_keinFehler() =
        assertFalse(ShellResult(0, "", "").isFailure())

    @Test fun exit0_securityException_imStderr_istFehler() =
        assertTrue(
            ShellResult(0, "", "SecurityException: Shell cannot change component state").isFailure()
        )

    @Test fun exit0_error_imStdout_istFehler() =
        assertTrue(ShellResult(0, "Error", "").isFailure())

    @Test fun exitUngleich0_istFehler() =
        assertTrue(ShellResult(1, "", "Unknown op").isFailure())

    @Test fun exit0_successAusgabe_keinFehler() =
        assertFalse(ShellResult(0, "Success", "").isFailure())
}
