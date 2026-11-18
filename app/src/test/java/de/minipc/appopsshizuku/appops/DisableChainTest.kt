package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisableChainTest {

    @Test
    fun gruendeAusEchtenGeraeteantworten() {
        // Gemessen auf OnePlus / Android 17
        assertEquals(
            "only root can delete system app for a particular user",
            UserRemoval.reason("Failure [only root can delete system app for a particular user]")
                ?.removeSurrounding("[", "]")
        )
        assertEquals(
            "Cannot disable a protected package: com.x",
            UserRemoval.reason("java.lang.SecurityException: Cannot disable a protected package: com.x")
        )
        // "new state: default" ist keine Fehlermeldung -> null (Zuruecklesen entscheidet)
        assertNull(UserRemoval.reason("Package com.oplus.gleanerservice new state: default"))
    }

    @Test
    fun deaktiviertZustaende() {
        assertTrue(DisableChain.isDisabledState(2))
        assertTrue(DisableChain.isDisabledState(3))
        assertTrue(DisableChain.isDisabledState(4)) // bis zur Nutzung
        assertFalse(DisableChain.isDisabledState(0))
        assertFalse(DisableChain.isDisabledState(1))
        assertFalse(DisableChain.isDisabledState(null))
    }

    @Test
    fun reihenfolgeUndBefehle() {
        assertEquals(DisableChain.Step.DISABLE_USER, DisableChain.ORDER.first())
        assertEquals(DisableChain.Step.NEUTRALIZE, DisableChain.ORDER.last())
        assertTrue(DisableChain.ORDER.indexOf(DisableChain.Step.HIDE_AS_OWNER) < DisableChain.ORDER.indexOf(DisableChain.Step.NEUTRALIZE))
        assertEquals(listOf("pm", "disable-until-used", "--user", "0", "p"), DisableChain.command(DisableChain.Step.DISABLE_UNTIL_USED, "p"))
        assertEquals(listOf("pm", "uninstall", "-k", "--user", "0", "p"), DisableChain.command(DisableChain.Step.REMOVE_FOR_USER, "p"))
        assertNull(DisableChain.command(DisableChain.Step.HIDE_AS_OWNER, "p"))
    }

    @Test
    fun gewinner() {
        val a = listOf(
            DisableChain.Attempt(DisableChain.Step.DISABLE_UNTIL_USED, false, "x"),
            DisableChain.Attempt(DisableChain.Step.REMOVE_FOR_USER, true, null)
        )
        assertEquals(DisableChain.Step.REMOVE_FOR_USER, DisableChain.winner(a))
        assertNull(DisableChain.winner(a.take(1)))
    }

    @Test
    fun kritischeAppsNieAngeboten() {
        assertEquals(UserRemoval.Block.CRITICAL, UserRemoval.blockReason("com.android.systemui", "me", null, null))
        assertEquals(UserRemoval.Block.CRITICAL, UserRemoval.blockReason("com.android.providers.calendar", "me", null, null))
        assertEquals(UserRemoval.Block.HOME, UserRemoval.blockReason("net.oneplus.launcher", "me", "net.oneplus.launcher", null))
        assertEquals(UserRemoval.Block.KEYBOARD, UserRemoval.blockReason("com.kb", "me", null, UserRemoval.keyboardPackage("com.kb/.Ime")))
        assertEquals(UserRemoval.Block.OWN, UserRemoval.blockReason("me", "me", null, null))
        assertNull(UserRemoval.blockReason("com.oplus.gleanerservice", "me", "net.oneplus.launcher", "com.kb"))
        assertNull(UserRemoval.blockReason("com.heytap.browser", "me", null, null))
    }

    @Test
    fun geraeteinhaberAntworten() {
        assertEquals(DeviceOwner.SetupResult.OK, DeviceOwner.classify("Success: Device owner set to package ComponentInfo{x/y}"))
        assertEquals(DeviceOwner.SetupResult.ACCOUNTS,
            DeviceOwner.classify("java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device"))
        assertEquals(DeviceOwner.SetupResult.USERS,
            DeviceOwner.classify("java.lang.IllegalStateException: Not allowed to set the device owner because there are already several users on the device"))
        assertEquals(DeviceOwner.SetupResult.ALREADY_SET,
            DeviceOwner.classify("java.lang.IllegalStateException: Trying to set the device owner, but device owner is already set."))
        assertEquals(DeviceOwner.SetupResult.OTHER, DeviceOwner.classify("Error: something else"))
    }
}
