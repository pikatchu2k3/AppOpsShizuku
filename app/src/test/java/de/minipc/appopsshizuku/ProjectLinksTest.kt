package de.minipc.appopsshizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectLinksTest {
    @Test fun obtainiumLinkWrapsGithub() {
        assertEquals("obtainium://add/https://github.com/pikatchu2k3/AppOpsShizuku", ProjectLinks.OBTAINIUM)
    }

    @Test fun webLinksAreHttps() {
        listOf(ProjectLinks.GITHUB, ProjectLinks.GITLAB, ProjectLinks.FDROID).forEach { assertTrue(it.startsWith("https://")) }
    }
}
