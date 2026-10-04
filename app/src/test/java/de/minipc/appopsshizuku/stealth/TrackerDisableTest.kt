package de.minipc.appopsshizuku.stealth

import de.minipc.appopsshizuku.appops.TrackerDisable
import de.minipc.appopsshizuku.appops.TrackerGroup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tracker abschalten: `android:enabled=false` an Katalog-Komponenten, Schutzliste, Idempotenz,
 * Firebase-Meta-Data-Schalter, Vorher/Nachher-Vergleich. Manifeste kommen von aapt2 (echtes AXML),
 * geprueft wird zusaetzlich mit `aapt2 dump xmltree` (derselbe Parser-Weg wie beim Installieren).
 */
class TrackerDisableTest {

    private val xml = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.test.trk">
            <application android:label="T">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>
                <activity android:name="com.google.android.gms.ads.AdActivity" android:exported="false"
                    android:configChanges="orientation"/>
                <provider android:name="com.google.android.gms.ads.MobileAdsInitProvider"
                    android:authorities="com.test.trk.mobileadsinitprovider" android:exported="false"
                    android:initOrder="100"/>
                <activity android:name="com.facebook.ads.AudienceNetworkActivity" android:exported="false"/>
                <service android:name="com.google.android.gms.measurement.AppMeasurementService"
                    android:enabled="true" android:exported="false"/>
                <receiver android:name="com.google.android.gms.measurement.AppMeasurementReceiver"
                    android:enabled="true" android:exported="true"/>
                <service android:name="com.google.android.gms.measurement.AppMeasurementJobService"
                    android:permission="android.permission.BIND_JOB_SERVICE" android:exported="false"/>
                <receiver android:name="com.appsflyer.SingleInstallBroadcastReceiver" android:exported="true"/>
                <service android:name="com.google.firebase.messaging.FirebaseMessagingService" android:exported="false"/>
                <service android:name="com.mixpanel.android.mpmetrics.MixpanelFCMMessagingService" android:exported="false"/>
                <service android:name="androidx.work.impl.background.systemjob.SystemJobService"
                    android:permission="android.permission.BIND_JOB_SERVICE" android:exported="true"/>
                <activity android:name="com.android.billingclient.api.ProxyBillingActivity" android:exported="false"/>
                <activity android:name="com.google.android.gms.auth.api.signin.internal.SignInHubActivity" android:exported="false"/>
                <receiver android:name="com.facebook.CurrentAccessTokenExpirationBroadcastReceiver" android:exported="false"/>
                <receiver android:name="com.facebook.AuthenticationTokenManager${'$'}CurrentAuthenticationTokenChangedBroadcastReceiver"
                    android:exported="false"/>
                <service android:name="com.google.android.datatransport.runtime.backends.TransportBackendDiscovery"
                    android:exported="false"/>
                <service android:name=".TrackerSvc" android:exported="false"/>
                <receiver android:name="Bare" android:exported="false"/>
                <provider android:name=".Files" android:authorities="com.test.trk.provider" android:exported="false"/>
                <service android:name="com.google.firebase.components.ComponentDiscoveryService" android:exported="false">
                    <meta-data android:name="com.google.firebase.components:com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar"
                        android:value="com.google.firebase.components.ComponentRegistrar"/>
                    <meta-data android:name="com.google.firebase.components:com.google.firebase.crashlytics.CrashlyticsRegistrar"
                        android:value="com.google.firebase.components.ComponentRegistrar"/>
                </service>
                <meta-data android:name="firebase_analytics_collection_enabled" android:value="true"/>
            </application>
        </manifest>
    """.trimIndent()

    private val original: ByteArray by lazy { BinaryXmlTestData.compile(xml) }

    private fun states(m: ByteArray) = BinaryXml.components(m).associate { it.name to it.enabled }

    private val adsComponents = setOf(
        "com.google.android.gms.ads.AdActivity",
        "com.google.android.gms.ads.MobileAdsInitProvider",
        "com.facebook.ads.AudienceNetworkActivity"
    )

    private val protectedComponents = setOf(
        "com.google.firebase.messaging.FirebaseMessagingService",
        "com.mixpanel.android.mpmetrics.MixpanelFCMMessagingService",
        "androidx.work.impl.background.systemjob.SystemJobService",
        "com.android.billingclient.api.ProxyBillingActivity",
        "com.google.android.gms.auth.api.signin.internal.SignInHubActivity",
        "com.facebook.CurrentAccessTokenExpirationBroadcastReceiver",
        "com.facebook.AuthenticationTokenManager\$CurrentAuthenticationTokenChangedBroadcastReceiver",
        "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery"
    )

    // --- Katalog -> Komponenten-Auswahl ---

    @Test fun gruppeWerbung_trifftGenauDieWerbeKomponenten() {
        assertEquals(
            setOf(
                "com.google.android.gms.ads.AdActivity", "com.google.android.gms.ads.MobileAdsInitProvider",
                "com.google.android.gms.ads.AdService", "com.google.android.gms.ads.NotificationHandlerActivity",
                "com.facebook.ads.AudienceNetworkActivity", "com.facebook.ads.AudienceNetworkContentProvider",
                "com.facebook.ads.internal.ipc.AdsProcessPriorityService",
                "com.facebook.ads.internal.ipc.AdsMessengerService"
            ),
            TrackerDisable.plan(setOf(TrackerGroup.ADS)).components
        )
    }

    @Test fun schutzliste_keineGruppeTrifftPushWorkManagerBezahlungLogin() {
        val all = TrackerDisable.plan(TrackerGroup.values().toSet()).components
        for (p in protectedComponents) assertTrue("$p muss geschuetzt sein", TrackerDisable.isProtected(p))
        assertTrue("Katalog darf nach der Schutzliste nichts Geschuetztes enthalten", all.none(TrackerDisable::isProtected))
        // Auch kein Katalog-Eintrag selbst (Datentransport, Facebook-Token-Empfaenger) landet im Plan
        assertFalse(all.any { it.startsWith("com.google.android.datatransport.") || it.startsWith("com.facebook.Current") })
    }

    // --- enabled=false ---

    @Test fun werbung_setztEnabledFalse_nurAnWerbeKomponenten_restUnveraendert() {
        val before = states(original)
        val patched = TrackerDisable.apply(original, setOf(TrackerGroup.ADS))
        val after = states(patched)
        assertEquals(before.keys, after.keys) // nichts geloescht
        for ((name, enabled) in after) {
            if (name in adsComponents) assertFalse("$name muss aus sein", enabled)
            else assertEquals("$name darf sich nicht aendern", before[name], enabled)
        }
        // Der echte Parser akzeptiert das Ergebnis; Reihenfolge name < enabled < exported (aapt2-Sortierung)
        val dump = BinaryXmlTestData.xmltree(patched)
        val iName = dump.indexOf("com.google.android.gms.ads.AdActivity")
        val iEnabled = dump.indexOf("enabled(0x0101000e)=false", iName)
        val iExported = dump.indexOf("exported(0x01010010)", iName)
        assertTrue("enabled=false fehlt im Dump:\n$dump", iEnabled > iName)
        assertTrue("Sortierung name<enabled<exported:\n$dump", iEnabled < iExported)
    }

    @Test fun bestehendesEnabledTrue_wirdFalse_andereAttributeBleiben() {
        val patched = TrackerDisable.apply(original, setOf(TrackerGroup.ANALYTICS))
        val s = states(patched)
        assertFalse(s.getValue("com.google.android.gms.measurement.AppMeasurementService"))
        assertFalse(s.getValue("com.google.android.gms.measurement.AppMeasurementReceiver"))
        assertFalse(s.getValue("com.google.android.gms.measurement.AppMeasurementJobService"))
        // exported/permission unveraendert: gleiche Anzahl Attribute-Zeilen im Dump
        fun count(d: String, key: String) = Regex(key).findAll(d).count()
        val d0 = BinaryXmlTestData.xmltree(original)
        val d1 = BinaryXmlTestData.xmltree(patched)
        assertEquals(count(d0, "exported\\("), count(d1, "exported\\("))
        assertEquals(count(d0, "permission\\("), count(d1, "permission\\("))
        assertTrue(d1, d1.contains("exported(0x01010010)=true")) // der exported=true-Receiver bleibt
    }

    @Test fun geschuetzteKomponenten_bleibenAnUnterAllenGruppen() {
        val patched = TrackerDisable.apply(original, TrackerGroup.values().toSet())
        val s = states(patched)
        for (p in protectedComponents) assertTrue("$p muss an bleiben", s.getValue(p))
        assertTrue(s.getValue("com.test.trk.MainActivity"))
        assertTrue(s.getValue("com.test.trk.Files"))
        assertFalse(s.getValue("com.appsflyer.SingleInstallBroadcastReceiver"))
    }

    @Test fun idempotent_zweiterLaufAendertNichts() {
        val groups = TrackerGroup.values().toSet()
        val once = TrackerDisable.apply(original, groups)
        val twice = TrackerDisable.apply(once, groups)
        assertNotEquals(original.toList(), once.toList())
        assertArrayEquals(once, twice)
    }

    @Test fun relativeUndInnereKlassennamen_werdenAufgeloest() {
        val names = setOf(
            "com.test.trk.TrackerSvc", "com.test.trk.Bare",
            "com.facebook.AuthenticationTokenManager\$CurrentAuthenticationTokenChangedBroadcastReceiver"
        )
        val s = states(BinaryXml.setComponentEnabled(original, names, false))
        for (n in names) assertFalse("$n muss aus sein", s.getValue(n))
        assertTrue(s.getValue("com.test.trk.MainActivity"))
        // wieder an: zurueck zum Original-Zustand
        assertEquals(states(original), states(BinaryXml.setComponentEnabled(BinaryXml.setComponentEnabled(original, names, false), names, true)))
    }

    @Test fun unbekannteOderLeereNamen_lassenManifestUnveraendert() {
        assertArrayEquals(original, BinaryXml.setComponentEnabled(original, emptySet(), false))
        assertArrayEquals(original, BinaryXml.setComponentEnabled(original, setOf("com.nicht.da.Foo"), false))
    }

    // --- SDK-Schalter (meta-data) ---

    @Test fun firebaseSchalter_werdenAnDerLetztenStelleGesetzt() {
        val patched = TrackerDisable.apply(original, setOf(TrackerGroup.ANALYTICS))
        // Die App hatte collection_enabled=true: unser spaeterer Eintrag muss gewinnen
        assertEquals(false, BinaryXml.applicationMetaBoolean(patched, "firebase_analytics_collection_enabled"))
        assertEquals(true, BinaryXml.applicationMetaBoolean(patched, "firebase_analytics_collection_deactivated"))
        assertEquals(false, BinaryXml.applicationMetaBoolean(patched, "firebase_crashlytics_collection_enabled"))
        val dump = BinaryXmlTestData.xmltree(patched)
        assertTrue(dump, dump.contains("firebase_crashlytics_collection_enabled"))
        assertTrue(dump, Regex("firebase_analytics_collection_deactivated\"[^\\n]*\\n[^\\n]*value\\(0x01010024\\)=true")
            .containsMatchIn(dump))
        // Registrare sind weiter vorhanden (nichts geloescht)
        assertEquals(BinaryXml.firebaseRegistrars(original), BinaryXml.firebaseRegistrars(patched))
    }

    @Test fun ohneFirebase_keineMetaSchalter() {
        val m = BinaryXmlTestData.miniManifest()
        val patched = TrackerDisable.apply(m, TrackerGroup.values().toSet())
        assertArrayEquals(m, patched)
    }

    // --- Vorher/Nachher ---

    @Test fun vorherNachher_gepatchtesManifestHatWenigerAktiveBausteine() {
        val patched = TrackerDisable.apply(original, TrackerGroup.values().toSet())
        val r = TrackerDisable.compare(original, patched, emptySet(), emptySet())
        assertTrue(r.before.size > r.after.size)
        assertEquals(setOf("Firebase Analytics", "Google Werbung (AdMob)", "Facebook Audience Network", "AppsFlyer"), r.disabled.toSet())
        assertEquals(r.before.size - r.disabled.size, r.after.size)
        assertTrue(r.text().startsWith("Bausteine vorher: ${r.before.size} — nachher: ${r.after.size} — abgeschaltet: "))
        // Crashlytics: nur Registrar im Manifest -> nicht ueber enabled abschaltbar, dafuer der SDK-Schalter
        assertTrue("Firebase Crashlytics" in r.after)
        assertTrue("firebase_crashlytics_collection_enabled" in r.metaFlags)
    }

    @Test fun ohnePatch_nichtsAbgeschaltet() {
        val r = TrackerDisable.compare(original, original, emptySet(), emptySet())
        assertEquals(r.before, r.after)
        assertTrue(r.disabled.isEmpty())
    }

    @Test fun codeBleibt_codeNurSdkWirdNichtAlsAbgeschaltetBehauptet() {
        val patched = TrackerDisable.apply(original, TrackerGroup.values().toSet())
        val r = TrackerDisable.compare(original, patched, setOf("Segment"), setOf("Segment"))
        assertTrue("Segment" in r.before && "Segment" in r.after)
        assertFalse("Segment" in r.disabled)
    }

    @Test fun beweisAusDateien_scanntManifestUndDex() {
        val patched = TrackerDisable.apply(original, TrackerGroup.values().toSet())
        val dex = "dex\n035\u0000Lcom/segment/analytics/Analytics;\u0000".toByteArray(Charsets.ISO_8859_1)
        fun apk(manifest: ByteArray): File = File.createTempFile("proof", ".apk").also { f ->
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry()
                z.putNextEntry(ZipEntry("classes.dex")); z.write(dex); z.closeEntry()
            }
        }
        val o = apk(original)
        val p = apk(patched)
        try {
            val r = TrackerDisable.proof(listOf(o), listOf(p))
            assertTrue("Segment" in r.before && "Segment" in r.after) // nur im Code, bleibt
            assertTrue(r.disabled.isNotEmpty())
            assertTrue(r.after.size < r.before.size)
        } finally {
            o.delete(); p.delete()
        }
    }
}
