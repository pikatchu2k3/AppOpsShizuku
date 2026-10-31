package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewBadgesTest {

    private val camera = AppOp("CAMERA", "Kamera", "android.permission.CAMERA")
    private val clipboard = AppOp("READ_CLIPBOARD", "Zwischenablage", null)

    @Test
    fun schalterModi() {
        assertEquals("allow", PermissionOverview.toggleMode(camera, true))
        assertEquals("deny", PermissionOverview.toggleMode(camera, false))
        assertEquals("default", PermissionOverview.toggleMode(clipboard, true))
        // ignore statt deny: deny laesst Apps beim Zugriff auf die Zwischenablage abstuerzen
        assertEquals("ignore", PermissionOverview.toggleMode(clipboard, false))
    }

    @Test
    fun sortierungUndSammelziele() {
        val rows = listOf(
            PermissionOverview.Row("b", "Bravo", false, "deny"),
            PermissionOverview.Row("a", "Alpha", false, "allow", OpAccess("CAMERA", lastAccessAgoMs = 5000)),
            PermissionOverview.Row("s", "System", true, "allow"),
            PermissionOverview.Row("c", "Charlie", false, "allow", OpAccess("CAMERA", lastAccessAgoMs = 100))
        )
        assertEquals(listOf("c", "a", "s", "b"), PermissionOverview.sort(rows).map { it.packageName })
        assertEquals(setOf("a", "c"), PermissionOverview.bulkTargets(rows).map { it.packageName }.toSet())
    }

    @Test
    fun symbole() {
        val set = AppBadges.compute(
            profile = mapOf("CAMERA" to "deny", "READ_CLIPBOARD" to "ignore", "RECORD_AUDIO" to "allow"),
            net = setOf(NetSwitch.OFFLINE, NetSwitch.BG_DATA),
            lifecycle = LifecycleEntry(onDemand = true, screenOffExit = true),
            quarantined = true
        )
        assertEquals(2, set.blockedCount)
        // Offline verdraengt "keine Hintergrunddaten", Bedarf verdraengt Bildschirm-Aus
        assertEquals(listOf(AppBadge.QUARANTINE, AppBadge.BLOCKED, AppBadge.OFFLINE, AppBadge.ON_DEMAND), set.badges)
        assertTrue(AppBadges.compute(emptyMap(), emptySet(), null).isEmpty)
    }
}
