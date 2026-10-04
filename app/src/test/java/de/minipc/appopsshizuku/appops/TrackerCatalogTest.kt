package de.minipc.appopsshizuku.appops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerCatalogTest {

    private fun hits(vararg classes: String): Set<String> =
        TrackerCatalog.scan(classes.toSet()).filter { it.second }.map { it.first.name }.toSet()

    @Test fun exakterName_trifft() {
        assertEquals(
            setOf("Firebase Analytics"),
            hits("com.google.android.gms.measurement.AppMeasurementReceiver")
        )
        assertEquals(setOf("Google Werbung (AdMob)"), hits("com.google.android.gms.ads.AdActivity"))
        assertEquals(setOf("Facebook Audience Network"), hits("com.facebook.ads.AudienceNetworkActivity"))
    }

    @Test fun firebaseRegistrar_trifft() {
        assertEquals(setOf("Firebase Crashlytics"), hits("com.google.firebase.crashlytics.CrashlyticsRegistrar"))
    }

    @Test fun nurPraefixOderAehnlicherName_trifftNicht() {
        assertTrue(hits("com.google.android.gms.measurement.SomethingElse").isEmpty())
        assertTrue(hits("com.google.android.gms.ads.AdActivityX").isEmpty())
        assertTrue(hits("com.google.android.gms.ads").isEmpty())
        assertTrue(hits("com.example.app.ErrorReportingService", "com.example.com.google.android.gms.ads.AdActivity").isEmpty())
    }

    @Test fun leer_keinTreffer() = assertTrue(hits().isEmpty())

    @Test fun katalog_enthaeltAlleDreiGruppen_undKeinePraefixEintraege() {
        assertEquals(TrackerGroup.values().toSet(), TrackerCatalog.sdks.map { it.group }.toSet())
        TrackerCatalog.sdks.flatMap { it.classes }.forEach {
            assertFalse(it, it.endsWith(".") || it.endsWith("*"))
            assertTrue(it, it.substringAfterLast('.').first().isUpperCase())
        }
    }

    @Test fun scan_listetJedesSdk_einmal() =
        assertEquals(TrackerCatalog.sdks.size, TrackerCatalog.scan(emptySet()).size)

    // --- Befunde vom Redmi-Test (10/2026) ---

    @Test fun appsflyer_wirdAuchMitUmgeschriebenemKlassennamenErkannt() {
        // eBay 6.271.0.3: der Manifest-Merger hat die Klasse in die App umgeschrieben.
        assertEquals(setOf("AppsFlyer"), hits("com.ebay.mobile.appsflyer.impl.AppsFlyerDeepLinkActivity"))
        // und die alten Empfaenger weiterhin
        assertEquals(setOf("AppsFlyer"), hits("com.appsflyer.SingleInstallBroadcastReceiver"))
    }

    @Test fun appsflyer_nichtBeiBeliebigemNamenMitDemWort() {
        assertTrue(hits("com.example.appsflyer.MyOwnReceiver").isEmpty())
    }

    @Test fun facebookSdk_ausDemEbayManifest() {
        assertEquals(
            setOf("Facebook SDK (App-Events)"),
            hits("com.facebook.CurrentAccessTokenExpirationBroadcastReceiver")
        )
        assertEquals(
            setOf("Facebook SDK (App-Events)"),
            hits("com.facebook.AuthenticationTokenManager\$CurrentAuthenticationTokenChangedBroadcastReceiver")
        )
    }
}
