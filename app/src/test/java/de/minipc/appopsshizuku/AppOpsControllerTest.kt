package de.minipc.appopsshizuku

import de.minipc.appopsshizuku.appops.AppOp
import de.minipc.appopsshizuku.appops.AppOpsController
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOpsControllerTest {

    class FakeBackend : ShellBackend {
        val commands = mutableListOf<List<String>>()
        var result: ShellResult = ShellResult(0, "", "")
        override fun isAvailable(): Boolean = true
        override fun hasPermission(): Boolean = true
        override fun exec(vararg args: String): ShellResult {
            commands.add(args.toList())
            return result
        }
    }

    private val cameraOp = AppOp("CAMERA", "Kamera", "android.permission.CAMERA")
    private val clipboardOp = AppOp("READ_CLIPBOARD", "Zwischenablage lesen", null)

    // ---------- setMode: Runtime-Permission-Ops ----------

    @Test
    fun `setMode deny on permission op sends pm revoke`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "", "")
        val controller = AppOpsController(backend)

        val res = controller.setMode("com.example.app", cameraOp, "deny")

        assertEquals(0, res.exitCode)
        assertEquals(listOf("pm", "revoke", "com.example.app", "android.permission.CAMERA"), backend.commands[0])
    }

    @Test
    fun `setMode ignore on permission op sends pm revoke`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "", "")
        val controller = AppOpsController(backend)

        controller.setMode("com.example.app", cameraOp, "ignore")

        assertEquals(listOf("pm", "revoke", "com.example.app", "android.permission.CAMERA"), backend.commands[0])
    }

    @Test
    fun `setMode allow on permission op sends pm grant`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "", "")
        val controller = AppOpsController(backend)

        controller.setMode("com.example.app", cameraOp, "allow")

        assertEquals(listOf("pm", "grant", "com.example.app", "android.permission.CAMERA"), backend.commands[0])
    }

    @Test
    fun `setMode default on permission op sends pm grant`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "", "")
        val controller = AppOpsController(backend)

        controller.setMode("com.example.app", cameraOp, "default")

        assertEquals(listOf("pm", "grant", "com.example.app", "android.permission.CAMERA"), backend.commands[0])
    }

    // ---------- setMode: reine AppOps (Clipboard) ----------

    @Test
    fun `setMode deny on clipboard op sends cmd appops set`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "", "")
        val controller = AppOpsController(backend)

        controller.setMode("com.example.app", clipboardOp, "deny")

        assertEquals(
            listOf("cmd", "appops", "set", "com.example.app", "READ_CLIPBOARD", "deny"),
            backend.commands[0]
        )
    }

    // ---------- getMode-Parsing ----------

    @Test
    fun `getMode parses Uid mode foreground as allow`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "Uid mode: CAMERA: foreground\nCAMERA: allow", "")
        val controller = AppOpsController(backend)

        assertEquals("allow", controller.getMode("com.example.app", cameraOp))
    }

    @Test
    fun `getMode parses Uid mode ignore as ignore`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "Uid mode: CAMERA: ignore\nCAMERA: allow", "")
        val controller = AppOpsController(backend)

        assertEquals("ignore", controller.getMode("com.example.app", cameraOp))
    }

    @Test
    fun `getMode with No operations returns default`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "No operations.\nDefault mode: allow", "")
        val controller = AppOpsController(backend)

        assertEquals("default", controller.getMode("com.example.app", clipboardOp))
    }

    @Test
    fun `getMode with No mode returns default`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "No mode", "")
        val controller = AppOpsController(backend)

        assertEquals("default", controller.getMode("com.example.app", cameraOp))
    }

    @Test
    fun `getMode with exit code non zero returns null`() {
        val backend = FakeBackend()
        backend.result = ShellResult(1, "", "Unknown op")
        val controller = AppOpsController(backend)

        assertEquals(null, controller.getMode("com.example.app", cameraOp))
    }

    // ---------- restoreAll / Noop ----------

    @Test
    fun `restoreAll grants permission ops and resets clipboard ops`() {
        val backend = FakeBackend()
        backend.result = ShellResult(0, "", "")
        val controller = AppOpsController(backend)
        val ops = listOf(cameraOp, clipboardOp)

        val results = controller.restoreAll("com.example.app", ops)

        assertEquals(2, results.size)
        assertEquals(
            listOf("pm", "grant", "com.example.app", "android.permission.CAMERA"),
            backend.commands[0]
        )
        assertEquals(
            listOf("cmd", "appops", "set", "com.example.app", "READ_CLIPBOARD", "default"),
            backend.commands[1]
        )
    }

    @Test
    fun `NoopBackend returns exitCode -1`() {
        val controller = AppOpsController(NoopBackend())
        val res = controller.setMode("com.example.app", cameraOp, "deny")
        assertEquals(-1, res.exitCode)
    }

    @Test
    fun `probe is false when op not readable`() {
        val backend = FakeBackend()
        backend.result = ShellResult(1, "", "Unknown op")
        val controller = AppOpsController(backend)

        assertTrue(!controller.probe("com.example.app", cameraOp))
    }
}
