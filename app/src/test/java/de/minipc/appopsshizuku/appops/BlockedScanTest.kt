package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.ui.OpLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedScanTest {

    @Test
    fun uidEbeneZaehlt() {
        assertTrue(BlockedScan.hasBlockedOp("Uid mode: CAMERA: ignore\nCAMERA: allow; time=+1h ago"))
    }

    @Test
    fun reineAppOpOhneUidZeileZaehlt() {
        // Fehlerfall vorher: gesperrte Zwischenablage blieb im Filter unsichtbar
        assertTrue(BlockedScan.hasBlockedOp("READ_CLIPBOARD: deny; rejectTime=+5m ago\nWAKE_LOCK: allow"))
        assertTrue(BlockedScan.hasBlockedOp("  WRITE_CLIPBOARD: ignore"))
    }

    @Test
    fun fremdeOpsAufPaketEbeneZaehlenNicht() {
        // WAKE_LOCK ist nicht im Katalog; COARSE_LOCATION ist eine Berechtigungs-Op (zaehlt nur ueber Uid mode)
        assertFalse(BlockedScan.hasBlockedOp("WAKE_LOCK: ignore\nCOARSE_LOCATION: ignore"))
    }

    @Test
    fun erlaubtUndLeerZaehlenNicht() {
        assertFalse(BlockedScan.hasBlockedOp("Uid mode: CAMERA: foreground\nREAD_CLIPBOARD: allow"))
        assertFalse(BlockedScan.hasBlockedOp("No operations."))
    }

    @Test
    fun jedeKatalogOpHatLokalisiertenNamen() {
        val missing = PrivacyOps.PRIVACY_OPS.map { it.opName }.filter { it !in OpLabels.covered }
        assertEquals(emptyList<String>(), missing)
    }
}
