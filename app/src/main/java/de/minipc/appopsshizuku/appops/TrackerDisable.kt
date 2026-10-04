package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.stealth.ApkModifier
import de.minipc.appopsshizuku.stealth.BinaryXml
import java.io.File

/**
 * Tracker beim Patchen abschalten (Beta). Zwei Stufen, beide ohne Code-Eingriff:
 *  1. Komponenten der gewaehlten [TrackerGroup]s bekommen `android:enabled="false"` (nichts wird
 *     geloescht, Klassen und Manifest-Namen bleiben vorhanden).
 *  2. Von Google dokumentierte Meta-Data-Schalter fuer Firebase Analytics / Crashlytics.
 * Code-Ebene (Methoden leeren) gibt es bewusst nicht: ungetestete Smali-Eingriffe sind nicht erlaubt.
 * Folge: SDKs ohne Manifest-Komponenten (Segment, Amplitude, Werbe-ID, ...) bleiben unveraendert.
 */
object TrackerDisable {

    /**
     * NIE abschalten, auch wenn der Katalog die Klasse kennt: Push, WorkManager, Bezahlung,
     * Login, plus Datentransport (gemeinsame Infrastruktur von FCM/Crashlytics; Abschalten
     * ungeprueft, ein nicht planbarer JobScheduler-Dienst kann den Aufrufer zum Absturz bringen).
     */
    private val PROTECTED_PREFIXES = listOf(
        "com.google.firebase.messaging.", "com.google.firebase.iid.", // Push
        "androidx.work.", // WorkManager
        "com.android.billingclient.", "com.android.vending.billing.", // Bezahlung
        "com.google.android.gms.auth.", "com.google.android.gms.common.api.GoogleApiActivity", // Google-Login
        "com.facebook.login.", "com.facebook.CurrentAccessTokenExpirationBroadcastReceiver",
        "com.facebook.AuthenticationTokenManager", // Facebook-Login (Token-Empfaenger)
        "com.google.android.datatransport.", "com.google.firebase.datatransport." // Datentransport
    )

    /** Push-Dienste von Drittanbietern (Braze, Mixpanel, ...): `...MessagingService` / `...FCMMessagingService`. */
    fun isProtected(className: String): Boolean =
        PROTECTED_PREFIXES.any { className.startsWith(it) } ||
            className.substringAfterLast('.').contains("MessagingService")

    /** [sdkName] = Katalogeintrag, dessen Erkennung den Schalter erst sinnvoll macht. */
    class MetaFlag(val key: String, val value: Boolean, val sdkName: String)

    class Plan(val components: Set<String>, val metaFlags: List<MetaFlag>)

    private val ANALYTICS_FLAGS = listOf(
        MetaFlag("firebase_analytics_collection_enabled", false, "Firebase Analytics"),
        MetaFlag("firebase_analytics_collection_deactivated", true, "Firebase Analytics"),
        MetaFlag("firebase_crashlytics_collection_enabled", false, "Firebase Crashlytics")
    )

    /** Ziele der gewaehlten Gruppen: exakte Katalog-Klassen ohne die Schutzliste. */
    fun targets(sdk: TrackerSdk): Set<String> = sdk.classes.filterNot(::isProtected).toSet()

    fun plan(groups: Set<TrackerGroup>): Plan = Plan(
        components = TrackerCatalog.sdks.filter { it.group in groups }.flatMapTo(LinkedHashSet()) { targets(it) },
        metaFlags = if (TrackerGroup.ANALYTICS in groups) ANALYTICS_FLAGS else emptyList()
    )

    /** Katalog-Treffer im Manifest (Komponenten + Firebase-Registrare), Namen der SDKs. */
    fun detectInManifest(manifest: ByteArray): Set<String> {
        val names = BinaryXml.components(manifest).mapTo(HashSet()) { it.name } + BinaryXml.firebaseRegistrars(manifest)
        return TrackerCatalog.scan(names).filter { it.second }.mapTo(LinkedHashSet()) { it.first.name }
    }

    /** Wendet [plan] an. Gibt [manifest] selbst zurueck, wenn nichts zu tun war. */
    fun apply(manifest: ByteArray, groups: Set<TrackerGroup>): ByteArray {
        val plan = plan(groups)
        var m = BinaryXml.setComponentEnabled(manifest, plan.components, false)
        val present = detectInManifest(manifest)
        for (f in plan.metaFlags) if (f.sdkName in present) m = BinaryXml.setApplicationMetaBoolean(m, f.key, f.value)
        return m
    }

    /**
     * Vorher/Nachher ueber den eigenen Katalog. "Vorher" = Original (Manifest + Code), "nachher" =
     * gepatchte APK (Manifest + Code). Ein Baustein gilt als abgeschaltet, wenn er im Manifest
     * abschaltbare Komponenten hat und ALLE davon `enabled=false` sind. Der Code bleibt in der APK
     * (Absicht), deshalb zaehlt der Code-Scan nur fuer "ist der Baustein da", nicht fuer "aktiv".
     */
    class Report(
        val before: List<String>,
        val after: List<String>,
        val disabled: List<String>,
        val metaFlags: List<String>
    ) {
        fun text(): String = buildString {
            append("Bausteine vorher: ${before.size} — nachher: ${after.size} — abgeschaltet: ")
            append(if (disabled.isEmpty()) "keine" else disabled.joinToString(", "))
            if (metaFlags.isNotEmpty()) append("\nSDK-Schalter gesetzt: ").append(metaFlags.joinToString(", "))
            if (after.isNotEmpty()) append("\nWeiterhin aktiv: ").append(after.joinToString(", "))
            append("\nDer Code der Bausteine bleibt in der App (bewusst nicht entfernt).")
        }
    }

    /** Datei-Variante von [compare]: je die erste APK = base. Liest nur, schreibt nichts. */
    fun proof(originals: List<File>, patched: List<File>): Report = compare(
        ApkModifier.extractManifest(originals.first()), ApkModifier.extractManifest(patched.first()),
        TrackerCodeScan.scan(originals, TrackerCatalog.probes), TrackerCodeScan.scan(patched, TrackerCatalog.probes)
    )

    fun compare(
        originalManifest: ByteArray, patchedManifest: ByteArray,
        originalCode: Set<String>, patchedCode: Set<String>
    ): Report {
        val before = detectInManifest(originalManifest) + originalCode
        val patchedFound = detectInManifest(patchedManifest) + patchedCode
        val comps = BinaryXml.components(patchedManifest).associate { it.name to it.enabled }
        val disabled = TrackerCatalog.sdks.filter { sdk ->
            val t = targets(sdk).filter { it in comps }
            sdk.name in before && sdk.name in patchedFound && t.isNotEmpty() && t.none { comps.getValue(it) }
        }.map { it.name }
        val names = TrackerCatalog.sdks.map { it.name }
        val keys = ANALYTICS_FLAGS.map { it.key }.distinct().filter {
            BinaryXml.applicationMetaBoolean(patchedManifest, it) == ANALYTICS_FLAGS.first { f -> f.key == it }.value
        }
        return Report(
            before = names.filter { it in before },
            after = names.filter { it in patchedFound && it !in disabled },
            disabled = disabled,
            metaFlags = keys
        )
    }
}
