package de.minipc.appopsshizuku.appops

import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import de.minipc.appopsshizuku.whitelistContains
import org.json.JSONObject

/**
 * Feature 3 "Lifecycle-Profil": ein Griff statt vier Schalter.
 *
 * Anwenden (feste Reihenfolge, siehe [applyPlan]):
 *  1. Akku-Ausnahme      `cmd deviceidle whitelist +<pkg>`
 *  2. Standby-Stufe      `am set-standby-bucket <pkg> <stufe>`
 *  3. RUN_IN_BACKGROUND  `cmd appops set <pkg> RUN_IN_BACKGROUND allow`
 *  4. RUN_ANY_IN_BACKGROUND
 *  5. optional           `cmd package compile -m speed -f <pkg>`
 *
 * Am Geraet gemessene Falle: hat die App eine Akku-Ausnahme, haelt Android die
 * Standby-Stufe auf 5 (EXEMPTED). `am set-standby-bucket` laeuft dann fehlerfrei
 * durch, ist aber wirkungslos. Deshalb: Ausnahme ZUERST, Stufe DANACH und die
 * Stufe danach NEU LESEN. Stufe 5 ist per Shell nie setzbar ("Unknown bucket:
 * exempted") und wird nie als Erfolg gemeldet.
 *
 * Reine JVM-Logik ueber [ShellBackend] - unit-testbar ohne Geraet. Keine Log-Aufrufe
 * hier (android.util.Log ist in JVM-Tests nicht verfuegbar); das Loggen uebernimmt
 * der Aufrufer mit dem Praefix "AppOps.Lifecycle".
 */

/** Die einzelnen Schritte eines Lifecycle-Profils. */
enum class LifecycleStep(val label: String) {
    BATTERY_EXEMPT("Akku-Ausnahme"),
    STANDBY_BUCKET("Standby-Stufe"),
    RUN_IN_BACKGROUND("RUN_IN_BACKGROUND"),
    RUN_ANY_IN_BACKGROUND("RUN_ANY_IN_BACKGROUND"),
    COMPILE("App optimieren")
}

/** Wunsch-Profil. RUN_IN_BACKGROUND/RUN_ANY_IN_BACKGROUND werden immer auf "allow" gesetzt. */
data class LifecycleProfile(
    val standbyBucket: Int = 10,
    val batteryExempt: Boolean = true,
    val compile: Boolean = false
)

/**
 * Ist-Zustand vor dem Anwenden (fuer "Profil zuruecknehmen").
 * null = war nicht lesbar -> wird bei der Ruecknahme NICHT angefasst.
 */
data class LifecycleSnapshot(
    val batteryExempt: Boolean? = null,
    val standbyBucket: Int? = null,
    val runInBackground: String? = null,
    val runAnyInBackground: String? = null
)

/** Ergebnis eines einzelnen Schritts - nie als Sammel-"Erfolg" zusammengefasst. */
data class StepOutcome(
    val step: LifecycleStep,
    val status: Status,
    /** Wunschwert (lesbar), z. B. "an", "10 (Aktiv)", "allow". */
    val wanted: String,
    /** Frisch vom Geraet gelesener Istwert; null = nicht lesbar. */
    val actual: String?,
    /** Rohmeldung des Geraets (gekuerzt, Whitespace zusammengefasst). */
    val raw: String = "",
    /** Klartext-Grund bei Status != OK. */
    val reason: String? = null
) {
    enum class Status { OK, FAILED, NOT_SETTABLE, SKIPPED }

    val ok: Boolean get() = status == Status.OK

    /** Eine Zeile fuer die Oberflaeche / das Log. */
    fun describe(): String = buildString {
        append(
            when (status) {
                Status.OK -> "✓ "
                Status.SKIPPED -> "– "
                else -> "✗ "
            }
        )
        append(step.label).append(": ")
        when (status) {
            Status.OK -> append(actual ?: wanted)
            Status.SKIPPED -> append(reason ?: "übersprungen")
            else -> {
                append("Wunsch ").append(wanted).append(", Gerät ").append(actual ?: "unbekannt")
                reason?.let { append(" – ").append(it) }
            }
        }
        if (status != Status.OK && status != Status.SKIPPED && raw.isNotBlank()) {
            append(" [Gerät meldet: ").append(raw.take(160)).append("]")
        }
    }
}

/** Ergebnis von Anwenden/Zuruecknehmen: der vorher gelesene Zustand + ein Eintrag je Schritt. */
data class LifecycleResult(
    val before: LifecycleSnapshot?,
    val outcomes: List<StepOutcome>,
    /** Gesetzt, wenn gar nichts ausgefuehrt wurde (eigene App, System-App, kein Shizuku). */
    val refused: String? = null
) {
    val failures: List<StepOutcome>
        get() = outcomes.filter {
            it.status == StepOutcome.Status.FAILED || it.status == StepOutcome.Status.NOT_SETTABLE
        }
}

class LifecycleManager(
    private val backend: ShellBackend,
    private val ownPackage: String
) {

    private val appOps = AppOpsController(backend)

    /** Ist-Zustand der vier ruecknehmbaren Werte frisch vom Geraet lesen. */
    fun snapshot(packageName: String): LifecycleSnapshot = LifecycleSnapshot(
        batteryExempt = readBatteryExempt(packageName),
        standbyBucket = readStandbyBucket(packageName),
        runInBackground = appOps.getMode(packageName, RUN_IN_BACKGROUND_OP),
        runAnyInBackground = appOps.getMode(packageName, RUN_ANY_IN_BACKGROUND_OP)
    )

    /**
     * Profil anwenden. Liest vorher den Ist-Zustand ([LifecycleResult.before]) und nach
     * JEDEM Schritt den Wert frisch vom Geraet.
     */
    fun apply(packageName: String, profile: LifecycleProfile, isSystemApp: Boolean): LifecycleResult {
        lifecycleRefusal(packageName, ownPackage, isSystemApp, backend.hasPermission())
            ?.let { return LifecycleResult(null, emptyList(), it) }

        val before = snapshot(packageName)
        val outcomes = applyPlan(profile).map { step ->
            when (step) {
                LifecycleStep.BATTERY_EXEMPT -> setBatteryExempt(packageName, profile.batteryExempt)
                LifecycleStep.STANDBY_BUCKET ->
                    setStandbyBucket(packageName, profile.standbyBucket, before.standbyBucket, restoring = false)
                LifecycleStep.RUN_IN_BACKGROUND ->
                    setOpMode(packageName, RUN_IN_BACKGROUND_OP, step, "allow", before.runInBackground)
                LifecycleStep.RUN_ANY_IN_BACKGROUND ->
                    setOpMode(packageName, RUN_ANY_IN_BACKGROUND_OP, step, "allow", before.runAnyInBackground)
                LifecycleStep.COMPILE -> compile(packageName)
            }
        }
        return LifecycleResult(before, outcomes)
    }

    /** Gemerkten Zustand wiederherstellen - Reihenfolge siehe [revertPlan]. */
    fun revert(packageName: String, before: LifecycleSnapshot, isSystemApp: Boolean): LifecycleResult {
        lifecycleRefusal(packageName, ownPackage, isSystemApp, backend.hasPermission())
            ?.let { return LifecycleResult(before, emptyList(), it) }

        val outcomes = revertPlan().map { step ->
            when (step) {
                LifecycleStep.RUN_ANY_IN_BACKGROUND ->
                    before.runAnyInBackground?.let {
                        setOpMode(packageName, RUN_ANY_IN_BACKGROUND_OP, step, it, null)
                    } ?: skippedUnknown(step)
                LifecycleStep.RUN_IN_BACKGROUND ->
                    before.runInBackground?.let {
                        setOpMode(packageName, RUN_IN_BACKGROUND_OP, step, it, null)
                    } ?: skippedUnknown(step)
                LifecycleStep.BATTERY_EXEMPT ->
                    before.batteryExempt?.let { setBatteryExempt(packageName, it) } ?: skippedUnknown(step)
                LifecycleStep.STANDBY_BUCKET ->
                    before.standbyBucket?.let {
                        setStandbyBucket(packageName, it, null, restoring = true)
                    } ?: skippedUnknown(step)
                LifecycleStep.COMPILE -> StepOutcome(
                    step, StepOutcome.Status.SKIPPED, "-", null,
                    reason = "Kompilierung wird nicht zurückgenommen (harmlos, nur Startzeit)"
                )
            }
        }
        return LifecycleResult(before, outcomes)
    }

    // ------------------------------------------------------------------ Schritte

    private fun setBatteryExempt(packageName: String, want: Boolean): StepOutcome {
        val res = backend.exec("cmd", "deviceidle", "whitelist", (if (want) "+" else "-") + packageName)
        val dump = backend.exec("dumpsys", "deviceidle", "whitelist")
        val now = if (dump.exitCode == 0) whitelistContains(dump.stdout, packageName) else null
        val ok = now == want
        return StepOutcome(
            LifecycleStep.BATTERY_EXEMPT,
            if (ok) StepOutcome.Status.OK else StepOutcome.Status.FAILED,
            onOff(want), now?.let(::onOff), rawOf(res),
            if (ok) null else "Gerät hat die Änderung nicht übernommen"
        )
    }

    private fun setStandbyBucket(
        packageName: String,
        want: Int,
        before: Int?,
        restoring: Boolean
    ): StepOutcome {
        val step = LifecycleStep.STANDBY_BUCKET
        val name = standbyName(want)
        if (name == null) {
            // Nicht per Shell setzbar (5 = EXEMPTED oder unbekannt): KEIN Befehl.
            // Bei der Ruecknahme kann der alte Wert 5 trotzdem wieder stehen, weil die
            // davor wiederhergestellte Akku-Ausnahme ihn erzwingt -> nur dann OK.
            val now = readStandbyBucket(packageName)
            val ok = restoring && now == want
            return StepOutcome(
                step,
                if (ok) StepOutcome.Status.OK else StepOutcome.Status.NOT_SETTABLE,
                bucketText(want), now?.let(::bucketText),
                reason = if (ok) null else bucketNotSettableReason(want)
            )
        }
        val setRes = backend.exec("am", "set-standby-bucket", packageName, name)
        // Ehrlichkeit: NACH dem Setzen (und nach der Akku-Ausnahme) frisch lesen.
        val getRes = backend.exec("cmd", "activity", "get-standby-bucket", packageName)
        val now = standbyBucketOf(getRes.stdout)
        if (now == want) {
            return StepOutcome(step, StepOutcome.Status.OK, bucketText(want), bucketText(now))
        }
        val exempt = readBatteryExempt(packageName)
        return StepOutcome(
            step, StepOutcome.Status.FAILED, bucketText(want), now?.let(::bucketText),
            "set: ${rawOf(setRes)} | ist: ${rawOf(getRes)}",
            bucketFailureReason(before, want, now, exempt)
        )
    }

    private fun setOpMode(
        packageName: String,
        op: AppOp,
        step: LifecycleStep,
        want: String,
        before: String?
    ): StepOutcome {
        val res = appOps.setMode(packageName, op, want)
        val now = appOps.getMode(packageName, op)
        val ok = now == want
        return StepOutcome(
            step,
            if (ok) StepOutcome.Status.OK else StepOutcome.Status.FAILED,
            want, now, rawOf(res),
            when {
                ok -> null
                deviceKeptOldValue(before, want, now) -> "Gerät behielt den alten Wert"
                else -> "Gerät hat den Wert nicht übernommen"
            }
        )
    }

    private fun compile(packageName: String): StepOutcome {
        val res = backend.execTimeout(180, "cmd", "package", "compile", "-m", "speed", "-f", packageName)
        val ok = compileSucceeded(res)
        return StepOutcome(
            LifecycleStep.COMPILE,
            if (ok) StepOutcome.Status.OK else StepOutcome.Status.FAILED,
            "speed", if (ok) "speed" else null, rawOf(res),
            if (ok) null else "Gerät hat den Befehl abgelehnt"
        )
    }

    private fun skippedUnknown(step: LifecycleStep) = StepOutcome(
        step, StepOutcome.Status.SKIPPED, "-", null,
        reason = "Wert war vorher nicht lesbar – nicht angefasst"
    )

    // ------------------------------------------------------------------ Lesen

    private fun readStandbyBucket(packageName: String): Int? =
        standbyBucketOf(backend.exec("cmd", "activity", "get-standby-bucket", packageName).stdout)

    private fun readBatteryExempt(packageName: String): Boolean? {
        val res = backend.exec("dumpsys", "deviceidle", "whitelist")
        return if (res.exitCode == 0) whitelistContains(res.stdout, packageName) else null
    }

    companion object {
        val RUN_IN_BACKGROUND_OP = AppOp("RUN_IN_BACKGROUND", "Hintergrund-Ausfuehrung", null, "Hintergrund")
        val RUN_ANY_IN_BACKGROUND_OP =
            AppOp("RUN_ANY_IN_BACKGROUND", "Hintergrund-Ausfuehrung (beliebig)", null, "Hintergrund")
    }
}

// ---------------------------------------------------------------- Reine Helfer (unit-testbar)

/**
 * Reihenfolge beim Anwenden. Akku-Ausnahme MUSS vor der Standby-Stufe kommen
 * (sonst setzt die Ausnahme die Stufe hinterher auf 5 zurueck).
 */
fun applyPlan(profile: LifecycleProfile): List<LifecycleStep> = buildList {
    add(LifecycleStep.BATTERY_EXEMPT)
    add(LifecycleStep.STANDBY_BUCKET)
    add(LifecycleStep.RUN_IN_BACKGROUND)
    add(LifecycleStep.RUN_ANY_IN_BACKGROUND)
    if (profile.compile) add(LifecycleStep.COMPILE)
}

/**
 * Reihenfolge bei der Ruecknahme: umgekehrt zum Anwenden, mit einer Ausnahme -
 * die Akku-Ausnahme wird VOR der Standby-Stufe zurueckgesetzt. Nur so kann die
 * alte Stufe wieder greifen (solange die Ausnahme besteht, haelt Android Stufe 5).
 * Die Kompilierung wird nicht zurueckgenommen (nur als Hinweis gelistet).
 */
fun revertPlan(): List<LifecycleStep> = listOf(
    LifecycleStep.RUN_ANY_IN_BACKGROUND,
    LifecycleStep.RUN_IN_BACKGROUND,
    LifecycleStep.BATTERY_EXEMPT,
    LifecycleStep.STANDBY_BUCKET,
    LifecycleStep.COMPILE
)

/** Grund, warum auf einer App NICHTS ausgefuehrt wird; null = erlaubt. */
fun lifecycleRefusal(
    packageName: String,
    ownPackage: String,
    isSystemApp: Boolean,
    hasShizuku: Boolean
): String? = when {
    packageName == ownPackage -> "Nicht auf die eigene App anwendbar"
    isSystemApp -> "Nicht auf System-Apps anwendbar"
    !hasShizuku -> "Nicht möglich: Shizuku ist nicht verbunden oder nicht erlaubt"
    else -> null
}

/** Warum eine Stufe nicht per Shell setzbar ist; null = setzbar. */
fun bucketNotSettableReason(bucket: Int): String? = when {
    standbyName(bucket) != null -> null
    bucket == 5 -> "Stufe 5 (vom System ausgenommen) ist per Shell nicht setzbar (\"Unknown bucket: exempted\")"
    else -> "Unbekannte Standby-Stufe $bucket"
}

/** Deutscher Name einer Standby-Stufe (Nummer wird immer mitgezeigt, nichts geraten). */
fun bucketText(bucket: Int): String = when (bucket) {
    5 -> "5 (vom System ausgenommen)"
    10 -> "10 (Aktiv)"
    20 -> "20 (Regelmäßig)"
    30 -> "30 (Ab und zu)"
    40 -> "40 (Selten)"
    45 -> "45 (Eingeschränkt)"
    50 -> "50 (Nie)"
    else -> "Stufe $bucket"
}

/** Klartext, warum die Standby-Stufe nach dem Setzen nicht dem Wunsch entspricht. */
fun bucketFailureReason(before: Int?, want: Int, now: Int?, batteryExempt: Boolean?): String = when {
    now == null -> "Stufe danach nicht lesbar"
    now == 5 && batteryExempt == true ->
        "Die App hat eine Akku-Ausnahme – Android hält sie auf Stufe 5 (ausgenommen), " +
            "der Befehl lief durch, war aber wirkungslos"
    deviceKeptOldValue(before, want, now) -> "Gerät behielt den alten Wert"
    else -> "Gerät hat einen anderen Wert gesetzt"
}

/** "Geraet behielt den alten Wert": Istwert != Wunsch und == Wert vor dem Befehl. */
fun <T> deviceKeptOldValue(before: T?, wanted: T, after: T?): Boolean =
    before != null && after != wanted && after == before

/** Erfolgsheuristik fuer `cmd package compile` (wie im Reiter "Verhalten"). */
fun compileSucceeded(res: ShellResult): Boolean {
    val out = (res.stdout + " " + res.stderr).trim()
    return res.exitCode == 0 &&
        (out.contains("Success", ignoreCase = true) || out.contains("Nothing to do", ignoreCase = true))
}

/**
 * Beim erneuten Anwenden darf der gemerkte Ursprungszustand NICHT durch den
 * (schon veraenderten) aktuellen Zustand ersetzt werden - sonst stellt
 * "Zuruecknehmen" nur das Profil wieder her.
 */
fun snapshotToKeep(stored: LifecycleSnapshot?, fresh: LifecycleSnapshot?): LifecycleSnapshot? =
    stored ?: fresh

/** Gemerkten Zustand erst verwerfen, wenn die Ruecknahme ohne Fehler durchlief. */
fun shouldClearSnapshot(result: LifecycleResult): Boolean =
    result.refused == null && result.failures.isEmpty()

/** Gemerkten Zustand lesbar machen; nicht lesbare Werte heissen "unbekannt". */
fun describeSnapshot(s: LifecycleSnapshot): String = listOf(
    "Akku-Ausnahme " + (s.batteryExempt?.let(::onOff) ?: "unbekannt"),
    "Stufe " + (s.standbyBucket?.let(::bucketText) ?: "unbekannt"),
    "RUN_IN_BACKGROUND " + (s.runInBackground ?: "unbekannt"),
    "RUN_ANY_IN_BACKGROUND " + (s.runAnyInBackground ?: "unbekannt")
).joinToString(", ")

internal fun onOff(b: Boolean): String = if (b) "an" else "aus"

internal fun rawOf(res: ShellResult): String =
    (res.stdout.trim() + " " + res.stderr.trim()).replace(Regex("\\s+"), " ").trim()

// ---------------------------------------------------------------- JSON (Format v2, Schluessel "lifecycle")

/**
 * Pro App gespeicherte Lifecycle-Einstellungen. Alle Felder nullable:
 * null = "fehlt in der Datei" -> beim Import wird der vorhandene Wert NICHT angefasst.
 */
data class LifecycleEntry(
    val standbyBucket: Int? = null,
    val batteryExempt: Boolean? = null,
    val compile: Boolean? = null,
    /** Ist-Zustand vor dem ersten Anwenden (fuer "Zuruecknehmen"). */
    val before: LifecycleSnapshot? = null,
    val screenOffExit: Boolean? = null,
    val screenOffEffect: ScreenOffEffect? = null,
    val releaseOnScreenOn: Boolean? = null,
    /** "Nur bei Bedarf": App bleibt gesperrt und laeuft nur nach Start ueber unsere Verknuepfung. */
    val onDemand: Boolean? = null,
    val onDemandLock: OnDemandLock? = null,
    /** Frist nach dem Verlassen bis zum Sperren; 0 = nur beim Bildschirm-Aus sperren. */
    val onDemandGraceSeconds: Int? = null
) {
    fun profile(): LifecycleProfile {
        val d = LifecycleProfile()
        return LifecycleProfile(
            standbyBucket = standbyBucket ?: d.standbyBucket,
            batteryExempt = batteryExempt ?: d.batteryExempt,
            compile = compile ?: d.compile
        )
    }

    /** Feldweise zusammenfuehren: Werte aus [other] gewinnen, fehlende (null) lassen den alten stehen. */
    fun mergedWith(other: LifecycleEntry): LifecycleEntry = LifecycleEntry(
        standbyBucket = other.standbyBucket ?: standbyBucket,
        batteryExempt = other.batteryExempt ?: batteryExempt,
        compile = other.compile ?: compile,
        before = other.before ?: before,
        screenOffExit = other.screenOffExit ?: screenOffExit,
        screenOffEffect = other.screenOffEffect ?: screenOffEffect,
        releaseOnScreenOn = other.releaseOnScreenOn ?: releaseOnScreenOn,
        onDemand = other.onDemand ?: onDemand,
        onDemandLock = other.onDemandLock ?: onDemandLock,
        onDemandGraceSeconds = other.onDemandGraceSeconds ?: onDemandGraceSeconds
    )
}

fun lifecycleEntryToJson(e: LifecycleEntry): JSONObject {
    val o = JSONObject()
    e.standbyBucket?.let { o.put("standbyBucket", it) }
    e.batteryExempt?.let { o.put("batteryExempt", it) }
    e.compile?.let { o.put("compile", it) }
    e.before?.let { b ->
        val s = JSONObject()
        b.batteryExempt?.let { s.put("batteryExempt", it) }
        b.standbyBucket?.let { s.put("standbyBucket", it) }
        b.runInBackground?.let { s.put("runInBackground", it) }
        b.runAnyInBackground?.let { s.put("runAnyInBackground", it) }
        o.put("before", s)
    }
    e.screenOffExit?.let { o.put("screenOffExit", it) }
    e.screenOffEffect?.let { o.put("screenOffEffect", it.json) }
    e.releaseOnScreenOn?.let { o.put("releaseOnScreenOn", it) }
    e.onDemand?.let { o.put("onDemand", it) }
    e.onDemandLock?.let { o.put("onDemandLock", it.json) }
    e.onDemandGraceSeconds?.let { o.put("onDemandGraceSeconds", it) }
    return o
}

fun lifecycleEntryFromJson(o: JSONObject): LifecycleEntry {
    val b = o.optJSONObject("before")
    return LifecycleEntry(
        standbyBucket = if (o.has("standbyBucket")) o.optInt("standbyBucket") else null,
        batteryExempt = if (o.has("batteryExempt")) o.optBoolean("batteryExempt") else null,
        compile = if (o.has("compile")) o.optBoolean("compile") else null,
        before = b?.let {
            LifecycleSnapshot(
                batteryExempt = if (it.has("batteryExempt")) it.optBoolean("batteryExempt") else null,
                standbyBucket = if (it.has("standbyBucket")) it.optInt("standbyBucket") else null,
                runInBackground = it.optString("runInBackground").ifEmpty { null },
                runAnyInBackground = it.optString("runAnyInBackground").ifEmpty { null }
            )
        },
        screenOffExit = if (o.has("screenOffExit")) o.optBoolean("screenOffExit") else null,
        // Unbekannte Wirkung -> null (nicht anfassen), nicht raten
        screenOffEffect = ScreenOffEffect.fromJson(o.optString("screenOffEffect")),
        releaseOnScreenOn = if (o.has("releaseOnScreenOn")) o.optBoolean("releaseOnScreenOn") else null,
        onDemand = if (o.has("onDemand")) o.optBoolean("onDemand") else null,
        onDemandLock = OnDemandLock.fromJson(o.optString("onDemandLock")),
        onDemandGraceSeconds = if (o.has("onDemandGraceSeconds")) o.optInt("onDemandGraceSeconds") else null
    )
}

fun lifecycleToJson(entries: Map<String, LifecycleEntry>): JSONObject {
    val obj = JSONObject()
    for ((pkg, e) in entries) {
        val j = lifecycleEntryToJson(e)
        if (j.length() > 0) obj.put(pkg, j)
    }
    return obj
}

fun lifecycleFromJson(obj: JSONObject?): Map<String, LifecycleEntry> {
    if (obj == null) return emptyMap()
    val out = mutableMapOf<String, LifecycleEntry>()
    for (pkg in obj.keys()) {
        val e = obj.optJSONObject(pkg) ?: continue
        out[pkg] = lifecycleEntryFromJson(e)
    }
    return out
}
