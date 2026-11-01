package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTemplateTest {

    @Test
    fun nurKatalogOps() {
        val known = PrivacyOps.PRIVACY_OPS.map { it.opName }.toSet()
        for (id in ProfileTemplate.Id.values()) {
            assertTrue(ProfileTemplate.opsFor(id).keys.all { it in known })
            assertTrue(ProfileTemplate.opsFor(id).isNotEmpty())
        }
    }

    @Test
    fun clipboardUndHintergrundNieDeny() {
        // Nur diese reinen AppOps lassen Apps bei deny abstuerzen -> hier immer ignore
        val mustIgnore = setOf("READ_CLIPBOARD", "WRITE_CLIPBOARD", "RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND")
        for (id in ProfileTemplate.Id.values()) {
            for ((op, mode) in ProfileTemplate.opsFor(id)) {
                if (op in mustIgnore) assertEquals("$id/$op muss ignore sein", "ignore", mode)
            }
        }
    }

    @Test
    fun vorlageErteiltNichtsNeues() {
        for (id in ProfileTemplate.Id.values()) {
            assertTrue(ProfileTemplate.opsFor(id).values.none { it == "allow" || it == "default" })
        }
    }

    @Test
    fun mergeUeberschreibt() {
        val current = mapOf("CAMERA" to "allow", "READ_CONTACTS" to "allow")
        val merged = ProfileTemplate.merge(current, ProfileTemplate.Id.GAME)
        assertEquals("allow", merged["CAMERA"]) // nicht in der Vorlage -> bleibt
        assertEquals("deny", merged["READ_CONTACTS"]) // Vorlage gewinnt
    }
}
