package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemWideTest {

    @Test
    fun komponentenListe() {
        val raw = "com.a/.Svc:com.b/com.b.x.Listener:kaputt:com.a/com.a.Svc"
        val list = PowerfulAccess.parseComponents(raw)
        // relativer Name aufgeloest, Duplikat entfernt, Kaputtes ignoriert
        assertEquals(
            listOf(PowerfulAccess.Component("com.a", "com.a.Svc"), PowerfulAccess.Component("com.b", "com.b.x.Listener")),
            list
        )
        assertEquals("com.b/com.b.x.Listener", PowerfulAccess.without(raw, PowerfulAccess.Component("com.a", "com.a.Svc")))
        assertEquals(emptyList<PowerfulAccess.Component>(), PowerfulAccess.parseComponents("null"))
        assertEquals("", PowerfulAccess.without("com.a/.Svc", PowerfulAccess.Component("com.a", "com.a.Svc")))
    }

    @Test
    fun rollenInhaber() {
        assertEquals(listOf("com.google.android.apps.messaging"),
            PowerfulAccess.parseRoleHolders("com.google.android.apps.messaging\n\n"))
        assertEquals(emptyList<String>(), PowerfulAccess.parseRoleHolders("Exception: unknown role"))
    }

    @Test
    fun privateDns() {
        assertNull(PrivateDns.clean("null\n"))
        assertEquals("hostname", PrivateDns.clean(" hostname\n"))
        assertTrue(PrivateDns.isValidHost("dns.adguard-dns.com"))
        assertTrue(PrivateDns.PRESETS.all { PrivateDns.isValidHost(it.host) })
        assertFalse(PrivateDns.isValidHost("https://dns.example.com/dns-query"))
        assertFalse(PrivateDns.isValidHost("localhost"))
        assertFalse(PrivateDns.isValidHost("a b.com"))
    }

    @Test
    fun lockdownNurErteilteUndGewaehlte() {
        val plan = Lockdown.plan(
            mapOf("b" to setOf("CAMERA", "READ_CONTACTS"), "a" to setOf("RECORD_AUDIO")),
            Lockdown.DEFAULT
        )
        assertEquals(listOf("a" to "RECORD_AUDIO", "b" to "CAMERA"), plan)
        assertEquals("pkg" to "CAMERA", Lockdown.parseKey(Lockdown.key("pkg", "CAMERA")))
        assertNull(Lockdown.parseKey("kaputt"))
    }

    @Test
    fun lockdownOpsSindBerechtigungsOps() {
        for (name in Lockdown.CHOICES) {
            val op = PrivacyOps.PRIVACY_OPS.first { it.opName == name }
            assertTrue("$name braucht eine Runtime-Permission", op.permissionName != null)
        }
    }
}
