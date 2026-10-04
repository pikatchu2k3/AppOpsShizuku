package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TrackerCodeScanTest {

    @get:Rule val tmp = TemporaryFolder()

    private val segment = "com/segment/analytics"

    private fun contains(data: String, marker: String = segment, chunk: Int = 64 * 1024) =
        TrackerCodeScan.containsMarker(ByteArrayInputStream(data.toByteArray(Charsets.ISO_8859_1)), marker, chunk)

    // --- Marker-Matcher ---

    @Test fun treffer_amAnfang_inDerMitte_amEnde() {
        assertTrue(contains("com/segment/analytics/Analytics;"))
        assertTrue(contains("xx\u0000Lcom/segment/analytics/Analytics;\u0000yy"))
        assertTrue(contains("Lcom/segment/analytics")) // Stromende = Namensgrenze
    }

    @Test fun trefferUeberJedeChunkGrenze() {
        // Marker an jeder Startposition, Chunk-Groessen 1..40 (u.a. kleiner als der Marker).
        for (chunk in 1..40) for (pos in 0..60) {
            val data = "a".repeat(pos) + "Lcom/segment/analytics/X;" + "b".repeat(30)
            assertTrue("chunk=$chunk pos=$pos", contains(data, chunk = chunk))
        }
    }

    @Test fun markerLaengerAlsChunk() {
        assertTrue(contains("..Lcom/segment/analytics/X;", chunk = 3))
        assertFalse(contains("..Lcom/segment/analyti", chunk = 3))
    }

    @Test fun aehnlicherName_trifftNicht_auchNichtUeberChunkGrenze() {
        for (chunk in 1..40) for (pos in 0..40) {
            val pad = "a".repeat(pos)
            assertFalse("x chunk=$chunk pos=$pos", contains("${pad}Lcom/segment/analyticsX/Y;", chunk = chunk))
            assertFalse("_ chunk=$chunk pos=$pos", contains("${pad}com/segment/analytics_v2", chunk = chunk))
            assertFalse("9 chunk=$chunk pos=$pos", contains("${pad}com/segment/analytics9", chunk = chunk))
            assertFalse("kurz chunk=$chunk pos=$pos", contains("${pad}com/segment/analytic/", chunk = chunk))
        }
    }

    @Test fun namensgrenze_definition() {
        // erlaubt hinter dem Marker: Ende, '/', ';', '$', '.', Nullbyte
        for (tail in listOf("", "/A;", ";", "\$1", ".Analytics", "\u0000")) assertTrue(tail, contains("Lcom/segment/analytics$tail"))
        // verboten: Buchstabe, Ziffer, Unterstrich
        for (tail in listOf("x", "X", "0", "_")) assertFalse(tail, contains("Lcom/segment/analytics$tail"))
        // Marker mit '/' am Ende ist selbst begrenzt: Buchstabe danach ist gewollt
        assertTrue(contains("Lcom/facebook/ads/AdView;", "com/facebook/ads/"))
        assertFalse(contains("Lcom/facebook/adsx/AdView;", "com/facebook/ads/"))
        // vorne wird nichts geprueft: umbenannte Kopie ist trotzdem das SDK
        assertTrue(contains("Lcom/meineapp/shaded/com/segment/analytics/Analytics;"))
    }

    @Test fun leereDatei_undLeererMarker_keinTreffer() {
        assertFalse(contains(""))
        assertFalse(contains("irgendwas", marker = ""))
    }

    @Test fun nurByteVergleich_keineTextDekodierung() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xC3.toByte(), 0x00) + "com/segment/analytics/".toByteArray() + byteArrayOf(0x80.toByte())
        assertTrue(TrackerCodeScan.containsMarker(ByteArrayInputStream(bytes), segment, 4))
    }

    @Test fun mehrereSonden_einDurchlauf_unabhaengigVonChunkGroesse() {
        val probes = listOf(
            TrackerProbe("Segment", listOf(segment)),
            TrackerProbe("Amplitude", listOf("com/amplitude/api")),
            TrackerProbe("Zwei Marker", listOf("com/nix/da", "io/sentry/android/core"))
        )
        val data = "Lcom/segment/analytics/A;..Lio/sentry/android/core/B;..Lcom/amplitudeX/api;"
        for (chunk in 1..30) {
            val hits = TrackerCodeScan.findProbes(ByteArrayInputStream(data.toByteArray()), probes, chunk)
            assertEquals("chunk=$chunk", setOf("Segment", "Zwei Marker"), hits)
        }
    }

    // --- Zip-Scan ---

    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, content) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(content.toByteArray(Charsets.ISO_8859_1))
                z.closeEntry()
            }
        }
        return f
    }

    private val probes = listOf(
        TrackerProbe("Segment", listOf(segment)),
        TrackerProbe("Amplitude", listOf("com/amplitude/api"))
    )

    @Test fun zip_markerInZweiterDex_wirdGefunden() {
        val apk = zip(
            "a.apk",
            "classes.dex" to "dex\n035\u0000 nichts hier",
            "classes2.dex" to "Lcom/segment/analytics/Analytics;"
        )
        val r = TrackerCodeScan.scanWithInfo(listOf(apk), probes)
        assertEquals(setOf("Segment"), r.found)
        assertEquals(setOf("Segment"), TrackerCodeScan.scan(listOf(apk), probes))
        assertTrue(r.complete)
        assertEquals(1, r.filesScanned)
    }

    @Test fun zip_dexNamen_unterordner_undZweistellig_nichtAndereDateien() {
        val apk = zip(
            "b.apk",
            "classes10.dex" to "Lcom/amplitude/api/Amplitude;",
            "sub/dir/classes3.dex" to "Lcom/segment/analytics/A;"
        )
        assertEquals(setOf("Segment", "Amplitude"), TrackerCodeScan.scan(listOf(apk), probes))

        val keine = zip(
            "c.apk",
            "assets/classes.dex.txt" to "Lcom/segment/analytics/A;",
            "mclasses.dex" to "Lcom/segment/analytics/A;",
            "classes.dex.bak" to "Lcom/amplitude/api/A;",
            "res/raw/notes.txt" to "com/segment/analytics"
        )
        assertTrue(TrackerCodeScan.scan(listOf(keine), probes).isEmpty())
    }

    @Test fun zip_keinTreffer() {
        val apk = zip("d.apk", "classes.dex" to "Lcom/segment/analyticsx/A;", "classes2.dex" to "nix")
        val r = TrackerCodeScan.scanWithInfo(listOf(apk), probes)
        assertTrue(r.found.isEmpty())
        assertTrue(r.complete)
    }

    @Test fun splits_werdenAlleDurchsucht() {
        val base = zip("base.apk", "classes.dex" to "nix")
        val split = zip("split_x.apk", "classes.dex" to "Lcom/amplitude/api/A;")
        assertEquals(setOf("Amplitude"), TrackerCodeScan.scan(listOf(base, split), probes))
    }

    /**
     * Rueckfall-Schutz gegen einen Fehlalarm aus der Praxis: Die Werbe-ID-Bibliothek
     * (AdvertisingIdClient) steckt in vielen Apps, die gar keine Werbung zeigen (an eBay belegt).
     * Der AdMob-Marker darf darauf NICHT anschlagen, der eigene Eintrag aber schon.
     */
    @Test fun werbeIdBibliothek_istNichtAdMob_aberEigenerEintrag() {
        val apk = zip(
            "base.apk",
            "classes.dex" to "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient;"
        )
        val echteSonden = TrackerCatalog.probes
        val hits = TrackerCodeScan.scan(listOf(apk), echteSonden)
        assertFalse("AdMob darf hier nicht anschlagen", "Google Werbung (AdMob)" in hits)
        assertTrue("Werbe-ID muss erkannt werden", "Google Werbe-ID (AdvertisingIdClient)" in hits)

        // Und mit echtem AdMob-Code schlaegt der AdMob-Eintrag an.
        val mitWerbung = zip(
            "base_mit_werbung.apk",
            "classes.dex" to "Lcom/google/android/gms/ads/MobileAds;Lcom/google/android/gms/ads/AdView;"
        )
        assertTrue("Google Werbung (AdMob)" in TrackerCodeScan.scan(listOf(mitWerbung), echteSonden))
    }

    @Test fun defektesZip_undFehlendeDatei_stuerztNichtAb_gutesZipWirdTrotzdemGelesen() {
        val kaputt = tmp.newFile("kaputt.apk").apply { writeText("das ist kein zip, nur Text") }
        val fehlt = File(tmp.root, "gibtsnicht.apk")
        val gut = zip("gut.apk", "classes.dex" to "Lcom/segment/analytics/A;")
        val log = mutableListOf<String>()
        val r = TrackerCodeScan.scanWithInfo(listOf(kaputt, fehlt, gut), probes, log = { log += it })
        assertEquals(setOf("Segment"), r.found)
        assertEquals(listOf("kaputt.apk", "gibtsnicht.apk"), r.failedFiles)
        assertEquals(1, r.filesScanned)
        assertFalse(r.complete)
        assertEquals(2, log.size)
        assertTrue(log.all { "apk" in it })
    }

    @Test fun nurDefekteDatei_ergebnisLeer_nichtVollstaendig() {
        val r = TrackerCodeScan.scanWithInfo(listOf(File(tmp.root, "weg.apk")), probes)
        assertTrue(r.found.isEmpty())
        assertFalse(r.complete)
        assertFalse(TrackerCodeScan.scanWithInfo(emptyList(), probes).complete)
    }

    @Test fun fruehAbbruch_wennAllesGefunden() {
        val grosser = "x".repeat(200_000)
        val apk = zip(
            "e.apk",
            "classes.dex" to "Lcom/segment/analytics/A;Lcom/amplitude/api/B;" + grosser,
            "classes2.dex" to grosser
        )
        val r = TrackerCodeScan.scanWithInfo(listOf(apk), probes)
        assertEquals(setOf("Segment", "Amplitude"), r.found)
        assertTrue("gelesen=${r.bytesRead}", r.bytesRead < 100_000)
    }

    @Test fun obergrenze_wirdGemeldet() {
        val padding = "x".repeat(50_000)
        val apk = zip("f.apk", "classes.dex" to padding, "classes2.dex" to padding + "Lcom/segment/analytics/A;")
        val log = mutableListOf<String>()
        val r = TrackerCodeScan.scanWithInfo(listOf(apk), probes, maxBytes = 60_000, log = { log += it })
        assertTrue(r.truncated)
        assertFalse(r.complete)
        assertTrue(r.found.isEmpty())
        assertTrue(r.bytesRead <= 60_000)
        assertTrue(log.any { "Obergrenze" in it })
        // ohne knappe Grenze: derselbe Treffer wird gefunden
        assertEquals(setOf("Segment"), TrackerCodeScan.scan(listOf(apk), probes))
    }

    // --- Katalog-Marker ---

    @Test fun katalog_markerSindKlassenpfadeMitSlashes_undNamenEindeutig() {
        val names = TrackerCatalog.sdks.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(names, TrackerCatalog.probes.map { it.id })
        for (sdk in TrackerCatalog.sdks) {
            assertTrue(sdk.name, sdk.markers.isNotEmpty())
            sdk.markers.forEach { assertTrue(it, '/' in it && '.' !in it && !it.startsWith("/")) }
        }
    }

    @Test fun katalog_neuErkennbareSdks_habenKeineManifestKlassen_aberMarker() {
        val expected = mapOf(
            "Segment" to segment,
            "Amplitude" to "com/amplitude/api",
            "Facebook SDK (App-Events)" to "com/facebook/appevents",
            "Google Analytics (alt)" to "com/google/android/gms/analytics"
        )
        for ((name, marker) in expected) {
            val sdk = TrackerCatalog.sdks.first { it.name == name }
            assertTrue(name, marker in sdk.markers)
        }
        assertTrue(TrackerCatalog.sdks.first { it.name == "Segment" }.classes.isEmpty())
        assertTrue(TrackerCatalog.sdks.first { it.name == "Amplitude" }.classes.isEmpty())
    }

    @Test fun katalog_gesamteMarkerTabelle_findetEchteKlassenpfade() {
        val dex = listOf(
            "com/segment/analytics/Analytics", "com/amplitude/api/Amplitude", "com/facebook/appevents/AppEventsLogger",
            "com/google/android/gms/analytics/Tracker", "com/google/firebase/analytics/FirebaseAnalytics",
            "com/google/android/datatransport/Transport", "com/google/firebase/crashlytics/FirebaseCrashlytics",
            "com/google/android/gms/ads/MobileAds", "com/facebook/ads/AdView", "com/appsflyer/AppsFlyerLib",
            "com/google/android/gms/ads/identifier/AdvertisingIdClient",
            "com/adjust/sdk/Adjust", "com/kochava/base/Tracker", "io/sentry/android/core/SentryAndroid",
            "com/mixpanel/android/mpmetrics/MixpanelAPI", "com/braze/Braze"
        ).joinToString("") { "L$it;\u0000" }
        val hits = TrackerCodeScan.findProbes(ByteArrayInputStream(dex.toByteArray()), TrackerCatalog.probes, 16)
        assertEquals(TrackerCatalog.sdks.map { it.name }.toSet(), hits)
    }
}
