package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRevokeFgsTest {
    private val op = AutoRevoke.OP

    @Test
    fun ignoreIstAusgenommenNichtBlockiert() {
        assertEquals(AutoRevoke.State.EXEMPT, AutoRevoke.parse(0, "$op: ignore\n"))
        assertEquals(AutoRevoke.State.EXEMPT, AutoRevoke.parse(0, "Uid mode: $op: ignore"))
        assertEquals(AutoRevoke.State.ACTIVE, AutoRevoke.parse(0, "No operations.\nDefault mode: default\n"))
        assertEquals(AutoRevoke.State.ACTIVE, AutoRevoke.parse(0, "$op: allow"))
        assertEquals(AutoRevoke.State.UNSUPPORTED, AutoRevoke.parse(255, "", "Error: Unknown operation string: $op"))
        assertEquals(AutoRevoke.State.UNSUPPORTED, AutoRevoke.parse(0, ""))
    }

    private val dump = """
        ACTIVITY MANAGER SERVICES (dumpsys activity services)
          * ServiceRecord{9b96f5 u0 a.b/a.b.AccService c:android}
            packageName=a.b
          * ServiceRecord{3ed890b u0 a.b/a.b.FloatingService c:com.android.shell}
            isForeground=true foregroundId=1001 types=40000000
    """.trimIndent()

    @Test
    fun vordergrunddienstErkennung() {
        assertTrue(ForegroundServices.isRunning(dump, "a.b"))
        assertFalse(ForegroundServices.isRunning(dump, "a.bc"))
        assertFalse(ForegroundServices.isRunning(dump.replace("isForeground=true", "isForeground=false"), "a.b"))
        assertFalse(ForegroundServices.isRunning("  (nothing)", "a.b"))
        val real = "ACTIVITY MANAGER SERVICES (dumpsys activity services)\n" +
            "  * ServiceRecord{9b96f5 u0 com.joaohouto.assistivemenutool.debug/com.joaohouto.assistivemenutool.AssistiveMenuAccessibilityService c:android}\n" +
            "    packageName=com.joaohouto.assistivemenutool.debug\n" +
            "  * ServiceRecord{3ed890b u0 com.joaohouto.assistivemenutool.debug/com.joaohouto.assistivemenutool.FloatingButtonService c:com.android.shell}\n" +
            "    isForeground=true foregroundId=1001 types=40000000 fgsHasTimeLimitedType=false foregroundNoti=Notification(channel=assistive_menu_tool)\n"
        assertTrue(ForegroundServices.isRunning(real, "com.joaohouto.assistivemenutool.debug")) // Format: Redmi Note 4, Android 14
    }

    private val apps = setOf("a")
    private val grace = WhileInUse.GRACE_MS

    @Test
    fun fgsLaeuftAusnahmeAn_keinRevoke_dannEndeRevoke() {
        val left = mutableMapOf<String, Long>()
        var fgs = true
        val d = { now: Long, screen: Boolean ->
            WhileInUse.decide(now, apps, mapOf("a" to false), setOf("a"), left, screen) { fgs }
        }
        assertEquals(emptyMap<String, WhileInUse.Action>(), d(0, true))
        assertEquals(emptyMap<String, WhileInUse.Action>(), d(grace * 3, true))
        fgs = false
        assertEquals(mapOf("a" to WhileInUse.Action.REVOKE), d(grace * 3 + 1, true))
        fgs = true // Bildschirm aus: auch dann kein Revoke, solange der Dienst laeuft
        assertEquals(emptyMap<String, WhileInUse.Action>(), d(grace * 4, false))
    }

    @Test
    fun ausnahmeAus_revokeNachFrist_grantBleibt() {
        val left = mutableMapOf<String, Long>()
        val none = { _: String -> false }
        assertEquals(emptyMap<String, WhileInUse.Action>(),
            WhileInUse.decide(0, apps, mapOf("a" to false), setOf("a"), left, true, none))
        assertEquals(mapOf("a" to WhileInUse.Action.REVOKE),
            WhileInUse.decide(grace, apps, mapOf("a" to false), setOf("a"), left, true, none))
        assertEquals(mapOf("a" to WhileInUse.Action.GRANT),
            WhileInUse.decide(0, apps, mapOf("a" to true), emptySet(), mutableMapOf(), true) { true })
    }
}
