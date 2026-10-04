package de.minipc.appopsshizuku.appops

enum class TrackerGroup { ANALYTICS, ADS, ATTRIBUTION }

/**
 * [classes] = EXAKTE Klassennamen (kein Praefix-Abgleich ueber ganze Pakete).
 * [patterns] = Ausnahmefaelle: manche SDKs deklarieren ihre Klassen mit einem RELATIVEN Namen,
 * den der Manifest-Merger in die App umschreibt (AppsFlyer: `com.<app>.appsflyer.impl.
 * AppsFlyerDeepLinkActivity`). Ein fester Klassenname kann das nicht treffen, ein eng gefasster
 * Teilstring schon.
 * [markers] = Klassenpfade mit Slashes, wie sie im Dex stehen, fuer den Code-Scan
 * ([TrackerCodeScan]); ein Treffer genuegt.
 */
class TrackerSdk(
    val name: String,
    val group: TrackerGroup,
    val classes: Set<String>,
    val patterns: Set<String> = emptySet(),
    val markers: List<String> = emptyList()
)

/** Eingabe des Code-Scans: [id] = Anzeige-Name des SDK ([TrackerSdk.name]). */
class TrackerProbe(val id: String, val markers: List<String>)

/**
 * Kleiner Katalog bekannter Tracking-/Werbe-Bausteine. Zwei Nachweise:
 *  1. Manifest: exakt gleicher Klassenname einer Komponente (Activity/Service/Receiver/Provider)
 *     oder eines Firebase-Registrars (aus den Meta-Daten des ComponentDiscoveryService);
 *     zusaetzlich die [TrackerSdk.patterns].
 *  2. Code: Marker-Strings in den classes*.dex der APK ([TrackerSdk.markers]). Damit werden auch
 *     SDKs ohne Manifest-Komponenten sichtbar (Segment, Amplitude, Facebook App Events, altes
 *     Google Analytics) - diese haben deshalb eine leere [TrackerSdk.classes]-Menge.
 *
 * Marker-Grenze: ein Marker ohne `/` am Ende trifft nur an einer Namensgrenze (`com/segment/analytics`
 * trifft `Lcom/segment/analytics/Analytics;`, nicht `com/segment/analyticsx`); ein Marker mit
 * `/` am Ende (`com/facebook/ads/`) ist dadurch schon begrenzt. Siehe [TrackerCodeScan].
 *
 * Manifest gegengeprueft an echten Manifesten (10/2026): eBay 6.271.0.3 (Firebase Analytics,
 * Datatransport, Crashlytics, Facebook-SDK, AppsFlyer) und Kleinanzeigen (zusaetzlich Audience
 * Network). Code-Marker gegengeprueft an eBay 6.271.0.3: `com/facebook/appevents` 38 Treffer,
 * `com/appsflyer/AppsFlyerLib` 2; Segment/Amplitude 0 (App hat sie nicht).
 * "Nicht gefunden" heisst trotzdem nicht "nicht enthalten" - verschleierte Apps koennen
 * Klassen und Pakete umbenennen.
 */
object TrackerCatalog {

    val sdks: List<TrackerSdk> = listOf(
        TrackerSdk(
            "Firebase Analytics", TrackerGroup.ANALYTICS, setOf(
                "com.google.android.gms.measurement.AppMeasurementReceiver",
                "com.google.android.gms.measurement.AppMeasurementService",
                "com.google.android.gms.measurement.AppMeasurementJobService",
                "com.google.android.gms.measurement.AppMeasurementContentProvider",
                "com.google.firebase.analytics.connector.internal.AnalyticsConnectorRegistrar"
            ),
            markers = listOf("com/google/firebase/analytics")
        ),
        TrackerSdk(
            "Google Datentransport (Firebase)", TrackerGroup.ANALYTICS, setOf(
                "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
                "com.google.firebase.datatransport.TransportRegistrar"
            ),
            markers = listOf("com/google/android/datatransport")
        ),
        TrackerSdk(
            "Firebase Crashlytics", TrackerGroup.ANALYTICS, setOf(
                "com.google.firebase.crashlytics.CrashlyticsRegistrar",
                "com.google.firebase.crashlytics.ndk.CrashlyticsNdkRegistrar",
                "com.google.firebase.crashlytics.CrashlyticsInitProvider"
            ),
            markers = listOf("com/google/firebase/crashlytics")
        ),
        TrackerSdk(
            "Sentry", TrackerGroup.ANALYTICS, setOf(
                "io.sentry.android.core.SentryInitProvider",
                "io.sentry.android.core.SentryPerformanceProvider"
            ),
            markers = listOf("io/sentry/android/core")
        ),
        TrackerSdk(
            "Mixpanel", TrackerGroup.ANALYTICS, setOf(
                "com.mixpanel.android.mpmetrics.MixpanelFCMMessagingService",
                "com.mixpanel.android.takeoverinapp.TakeoverInAppActivity"
            ),
            markers = listOf("com/mixpanel/android")
        ),
        TrackerSdk(
            "Braze", TrackerGroup.ANALYTICS, setOf(
                "com.braze.push.BrazeFirebaseMessagingService",
                "com.braze.ui.activities.BrazeFeedActivity",
                "com.appboy.AppboyFirebaseMessagingService",
                "com.appboy.ui.AppboyFeedActivity"
            ),
            markers = listOf("com/braze/")
        ),
        TrackerSdk(
            // Am eBay-Manifest belegt: dieser Receiver kommt aus dem Facebook-SDK selbst.
            "Facebook SDK (App-Events)", TrackerGroup.ANALYTICS, setOf(
                "com.facebook.CurrentAccessTokenExpirationBroadcastReceiver",
                "com.facebook.AuthenticationTokenManager${'$'}CurrentAuthenticationTokenChangedBroadcastReceiver"
            ),
            markers = listOf("com/facebook/appevents")
        ),
        // Ohne Manifest-Komponenten: nur ueber den Code-Scan erkennbar.
        TrackerSdk(
            "Segment", TrackerGroup.ANALYTICS, emptySet(),
            markers = listOf("com/segment/analytics")
        ),
        TrackerSdk(
            "Amplitude", TrackerGroup.ANALYTICS, emptySet(),
            markers = listOf("com/amplitude/api")
        ),
        TrackerSdk(
            "Google Analytics (alt)", TrackerGroup.ANALYTICS, emptySet(),
            markers = listOf("com/google/android/gms/analytics")
        ),
        TrackerSdk(
            "Google Werbung (AdMob)", TrackerGroup.ADS, setOf(
                "com.google.android.gms.ads.AdActivity",
                "com.google.android.gms.ads.MobileAdsInitProvider",
                "com.google.android.gms.ads.AdService",
                "com.google.android.gms.ads.NotificationHandlerActivity"
            ),
            // NICHT auf "com/google/android/gms/ads" verkuerzen! Das trifft auch
            // com/google/android/gms/ads/identifier/AdvertisingIdClient - die Werbe-ID-Bibliothek
            // steckt in sehr vielen Apps, die gar keine Werbung zeigen (am Geraet an eBay belegt:
            // MobileAds/AdView/AdRequest = 0 Treffer, AdvertisingIdClient = 3). Deshalb hier die
            // konkreten Klassen; die Werbe-ID-Bibliothek hat einen eigenen Eintrag.
            markers = listOf(
                "com/google/android/gms/ads/MobileAds",
                "com/google/android/gms/ads/AdView",
                "com/google/android/gms/ads/AdRequest"
            )
        ),
        TrackerSdk(
            // Nur die Werbe-ID (play-services-ads-identifier) - keine Werbung, aber ein
            // Kennzeichen, das die App ausliest. Bewusst getrennt vom AdMob-Eintrag.
            "Google Werbe-ID (AdvertisingIdClient)", TrackerGroup.ADS, emptySet(),
            markers = listOf("com/google/android/gms/ads/identifier/AdvertisingIdClient")
        ),
        TrackerSdk(
            "Facebook Audience Network", TrackerGroup.ADS, setOf(
                "com.facebook.ads.AudienceNetworkActivity",
                "com.facebook.ads.AudienceNetworkContentProvider",
                "com.facebook.ads.internal.ipc.AdsProcessPriorityService",
                "com.facebook.ads.internal.ipc.AdsMessengerService"
            ),
            markers = listOf("com/facebook/ads/")
        ),
        TrackerSdk(
            "AppsFlyer", TrackerGroup.ATTRIBUTION, setOf(
                "com.appsflyer.SingleInstallBroadcastReceiver",
                "com.appsflyer.MultipleInstallBroadcastReceiver"
            ),
            patterns = setOf("appsflyer.impl.AppsFlyerDeepLinkActivity"),
            markers = listOf("com/appsflyer/AppsFlyerLib")
        ),
        TrackerSdk(
            "Adjust", TrackerGroup.ATTRIBUTION, setOf(
                "com.adjust.sdk.AdjustReferrerReceiver",
                "com.adjust.sdk.AdjustInstallReferrerReceiver"
            ),
            markers = listOf("com/adjust/sdk")
        ),
        TrackerSdk(
            "Kochava", TrackerGroup.ATTRIBUTION, setOf(
                "com.kochava.base.InstallReferrerReceiver",
                "com.kochava.base.ReferralReceiver"
            ),
            markers = listOf("com/kochava/base")
        )
    )

    /** Marker fuer den Code-Scan, eine Sonde je SDK. */
    val probes: List<TrackerProbe> = sdks.map { TrackerProbe(it.name, it.markers) }

    /** SDK -> gefunden? ([found] = Klassennamen der App). Reihenfolge wie im Katalog. */
    fun scan(found: Set<String>): List<Pair<TrackerSdk, Boolean>> = sdks.map { sdk ->
        val exact = sdk.classes.any { it in found }
        val pattern = sdk.patterns.any { p -> found.any { it.contains(p) } }
        sdk to (exact || pattern)
    }
}
