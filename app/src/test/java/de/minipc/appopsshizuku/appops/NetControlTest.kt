package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetControlTest {

    private val pkg = "net.xelnaga.exchanger"
    private val uid = 10641

    // --- Parsing (Formate vom OnePlus) ---

    @Test fun bgParse_denyAllow() {
        assertEquals(false, parseBackgroundNetworking("10641: deny\n", 10641))
        assertEquals(true, parseBackgroundNetworking("10641: allow", 10641))
    }

    @Test fun bgParse_andereUidOderMuell_istNull() {
        assertNull(parseBackgroundNetworking("10641: deny", 1064))
        assertNull(parseBackgroundNetworking("java.lang.IllegalStateException: sUidOwnerMap does not have entry for uid: 10641 (code 2)", 10641))
        assertNull(parseBackgroundNetworking("", 10641))
    }

    @Test fun pkgParse_denyAllow() {
        assertEquals(false, parsePackageNetworking("net.xelnaga.exchanger:deny", pkg))
        assertEquals(true, parsePackageNetworking("net.xelnaga.exchanger:allow\n", pkg))
    }

    @Test fun pkgParse_anderesPaket_istNull() {
        assertNull(parsePackageNetworking("net.xelnaga.exchanger.pro:deny", pkg))
        assertNull(parsePackageNetworking("", pkg))
    }

    @Test fun blacklistParse() {
        assertTrue(parseBlacklistContains("Restrict background blacklisted UIDs: 10001 10641", uid))
        assertFalse(parseBlacklistContains("Restrict background blacklisted UIDs: none", uid))
        assertFalse(parseBlacklistContains("", uid))
    }

    @Test fun blacklistParse_nurGanzeZahlen() {
        assertFalse(parseBlacklistContains("Restrict background blacklisted UIDs: 106410 210641", uid))
    }

    // --- Setzen mit Zuruecklesen ---

    @Test fun bgData_aus_und_ein() {
        val dev = FakeDevice()
        val net = NetControl(dev)
        assertEquals(false, net.read(NetSwitch.BG_DATA, pkg, uid))
        assertTrue(net.set(NetSwitch.BG_DATA, pkg, uid, true))
        assertEquals(true, net.read(NetSwitch.BG_DATA, pkg, uid))
        assertTrue(net.set(NetSwitch.BG_DATA, pkg, uid, false))
        assertTrue(uid !in dev.bgDenied)
    }

    @Test fun bgData_sUidOwnerMapFehler_aberWertGesetzt_zaehltAlsErfolg() {
        val dev = FakeDevice(bgSetThrowsOnce = true)
        assertTrue(NetControl(dev).set(NetSwitch.BG_DATA, pkg, uid, true))
        assertTrue(uid in dev.bgDenied)
    }

    @Test fun offline_aus_und_ein() {
        val dev = FakeDevice()
        val net = NetControl(dev)
        assertTrue(net.set(NetSwitch.OFFLINE, pkg, uid, true))
        assertTrue(pkg in dev.offline)
        assertTrue(net.set(NetSwitch.OFFLINE, pkg, uid, false))
        assertTrue(dev.offline.isEmpty())
    }

    @Test fun bgRestrict_hinzufuegen_und_entfernen() {
        val dev = FakeDevice()
        val net = NetControl(dev)
        assertTrue(net.set(NetSwitch.BG_RESTRICT, pkg, uid, true))
        assertEquals(true, net.read(NetSwitch.BG_RESTRICT, pkg, uid))
        assertTrue(net.set(NetSwitch.BG_RESTRICT, pkg, uid, false))
        assertEquals(false, net.read(NetSwitch.BG_RESTRICT, pkg, uid))
    }

    @Test fun geraetUebernimmtNicht_istFehler_trotzExit0() {
        val dev = FakeDevice(ignoreNetSets = true)
        val net = NetControl(dev)
        NetSwitch.values().forEach { assertFalse(it.name, net.set(it, pkg, uid, true)) }
    }

    @Test fun readAll_liestAlleDrei() {
        val dev = FakeDevice(bgDenied = mutableSetOf(uid), offline = mutableSetOf(pkg))
        val all = NetControl(dev).readAll(pkg, uid)
        assertEquals(mapOf(NetSwitch.BG_DATA to true, NetSwitch.OFFLINE to true, NetSwitch.BG_RESTRICT to false), all)
    }

    @Test fun befehle_sindArgumentListen_ohneKomponentenSchalter() {
        val dev = FakeDevice()
        val net = NetControl(dev)
        NetSwitch.values().forEach { net.set(it, pkg, uid, true) }
        assertTrue(dev.commands.none { it.firstOrNull() == "pm" })
        assertTrue(dev.commands.contains(listOf("cmd", "connectivity", "set-background-networking-enabled-for-uid", "10641", "false")))
        assertTrue(dev.commands.contains(listOf("cmd", "connectivity", "set-package-networking-enabled", "false", pkg)))
        assertTrue(dev.commands.contains(listOf("cmd", "netpolicy", "add", "restrict-background-blacklist", "10641")))
    }

    // --- Store-Kodierung ---

    @Test fun marks_roundtrip() {
        val all = NetSwitch.values().toSet()
        assertEquals(all, NetMarks.decode(NetMarks.encode(all)))
        assertEquals(setOf(NetSwitch.OFFLINE), NetMarks.decode(NetMarks.encode(setOf(NetSwitch.OFFLINE))))
        assertEquals(emptySet<NetSwitch>(), NetMarks.decode(NetMarks.encode(emptySet())))
    }

    @Test fun marks_decode_ignoriertMuell() {
        assertEquals(setOf(NetSwitch.BG_DATA), NetMarks.decode("BG_DATA,gibtsNicht,"))
        assertEquals(emptySet<NetSwitch>(), NetMarks.decode(null))
    }

    // --- Firewall-Kette (am Redmi gemessen, 10/2026) ---

    @Test fun chainParse() {
        assertEquals(true, parseChainEnabled("chain:enabled\n"))
        assertEquals(false, parseChainEnabled("chain:disabled"))
        assertNull(parseChainEnabled(""))
        assertNull(parseChainEnabled("java.lang.RuntimeException: irgendwas"))
    }

    @Test fun offline_ketteAus_wirdEingeschaltetUndGemeldet() {
        val dev = FakeDevice(chainEnabled = false)
        val net = NetControl(dev)
        assertTrue(net.set(NetSwitch.OFFLINE, pkg, uid, true))
        assertTrue("Kette muss jetzt an sein", dev.chainEnabled)
        assertTrue("wir haben sie eingeschaltet", net.lastSetEnabledChain)
        assertTrue(pkg in dev.offline)
    }

    @Test fun offline_ketteSchonAn_wirdNichtAlsUnsereMarkiert() {
        val dev = FakeDevice(chainEnabled = true)
        val net = NetControl(dev)
        assertTrue(net.set(NetSwitch.OFFLINE, pkg, uid, true))
        assertTrue(dev.chainEnabled)
        assertFalse("Kette war schon an - darf beim Zuruecksetzen nicht abgeschaltet werden", net.lastSetEnabledChain)
    }

    @Test fun offline_ketteLaesstSichNichtEinschalten_istFehlerTrotzDenyBit() {
        // Genau der Fall, der ohne Kettenpruefung als Erfolg durchginge: Deny-Bit liest sich als
        // `deny`, gesperrt ist aber nichts, weil die Kette aus bleibt.
        val dev = FakeDevice(chainEnabled = false, ignoreChainSet = true)
        val net = NetControl(dev)
        assertFalse(net.set(NetSwitch.OFFLINE, pkg, uid, true))
        assertTrue(pkg in dev.offline)
        assertFalse(dev.chainEnabled)
    }

    @Test fun offline_aus_schaltetKetteNichtSelbstAus() {
        val dev = FakeDevice(chainEnabled = false)
        val net = NetControl(dev)
        net.set(NetSwitch.OFFLINE, pkg, uid, true)
        assertTrue(net.set(NetSwitch.OFFLINE, pkg, uid, false))
        assertTrue("Kette bleibt an - darum kuemmert sich 'Alles zuruecksetzen'", dev.chainEnabled)
    }
}
