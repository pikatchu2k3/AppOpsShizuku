package de.minipc.appopsshizuku.stealth

import de.minipc.appopsshizuku.PackageSigner
import de.minipc.appopsshizuku.stealth.VaultTrust.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VaultTrustTest {
    private val pkg = "de.minipc.calcyou"

    @Test fun ersteBegegnungMitRichtigemPaketWirdGepinnt() =
        assertEquals(Verdict.TRUST_AND_PIN, VaultTrust.decide(pkg, pkg, "aa", null))

    @Test fun gepinnteSignaturWirdAkzeptiert() = assertEquals(Verdict.TRUST, VaultTrust.decide(pkg, pkg, "aa", "aa"))

    @Test fun andereSignaturWirdAbgelehnt() = assertEquals(Verdict.REJECT, VaultTrust.decide(pkg, pkg, "bb", "aa"))

    @Test fun fremdeAuthorityWirdAbgelehnt() {
        assertEquals(Verdict.REJECT, VaultTrust.decide(pkg, "evil.app", "aa", null))
        assertEquals(Verdict.REJECT, VaultTrust.decide(pkg, null, "aa", "aa"))
    }

    @Test fun nichtLesbareSignaturWirdAbgelehnt() = assertEquals(Verdict.REJECT, VaultTrust.decide(pkg, pkg, null, null))

    @Test fun fingerabdruckIstStabilSortiertUndUnterscheidetZertifikate() {
        assertNull(PackageSigner.fingerprintOf(emptyList()))
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(4, 5, 6)
        assertEquals(PackageSigner.fingerprintOf(listOf(a, b)), PackageSigner.fingerprintOf(listOf(b, a)))
        assertNotEquals(PackageSigner.fingerprintOf(listOf(a)), PackageSigner.fingerprintOf(listOf(b)))
        assertEquals(64, PackageSigner.fingerprintOf(listOf(a))!!.length)
    }
}
