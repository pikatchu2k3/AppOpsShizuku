package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkProfileTest {

    private val listing = """
        Users:
        	UserInfo{0:Besitzer:4c13} running
        	UserInfo{10:Arbeit:30} running
        	UserInfo{11:Gast:404}
    """.trimIndent()

    @Test
    fun nutzerUndManagedFlag() {
        val users = WorkProfile.parseUsers(listing)
        assertEquals(3, users.size)
        val managed = WorkProfile.managedProfile(users)!!
        assertEquals(10, managed.id)
        assertEquals("Arbeit", managed.name)
        assertTrue(managed.managed)
        assertTrue(managed.running)
        assertFalse(users.first { it.id == 0 }.managed)
        assertFalse(users.first { it.id == 11 }.running)
    }

    @Test
    fun keinManagedProfil() {
        val users = WorkProfile.parseUsers("Users:\n\tUserInfo{0:Owner:13} running")
        assertNull(WorkProfile.managedProfile(users))
    }

    @Test
    fun neueUserId() {
        assertEquals(10, WorkProfile.parseCreatedUserId("Success: created user id 10"))
        assertNull(WorkProfile.parseCreatedUserId("Error: couldn't create User."))
    }

    @Test
    fun paketeImProfil() {
        val out = "package:com.a\npackage:com.b\nsomething else"
        assertEquals(setOf("com.a", "com.b"), WorkProfile.parsePackages(out))
    }

    @Test
    fun profilbesitzer() {
        val dump = "Device policy ...\nProfile Owner (User 10):\n    admin=ComponentInfo{de.minipc.appopsshizuku/de.minipc.appopsshizuku.ProfileOwnerReceiver}\n"
        assertEquals("de.minipc.appopsshizuku", WorkProfile.parseProfileOwner(dump, 10))
        assertNull(WorkProfile.parseProfileOwner(dump, 11))
    }
}
