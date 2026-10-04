package de.minipc.appopsshizuku.appops

enum class TrackerGroup { ANALYTICS, ADS, ATTRIBUTION }

/**
 * [classes] = EXAKTE Klassennamen (kein Praefix-Abgleich ueber ganze Pakete).
 * [patterns] = Ausnahmefaelle: manche SDKs deklarieren ihre Klassen mit einem RELATIVEN Namen,
 * den der Manifest-Merger in die App umschreibt (AppsFlyer: `com.<app>.appsflyer.impl.
 * AppsFlyerDeepLinkActivity`). Ein fester Klassenname kann das nicht treffen, ein eng gefasster
 * Teilstring schon.
 */
class TrackerSdk(
    val name: String,
    val group: TrackerGroup,
    val classes: Set<String>,
    val patterns: Set<String> = emptySet()
)

/**
 * Kleiner Katalog bekannter Tracking-/Werbe-Bausteine. Treffer bei exakt gleichem Klassennamen
 * einer Manifest-Komponente (Activity/Service/Receiver/Provider) oder eines Firebase-Registrars
 * (aus den Meta-Daten des ComponentDiscoveryService); zusaetzlich die [TrackerSdk.patterns].
 *
 * SDKs ohne Manifest-Komponenten (Segment, Amplitude) sind nicht erkennbar und fehlen bewusst.
 *
 * Gegengeprueft an echten Manifesten (10/2026): eBay 6.271.0.3 (Firebase Analytics, Datatransport,
 * Crashlytics, Facebook-SDK, AppsFlyer) und Kleinanzeigen (zusaetzlich Audience Network).
 * "Nicht gefunden" heisst trotzdem nicht "nicht enthalten" - es fehlen SDKs ohne Komponenten und
 * Klassen, die der Hersteller umbenennt.
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
            )
        ),
        TrackerSdk(
            "Google Datentransport (Firebase)", TrackerGroup.ANALYTICS, setOf(
                "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
                "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
                "com.google.firebase.datatransport.TransportRegistrar"
            )
        ),
        TrackerSdk(
            "Firebase Crashlytics", TrackerGroup.ANALYTICS, setOf(
                "com.google.firebase.crashlytics.CrashlyticsRegistrar",
                "com.google.firebase.crashlytics.ndk.CrashlyticsNdkRegistrar",
                "com.google.firebase.crashlytics.CrashlyticsInitProvider"
            )
        ),
        TrackerSdk(
            "Sentry", TrackerGroup.ANALYTICS, setOf(
                "io.sentry.android.core.SentryInitProvider",
                "io.sentry.android.core.SentryPerformanceProvider"
            )
        ),
        TrackerSdk(
            "Mixpanel", TrackerGroup.ANALYTICS, setOf(
                "com.mixpanel.android.mpmetrics.MixpanelFCMMessagingService",
                "com.mixpanel.android.takeoverinapp.TakeoverInAppActivity"
            )
        ),
        TrackerSdk(
            "Braze", TrackerGroup.ANALYTICS, setOf(
                "com.braze.push.BrazeFirebaseMessagingService",
                "com.braze.ui.activities.BrazeFeedActivity",
                "com.appboy.AppboyFirebaseMessagingService",
                "com.appboy.ui.AppboyFeedActivity"
            )
        ),
        TrackerSdk(
            // Am eBay-Manifest belegt: dieser Receiver kommt aus dem Facebook-SDK selbst.
            "Facebook SDK (App-Events)", TrackerGroup.ANALYTICS, setOf(
                "com.facebook.CurrentAccessTokenExpirationBroadcastReceiver",
                "com.facebook.AuthenticationTokenManager${'$'}CurrentAuthenticationTokenChangedBroadcastReceiver"
            )
        ),
        TrackerSdk(
            "Google Werbung (AdMob)", TrackerGroup.ADS, setOf(
                "com.google.android.gms.ads.AdActivity",
                "com.google.android.gms.ads.MobileAdsInitProvider",
                "com.google.android.gms.ads.AdService",
                "com.google.android.gms.ads.NotificationHandlerActivity"
            )
        ),
        TrackerSdk(
            "Facebook Audience Network", TrackerGroup.ADS, setOf(
                "com.facebook.ads.AudienceNetworkActivity",
                "com.facebook.ads.AudienceNetworkContentProvider",
                "com.facebook.ads.internal.ipc.AdsProcessPriorityService",
                "com.facebook.ads.internal.ipc.AdsMessengerService"
            )
        ),
        TrackerSdk(
            "AppsFlyer", TrackerGroup.ATTRIBUTION, setOf(
                "com.appsflyer.SingleInstallBroadcastReceiver",
                "com.appsflyer.MultipleInstallBroadcastReceiver"
            ),
            patterns = setOf("appsflyer.impl.AppsFlyerDeepLinkActivity")
        ),
        TrackerSdk(
            "Adjust", TrackerGroup.ATTRIBUTION, setOf(
                "com.adjust.sdk.AdjustReferrerReceiver",
                "com.adjust.sdk.AdjustInstallReferrerReceiver"
            )
        ),
        TrackerSdk(
            "Kochava", TrackerGroup.ATTRIBUTION, setOf(
                "com.kochava.base.InstallReferrerReceiver",
                "com.kochava.base.ReferralReceiver"
            )
        )
    )

    /** SDK -> gefunden? ([found] = Klassennamen der App). Reihenfolge wie im Katalog. */
    fun scan(found: Set<String>): List<Pair<TrackerSdk, Boolean>> = sdks.map { sdk ->
        val exact = sdk.classes.any { it in found }
        val pattern = sdk.patterns.any { p -> found.any { it.contains(p) } }
        sdk to (exact || pattern)
    }
}
