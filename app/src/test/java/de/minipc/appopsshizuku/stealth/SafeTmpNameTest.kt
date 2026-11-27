package de.minipc.appopsshizuku.stealth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pfade landen in `sh -c "..."` - nur harmlose Zeichen. */
class SafeTmpNameTest {
    @Test fun normaleNamen() {
        assertTrue(StealthStealer.safeTmpName("base.apk"))
        assertTrue(StealthStealer.safeTmpName("split_config.arm64_v8a.apk"))
        assertTrue(StealthStealer.safeTmpName("com.example.app"))
        assertTrue(StealthStealer.safeTmpName("my-app_1.2"))
    }

    @Test fun gefaehrlicheNamen() {
        for (bad in listOf("", "a b", "a;b", "a\$(id)", "a`id`", "a|b", "a&b", "a>b", "a\nb", "a'b", "a\"b", "../x", "a/b")) {
            assertFalse("'$bad'", StealthStealer.safeTmpName(bad))
        }
    }
}
