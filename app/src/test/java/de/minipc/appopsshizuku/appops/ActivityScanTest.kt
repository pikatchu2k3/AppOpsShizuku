package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityScanTest {

    @Test
    fun alarmWeckvorgaenge() {
        val dump = """
            Alarm Stats:
              com.whatsapp +1m2s743ms running, 3 wakeups:
              com.example.app +500ms running, 12 wakeups:
              com.sync.svc +10s running, 0 wakeups:
        """.trimIndent()
        val m = ActivityScan.parseAlarmWakeups(dump)
        assertEquals(3, m["com.whatsapp"])
        assertEquals(12, m["com.example.app"])
        // einzelne Namen ohne Punkt (z. B. "android") werden bewusst nicht als Paket gezaehlt
        assertEquals(0, m["com.sync.svc"])
    }

    @Test
    fun jobsGezaehlt() {
        val dump = """
            JOB #u0a101/5: ... service=ComponentInfo{com.example.app/com.example.Svc}
            JOB #u0a101/6: ... service=ComponentInfo{com.example.app/com.example.Other}
            JOB #u0a102/1: ... service=ComponentInfo{com.foo.bar/com.foo.Job}
        """.trimIndent()
        val m = ActivityScan.parseJobs(dump)
        assertEquals(2, m["com.example.app"])
        assertEquals(1, m["com.foo.bar"])
    }

    @Test
    fun alarmMitUidPraefix() {
        val dump = "Alarm Stats:\n  u0a42:com.google.android.gms +170ms running, 4 wakeups:\n  1000:com.sys.svc +1s running, 2 wakeups:"
        val m = ActivityScan.parseAlarmWakeups(dump)
        assertEquals(4, m["com.google.android.gms"])
        assertEquals(2, m["com.sys.svc"])
    }

    @Test
    fun jobsNeuesFormatOhneDoppelzaehlung() {
        val dump = """
            JOB #u0a105/12: 7ad2f8 com.example.app/.SyncJob
            JOB #u0a105/13: 8be3a9 com.example.app/.OtherJob
              service=ComponentInfo{com.example.app/com.example.app.SyncJob}
        """.trimIndent()
        // Variante 1 hat Treffer -> Variante 2 darf nicht zusaetzlich zaehlen
        assertEquals(2, ActivityScan.parseJobs(dump)["com.example.app"])
    }

    @Test
    fun sensorPakete() {
        val dump = "x package=com.sensor.app y\n z pkg=com.other.app w\n nothing here"
        assertEquals(setOf("com.sensor.app", "com.other.app"), ActivityScan.parseSensorPackages(dump))
    }

    @Test
    fun itemsSortiertUndOhneEigeneApp() {
        val items = ActivityScan.items(
            mapOf("a" to 1, "b" to 5, "me" to 9), ActivityScan.Kind.ALARM, "me"
        )
        assertEquals(listOf("b", "a"), items.map { it.packageName })
        assertTrue(items.none { it.packageName == "me" })
    }
}
