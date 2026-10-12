package de.minipc.appopsshizuku

import de.minipc.appopsshizuku.ui.ToggleGuard
import org.junit.Assert.assertEquals
import org.junit.Test

class ToggleGuardTest {
    @Test fun userChangeRuns() {
        var n = 0
        ToggleGuard().ifUser { n++ }
        assertEquals(1, n)
    }

    @Test fun silentChangeSuppressesListener() {
        val g = ToggleGuard()
        var n = 0
        g.silently { g.ifUser { n++ } }
        assertEquals(0, n)
    }

    @Test fun nestedSilentAndRecoveryAfterException() {
        val g = ToggleGuard()
        var n = 0
        g.silently { g.silently { }; g.ifUser { n++ } }
        runCatching { g.silently { error("x") } }
        g.ifUser { n += 10 }
        assertEquals(10, n)
    }

    @Test fun silentlyReturnsValue() {
        assertEquals(5, ToggleGuard().silently { 5 })
    }
}
