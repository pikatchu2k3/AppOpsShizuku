package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetUsageTest {

    @Test
    fun groessen() {
        assertEquals("512 B", NetUsage.formatBytes(512))
        assertEquals("1.0 KB", NetUsage.formatBytes(1024))
        assertEquals("38.0 MB", NetUsage.formatBytes(38L * 1024 * 1024))
        assertEquals("2.0 GB", NetUsage.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun verdaechtigNurBeiVielHintergrundUndLangeUngenutzt() {
        val big = 10L * 1024 * 1024
        assertTrue(NetUsage.isSuspicious(NetUsage.Row("p", "l", 0, big, lastUsedDays = 5)))
        assertFalse(NetUsage.isSuspicious(NetUsage.Row("p", "l", 0, big, lastUsedDays = 1)))
        assertFalse(NetUsage.isSuspicious(NetUsage.Row("p", "l", big, 1024, lastUsedDays = 10)))
        assertFalse(NetUsage.isSuspicious(NetUsage.Row("p", "l", 0, big, lastUsedDays = null)))
    }

    @Test
    fun verdaechtigeZuerst() {
        val susp = NetUsage.Row("susp", "s", 0, 20L * 1024 * 1024, lastUsedDays = 7)
        val big = NetUsage.Row("big", "b", 100L * 1024 * 1024, 100L * 1024 * 1024, lastUsedDays = 0)
        val small = NetUsage.Row("small", "s2", 1024, 1024, lastUsedDays = 0)
        assertEquals(listOf("susp", "big", "small"), NetUsage.sort(listOf(small, big, susp)).map { it.packageName })
    }
}
