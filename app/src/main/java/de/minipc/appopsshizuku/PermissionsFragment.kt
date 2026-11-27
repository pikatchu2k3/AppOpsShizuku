package de.minipc.appopsshizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import de.minipc.appopsshizuku.appops.AppOpsController
import de.minipc.appopsshizuku.appops.AppOpsDump
import de.minipc.appopsshizuku.appops.AccessLog
import de.minipc.appopsshizuku.appops.AppOp
import de.minipc.appopsshizuku.appops.OpAccess
import de.minipc.appopsshizuku.appops.OpEffect
import de.minipc.appopsshizuku.appops.Recommendations
import de.minipc.appopsshizuku.appops.WhileInUse
import de.minipc.appopsshizuku.appops.OpReset
import de.minipc.appopsshizuku.appops.PrivacyOps
import de.minipc.appopsshizuku.appops.PrivacyProfileStore
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.ui.AppOpsAdapter
import de.minipc.appopsshizuku.ui.OpsListItem
import java.util.concurrent.Executors

/**
 * Reiter "Berechtigungen" der App-Detailseite (frueher AppOpsActivity):
 * App-Header, Statistik (blockiert/erlaubt/standard), Privacy-Ops gruppiert in
 * Kategorien, Mode-Spinner pro Op, "Alle auf Standard" + Lade-Fortschritt.
 *
 * Backend, Controller und opStates leben im Fragment (nicht in der View), damit
 * der Zustand ein Neuaufbauen der View beim Reiter-Wechsel ueberlebt.
 */
class PermissionsFragment : Fragment() {

    companion object {
        /** Welche Ops dieses Geraet kennt - einmal pro Prozess geprüft, nicht je App. */
        private val opSupport = AppOpsDump.Support()

        private const val ARG_PACKAGE = "pkg"

        private val CATEGORY_SENSORS = listOf(
            "CAMERA", "RECORD_AUDIO", "BODY_SENSORS", "ACTIVITY_RECOGNITION",
            "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "NEARBY_WIFI_DEVICES", "ACCESS_LOCAL_NETWORK"
        )
        private val CATEGORY_LOCATION = listOf(
            "FINE_LOCATION", "COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION"
        )
        private val CATEGORY_DATA = listOf(
            "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR",
            "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_CLIPBOARD", "WRITE_CLIPBOARD",
            "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "READ_SMS", "RECEIVE_SMS", "SEND_SMS"
        )
        private val CATEGORY_MEDIA = listOf(
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
            "READ_MEDIA_VISUAL_USER_SELECTED", "MANAGE_EXTERNAL_STORAGE",
            "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"
        )
        private val CATEGORY_ACCOUNT = listOf("GET_ACCOUNTS")
        private val CATEGORY_BACKGROUND = listOf(
            "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND",
            "START_FOREGROUND_SERVICES_FROM_BACKGROUND", "GET_USAGE_STATS"
        )
        private val CATEGORY_IDENTITY = listOf("READ_DEVICE_IDENTIFIERS", "MOCK_LOCATION")
        private val CATEGORY_SYSTEM_UI = listOf(
            "SYSTEM_ALERT_WINDOW", "ACCESS_NOTIFICATIONS", "POST_NOTIFICATION", "TURN_SCREEN_ON",
            "PICTURE_IN_PICTURE"
        )
        private val CATEGORY_SPECIAL = listOf(
            "REQUEST_INSTALL_PACKAGES", "WRITE_SETTINGS", "SCHEDULE_EXACT_ALARM", "USE_FULL_SCREEN_INTENT"
        )

        fun newInstance(packageName: String) = PermissionsFragment().apply {
            arguments = Bundle().apply { putString(ARG_PACKAGE, packageName) }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private lateinit var backend: de.minipc.appopsshizuku.shizuku.ShellBackend
    private lateinit var controller: AppOpsController
    private lateinit var profileStore: PrivacyProfileStore
    private lateinit var wiuStore: de.minipc.appopsshizuku.appops.WhileInUseStore
    private lateinit var quarantineStore: de.minipc.appopsshizuku.appops.QuarantineStore
    private lateinit var packageName: String
    private var adapter: AppOpsAdapter? = null

    /** opName -> aktueller Mode (null = noch nicht geladen) */
    private val opStates = mutableMapOf<String, String?>()
    /** Katalog mit lokalisierten Namen (in onCreate gesetzt, sobald ein Context da ist). */
    private var ops: List<AppOp> = PrivacyOps.PRIVACY_OPS
    private var loaded = false
    private var loading = false

    /** Zwischenspeicher fuer `dumpsys package <pkg>`: pro Ladevorgang EINMAL lesen,
     *  nicht einmal je Permission-Op (kostet sonst mehrere Sekunden pro Reiterwechsel). */
    private var dumpsysCache: String? = null

    /** Zwischenspeicher fuer `pm list permissions` (Geraetewissen), ebenfalls einmal pro Ladevorgang. */
    private var permissionsCache: String? = null

    /** Gesamtausgabe von `cmd appops get <pkg>` waehrend eines Ladevorgangs; sonst null (dann Einzelabfrage). */
    private var appOpsDump: String? = null

    /** Zugriffsprotokoll (opName -> letzte Nutzung/Ablehnung), aus EINEM `cmd appops get <pkg>`. */
    private var access: Map<String, OpAccess> = emptyMap()

    /** Aktuelle Empfehlungen (erteilt, aber offenbar nicht gebraucht). */
    private var suggestions: List<Recommendations.Suggestion> = emptyList()

    /** App-Kategorie und Installationsalter fuer die Empfehlungen (einmal gelesen). */
    private var appCategory: Int = -1
    private var installedAgoMs: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = requireContext().applicationContext
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        ops = de.minipc.appopsshizuku.ui.OpLabels.localized(appContext)

        val shizukuOn = runCatching {
            de.minipc.appopsshizuku.shizuku.ShizukuBackend().let {
                it.isAvailable() && it.hasPermission()
            }
        }.getOrDefault(false)
        backend = if (shizukuOn) de.minipc.appopsshizuku.shizuku.ShizukuBackend() else NoopBackend()
        controller = AppOpsController(backend)
        profileStore = PrivacyProfileStore(appContext)
        wiuStore = de.minipc.appopsshizuku.appops.WhileInUseStore(appContext)
        quarantineStore = de.minipc.appopsshizuku.appops.QuarantineStore(appContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_permissions, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadAppInfo(view)

        val opsAdapter = AppOpsAdapter { op, mode -> onModeSelected(op, mode) }
        adapter = opsAdapter
        view.findViewById<RecyclerView>(R.id.opList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = opsAdapter
        }

        view.findViewById<MaterialButton>(R.id.suggestionApply).setOnClickListener { applySuggestions() }
        view.findViewById<MaterialButton>(R.id.quarantineRelease).setOnClickListener { releaseQuarantine() }
        view.findViewById<MaterialButton>(R.id.templateButton).let { button ->
            if (packageName == requireContext().packageName) button.visibility = View.GONE
            else button.setOnClickListener { showTemplateDialog() }
        }

        view.findViewById<MaterialButton>(R.id.restoreAllButton).setOnClickListener {
            confirmRestoreAll()
        }
        view.findViewById<MaterialButton>(R.id.blockAllButton).let { button ->
            // Ein-Tipp-Lockdown (QW-4): die eigene App schuetzt sich selbst.
            val isSelf = packageName == requireContext().packageName
            if (isSelf) {
                button.isEnabled = false
                button.setText(R.string.block_all_own_app)
            } else {
                button.setOnClickListener { confirmBlockAll() }
            }
        }

        when {
            // View neu aufgebaut (Reiter-Wechsel): vorhandenen Zustand anzeigen, nicht neu laden
            loaded -> refreshList()
            loading -> setLoading(true)
            else -> loadAllModes()
        }
    }

    override fun onDestroyView() {
        adapter = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        // Laufende Tasks (z.B. Profil-Speicherung) duerfen noch zu Ende laufen
        executor.shutdown()
        super.onDestroy()
    }

    private fun loadAppInfo(root: View) {
        val pm = requireContext().packageManager
        try {
            val info = pm.getApplicationInfo(packageName, 0)
            root.findViewById<ImageView>(R.id.appIcon).setImageDrawable(pm.getApplicationIcon(info))
            root.findViewById<TextView>(R.id.appLabel).text = pm.getApplicationLabel(info)
            root.findViewById<TextView>(R.id.appPackage).text = packageName
        } catch (e: PackageManager.NameNotFoundException) {
            root.findViewById<TextView>(R.id.appLabel).text = packageName
        }
    }

    private fun setLoading(visible: Boolean) {
        view?.findViewById<LinearProgressIndicator>(R.id.loadingBar)?.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    /** Liste + Statistik aus opStates neu zeichnen (no-op ohne View). */
    private fun refreshList() {
        if (view == null) return
        recomputeSuggestions()
        adapter?.submitList(buildItems())
        updateStats()
        updateSuggestionCard()
        updateQuarantineCard()
    }

    private fun loadAllModes() {
        loading = true
        setLoading(true)
        executor.execute {
            dumpsysCache = null
            permissionsCache = null
            // EIN `cmd appops get <pkg>` fuer alle Ops (statt je Op ein Prozess) - dient zugleich dem Zugriffsprotokoll
            val appops = runCatching { backend.exec("cmd", "appops", "get", packageName) }.getOrNull()
            appOpsDump = appops?.takeIf { it.exitCode == 0 }?.stdout
            access = appOpsDump?.let { AccessLog.parse(it) } ?: emptyMap()
            for (op in ops) {
                opStates[op.opName] = try {
                    readMode(op)
                } catch (e: Exception) {
                    null
                }
            }
            appOpsDump = null
            readAppMeta()
            handler.post {
                loading = false
                loaded = true
                setLoading(false)
                refreshList()
            }
        }
    }

    /**
     * Systemzustand einer Op.
     * - null            = auf diesem Geraet nicht les-/setzbar (Op- oder Permission-Name unbekannt)
     * - NOT_REQUESTED   = die App deklariert diese Berechtigung gar nicht (gegenstandslos, NICHT "nicht setzbar")
     */
    private fun readMode(op: AppOp, useCache: Boolean = true): String? {
        // "Nur waehrend Nutzung" ist ein Modus dieser App, kein Systemzustand: so anzeigen
        if (op.permissionName != null && wiuStore.has(packageName, op.opName)) return WhileInUse.MODE
        if (!op.readViaPermission) {
            val dump = appOpsDump
            if (dump != null) {
                // Beim Laden: aus der Gesamtausgabe; unbekannte Ops (nur dieses Geraet weiss das) einmal pro Prozess pruefen
                val known = opSupport.isKnown(op.opName) { backend.exec("cmd", "appops", "get", packageName, op.opName) }
                return if (known == true) AppOpsDump.mode(controller, dump, op) else null
            }
            return controller.getMode(packageName, op)   // Rueckleseprobe nach dem Setzen: frisch, einzeln
        }
        val perm = op.permissionName ?: return null
        val dump = if (useCache) (dumpsysCache ?: readPackageDump()) else readPackageDump()
        if (dump == null) return null
        val state = OpEffect.permissionState(dump, perm)
        if (state != OpEffect.NOT_REQUESTED) return state
        // Keine Zeile in `dumpsys package`: entweder fordert die App die Berechtigung nicht an, oder
        // das Geraet kennt sie selbst nicht (Android-17-Recht auf Android 14). Das entscheidet die
        // Geraete-Registry — sonst behauptet die UI "nicht angefordert", wo "nicht setzbar" gilt.
        return OpEffect.notRequestedOrUnknown(permissionsCache ?: readPermissionRegistry(), perm)
    }

    /**
     * Geraetewissen (`pm list permissions`) einmal lesen und fuer alle Ops dieses Ladevorgangs ablegen.
     * WICHTIG: `-g` ist Pflicht. Ohne `-g` fehlen die Laufzeit-Rechte komplett (am Redmi gemessen:
     * 1149 Zeilen ohne, 1318 mit `-g`; ACCESS_FINE_LOCATION und POST_NOTIFICATIONS fehlen ohne) —
     * dann wuerde jedes nicht angeforderte Recht faelschlich als "nicht setzbar" erscheinen.
     */
    private fun readPermissionRegistry(): String? {
        val res = backend.exec("pm", "list", "permissions", "-g")
        if (res.exitCode != 0) return null
        permissionsCache = res.stdout
        return res.stdout
    }

    /** `dumpsys package <pkg>` einmal lesen und fuer alle Permission-Ops dieses Ladevorgangs ablegen. */
    private fun readPackageDump(): String? {
        val res = backend.exec("dumpsys", "package", packageName)
        if (res.exitCode != 0) return null
        dumpsysCache = res.stdout
        return res.stdout
    }

    private fun readAppMeta() {
        runCatching {
            val pm = appContext.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            installedAgoMs = System.currentTimeMillis() - info.firstInstallTime
            appCategory = info.applicationInfo?.category ?: -1
        }
    }

    private fun recomputeSuggestions() {
        if (packageName == appContext.packageName) {
            suggestions = emptyList()
            return
        }
        suggestions = Recommendations.suggest(
            states = opStates.toMap(),
            access = access,
            permissionOps = ops.filter { it.permissionName != null }.map { it.opName }.toSet(),
            category = appCategory,
            installedAgoMs = installedAgoMs
        )
    }

    private fun relative(agoMs: Long): CharSequence {
        val now = System.currentTimeMillis()
        return android.text.format.DateUtils.getRelativeTimeSpanString(
            now - agoMs, now, android.text.format.DateUtils.MINUTE_IN_MILLIS
        )
    }

    private fun reasonText(reason: Recommendations.Reason): String = when (reason) {
        Recommendations.Reason.UNUSED ->
            appContext.getString(R.string.suggest_reason_unused, Recommendations.UNUSED_DAYS)
        Recommendations.Reason.CATEGORY -> appContext.getString(R.string.suggest_reason_category)
    }

    /** Zeile unter dem Op-Namen: zuletzt benutzt / abgelehnt / gerade aktiv (+ Empfehlungsgrund). */
    private fun accessLine(op: AppOp, state: String?): CharSequence? {
        val a = access[op.opName]
        val parts = mutableListOf<CharSequence>()
        when {
            a?.running == true -> parts += appContext.getString(R.string.access_running)
            a?.lastAccessAgoMs != null ->
                parts += appContext.getString(R.string.access_last_used, relative(a.lastAccessAgoMs))
            state == "allow" -> parts += appContext.getString(R.string.access_never)
        }
        a?.lastRejectAgoMs?.let { parts += appContext.getString(R.string.access_last_rejected, relative(it)) }
        suggestions.firstOrNull { it.opName == op.opName }?.let { parts += reasonText(it.reason) }
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    private fun updateSuggestionCard() {
        val root = view ?: return
        val card = root.findViewById<View>(R.id.suggestionCard)
        if (suggestions.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        val byName = ops.associateBy { it.opName }
        card.visibility = View.VISIBLE
        root.findViewById<TextView>(R.id.suggestionTitle).text =
            appContext.getString(R.string.suggest_card_title, suggestions.size)
        root.findViewById<TextView>(R.id.suggestionText).text = appContext.getString(
            R.string.suggest_card_text,
            suggestions.joinToString(", ") { s ->
                "${byName[s.opName]?.label ?: s.opName} (${reasonText(s.reason)})"
            }
        )
    }

    /** Empfehlungen umsetzen: entziehen, danach den echten Zustand zuruecklesen (nie raten). */
    private fun applySuggestions() {
        val todo = suggestions.mapNotNull { s -> ops.firstOrNull { it.opName == s.opName } }
        if (todo.isEmpty()) return
        setLoading(true)
        executor.execute {
            var ok = 0
            var failed = 0
            dumpsysCache = null
            for (op in todo) {
                val res = controller.setMode(packageName, op, "deny")
                dumpsysCache = null
                val actual = if (res.exitCode == 0) runCatching { readMode(op, useCache = false) }.getOrNull() else null
                if (res.exitCode == 0 && OpEffect.isSetEffective(op.permissionName, "deny", actual)) {
                    ok++
                    opStates[op.opName] = actual
                    runCatching { profileStore.setOpMode(packageName, op.opName, "deny") }
                } else {
                    failed++
                }
            }
            if (ok > 0) runCatching {
                de.minipc.appopsshizuku.appops.ChangeLog.recordInfo(
                    appContext, appContext.getString(R.string.log_info_suggest, selfAppLabel(), ok)
                )
            }
            handler.post {
                setLoading(false)
                refreshList()
                Toast.makeText(
                    appContext,
                    if (failed == 0) appContext.getString(R.string.suggest_done, ok)
                    else appContext.getString(R.string.suggest_partial, ok, failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Vorlagen-Auswahl mit Moeglichkeit, das aktuelle Profil als JSON zu teilen. */
    private fun showTemplateDialog() {
        val ids = de.minipc.appopsshizuku.appops.ProfileTemplate.Id.values()
        val names = ids.map {
            appContext.getString(
                when (it) {
                    de.minipc.appopsshizuku.appops.ProfileTemplate.Id.GAME -> R.string.template_game
                    de.minipc.appopsshizuku.appops.ProfileTemplate.Id.MESSENGER -> R.string.template_messenger
                    de.minipc.appopsshizuku.appops.ProfileTemplate.Id.BANKING -> R.string.template_banking
                }
            )
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.template_title)
            .setItems(names) { _, which -> confirmTemplate(ids[which], names[which]) }
            .setNeutralButton(R.string.template_share) { _, _ -> shareProfile() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun confirmTemplate(id: de.minipc.appopsshizuku.appops.ProfileTemplate.Id, name: String) {
        val count = de.minipc.appopsshizuku.appops.ProfileTemplate.opsFor(id).size
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(name)
            .setMessage(appContext.getString(R.string.template_confirm, count))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.template_apply) { _, _ -> applyTemplate(id) }
            .show()
    }

    /** Vorlage anwenden: jede Op setzen, zuruecklesen, nur bei Erfolg ins Profil + Protokoll. */
    private fun applyTemplate(id: de.minipc.appopsshizuku.appops.ProfileTemplate.Id) {
        val wanted = de.minipc.appopsshizuku.appops.ProfileTemplate.opsFor(id)
        val byName = ops.associateBy { it.opName }
        setLoading(true)
        executor.execute {
            var ok = 0
            var failed = 0
            dumpsysCache = null
            for ((opName, mode) in wanted) {
                val op = byName[opName] ?: continue
                // Geraet kennt die Op nicht ODER die App fordert die Berechtigung gar nicht an: nichts zu tun
                // (sonst meldet die Vorlage faelschlich "nicht aenderbar")
                if (opStates[opName] == null || opStates[opName] == OpEffect.NOT_REQUESTED) continue
                val prev = opStates[opName]
                val res = controller.setMode(packageName, op, mode)
                dumpsysCache = null
                val actual = if (res.exitCode == 0) runCatching { readMode(op, useCache = false) }.getOrNull() else null
                if (res.exitCode == 0 && OpEffect.isSetEffective(op.permissionName, mode, actual)) {
                    ok++
                    opStates[opName] = mode
                    runCatching { profileStore.setOpMode(packageName, opName, mode) }
                    runCatching { de.minipc.appopsshizuku.appops.ChangeLog.recordOp(appContext, packageName, op, prev, mode) }
                } else failed++
            }
            if (ok > 0) runCatching {
                de.minipc.appopsshizuku.appops.ChangeLog.recordInfo(
                    appContext, appContext.getString(R.string.log_info_template, selfAppLabel(), ok)
                )
            }
            handler.post {
                setLoading(false)
                refreshList()
                Toast.makeText(
                    appContext,
                    if (failed == 0) appContext.getString(R.string.template_done, ok)
                    else appContext.getString(R.string.template_partial, ok, failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Aktuelles Profil dieser App als JSON teilen (ACTION_SEND) - zum Weitergeben einer Vorlage. */
    private fun shareProfile() {
        val profile = mapOf(packageName to profileStore.getProfile(packageName))
        if (profile[packageName].isNullOrEmpty()) {
            Toast.makeText(appContext, R.string.template_share_empty, Toast.LENGTH_LONG).show()
            return
        }
        val json = PrivacyProfileStore.toJson(profile)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_TEXT, json)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "AppOps ${selfAppLabel()}")
        }
        startActivity(android.content.Intent.createChooser(send, appContext.getString(R.string.template_share)))
    }

    private fun selfAppLabel(): String = runCatching {
        appContext.packageManager.getApplicationInfo(packageName, 0).loadLabel(appContext.packageManager).toString()
    }.getOrDefault(packageName)

    private fun buildItems(): List<OpsListItem> {
        val items = mutableListOf<OpsListItem>()
        val byName = ops.associateBy { it.opName }
        val categories = listOf(
            Pair(appContext.getString(R.string.category_sensors), CATEGORY_SENSORS),
            Pair(appContext.getString(R.string.category_location), CATEGORY_LOCATION),
            Pair(appContext.getString(R.string.category_data), CATEGORY_DATA),
            Pair(appContext.getString(R.string.category_media), CATEGORY_MEDIA),
            Pair(appContext.getString(R.string.category_account_activity), CATEGORY_ACCOUNT),
            Pair(appContext.getString(R.string.category_background), CATEGORY_BACKGROUND),
            Pair(appContext.getString(R.string.category_identity), CATEGORY_IDENTITY),
            Pair(appContext.getString(R.string.category_system_ui), CATEGORY_SYSTEM_UI),
            Pair(appContext.getString(R.string.category_special), CATEGORY_SPECIAL)
        )
        for ((title, names) in categories) {
            val catOps = names.mapNotNull { byName[it] }
            if (catOps.isEmpty()) continue
            items.add(OpsListItem.CategoryHeader(title))
            items.addAll(catOps.map { opItem(it) })
        }
        // Sicherheitsnetz: Katalog-Ops ohne Kategorie-Eintrag nie stillschweigend ausblenden
        val placed = categories.flatMap { it.second }.toSet()
        val rest = ops.filter { it.opName !in placed }
        if (rest.isNotEmpty()) {
            items.add(OpsListItem.CategoryHeader(appContext.getString(R.string.category_other)))
            items.addAll(rest.map { opItem(it) })
        }
        return items
    }

    /**
     * Kennzeichnung ehrlich halten: "nicht setzbar" (das Geraet kennt die Op/Permission nicht) und
     * "nicht angefordert" (die App deklariert die Berechtigung gar nicht) sind zwei verschiedene
     * Aussagen. Nicht angeforderte Zeilen zeigen "Standard" — dort ist nichts blockiert.
     */
    private fun opItem(op: AppOp): OpsListItem.OpItem {
        val state = opStates[op.opName]
        return when (state) {
            null -> OpsListItem.OpItem(
                op.copy(label = appContext.getString(R.string.op_label_unsupported, op.label)), null
            )
            OpEffect.NOT_REQUESTED -> OpsListItem.OpItem(
                op.copy(label = appContext.getString(R.string.op_label_not_requested, op.label)), "default"
            )
            else -> OpsListItem.OpItem(
                op, state,
                accessText = accessLine(op, state),
                suggested = suggestions.any { it.opName == op.opName }
            )
        }
    }

    private fun onModeSelected(selected: AppOp, mode: String) {
        // Die Liste kann eine beschriftete Kopie liefern ("nicht setzbar"/"nicht angefordert")
        // -> Original aus dem Katalog nehmen, sonst landet der Zusatz im gespeicherten Profil.
        val op = ops.firstOrNull { it.opName == selected.opName } ?: selected
        val prevMode = opStates[op.opName]
        if (mode == WhileInUse.MODE) {
            enableWhileInUse(op)
            return
        }
        // Anderer Modus gewaehlt: "Nur waehrend Nutzung" fuer diese Op beenden
        if (wiuStore.has(packageName, op.opName)) {
            wiuStore.set(packageName, op.opName, false)
            LifecycleScreenService.sync(appContext)
        }
        // Nicht angeforderte Berechtigung: nicht erst setzen (waere ein wirkungsloser Shell-Aufruf)
        if (opStates[op.opName] == OpEffect.NOT_REQUESTED) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.op_not_requested, op.label),
                Toast.LENGTH_LONG
            ).show()
            // Der Spinner behaelt sonst die getippte Auswahl, obwohl nichts gesetzt wurde —
            // die Zeile wuerde "allow" behaupten, die Berechtigung ist aber weiterhin nicht erteilt.
            handler.post { refreshList() }
            return
        }
        executor.execute {
            val res = controller.setMode(packageName, op, mode)
            // NACH dem Setzen den echten Systemzustand lesen: manche Ops (z.B.
            // TURN_SCREEN_ON, live 09/2026 auf LineageOS 14) werden vom System still
            // ignoriert (exit 0, aber keine Aenderung) -> sonst zeigt die UI "deny",
            // das System ist aber "default" (Irrefuehrung + falscher Blockiert-Zaehler).
            val actual = if (res.exitCode == 0)
                runCatching { readMode(op) }.getOrNull()
            else null
            // Unbekannte Op/Permission: schon der Ausgangszustand war nicht lesbar -> "nicht setzbar", kein Exit-Fehler
            val unsupported = res.exitCode != 0 && opStates[op.opName] == null
            val effective = res.exitCode == 0 &&
                de.minipc.appopsshizuku.appops.OpEffect.isSetEffective(op.permissionName, mode, actual)
            // Blockiert, aber ohne "nicht mehr fragen"? Dann darf die App die Berechtigung neu anfordern -
            // `pm set-permission-flags` kennt nicht jedes Gerät, und der Befehl wird nicht allein geglaubt.
            val canAskAgain = effective && op.permissionName != null && (mode == "deny" || mode == "ignore") &&
                de.minipc.appopsshizuku.appops.OpEffect.isUserFixed(
                    backend.exec("dumpsys", "package", packageName).stdout, op.permissionName
                ) == false
            handler.post {
                if (effective) {
                    // "reset" ist eine Handlung, kein Zustand: angezeigt wird, was das System jetzt meldet
                    opStates[op.opName] = if (mode == "reset") actual else mode
                    // Profil-Speicherung darf den UI-Thread NIE crashen
                    // (z.B. korruptes/leeres JSON in den Prefs -> JSONException).
                    // Ein Reset erzwingt nichts: im Profil als "default" ablegen (aeltere Dateien bleiben lesbar).
                    runCatching { profileStore.setOpMode(packageName, op.opName, if (mode == "reset") "default" else mode) }
                    runCatching {
                        de.minipc.appopsshizuku.appops.ChangeLog.recordOp(appContext, packageName, op, prevMode, mode)
                    }
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.op_set_ok, op.label, mode),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (canAskAgain) {
                        Toast.makeText(appContext, R.string.op_blocked_can_ask_again, Toast.LENGTH_LONG).show()
                    }
                    // deny auf reinen AppOps (Zwischenablage) laesst die Ziel-App beim
                    // Start crashen (SecurityException, live verifiziert) -> Hinweis.
                    if (mode == "deny" && op.permissionName == null) {
                        Toast.makeText(appContext, R.string.op_deny_warning, Toast.LENGTH_LONG).show()
                    }
                } else if (res.exitCode == 0 || unsupported) {
                    // Op nicht aenderbar: echten Zustand anzeigen, NIE den Wunsch-Modus
                    // (null bleibt null -> Zeile bleibt als "nicht setzbar" gekennzeichnet)
                    opStates[op.opName] = actual
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.op_not_settable, op.label),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.op_set_fail, op.label, res.exitCode),
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshList()
            }
        }
    }

    /**
     * "Nur waehrend Nutzung" einschalten: jetzt entziehen (die App ist gerade nicht vorne), merken,
     * Nutzungszugriff fuer die eigene App sichern und den Waechter starten. Erteilt wird erst, wenn die
     * App geoeffnet wird.
     */
    private fun enableWhileInUse(op: AppOp) {
        if (packageName == appContext.packageName || opStates[op.opName] == OpEffect.NOT_REQUESTED || opStates[op.opName] == null) {
            Toast.makeText(appContext, appContext.getString(R.string.op_not_settable, op.label), Toast.LENGTH_LONG).show()
            handler.post { refreshList() }
            return
        }
        executor.execute {
            val res = controller.setMode(packageName, op, "deny")
            dumpsysCache = null
            val revoked = res.exitCode == 0 && run {
                wiuStore.set(packageName, op.opName, false) // fuer das Zuruecklesen kurz ohne Modus
                val actual = runCatching { readMode(op, useCache = false) }.getOrNull()
                OpEffect.isSetEffective(op.permissionName, "deny", actual)
            }
            if (revoked) {
                wiuStore.set(packageName, op.opName, true)
                runCatching { OnDemandSupport.grantUsageAccess(appContext, backend) }
                opStates[op.opName] = WhileInUse.MODE
                runCatching { profileStore.setOpMode(packageName, op.opName, "deny") }
            }
            handler.post {
                val err = if (revoked) LifecycleScreenService.sync(appContext) else null
                Toast.makeText(
                    appContext,
                    when {
                        !revoked -> appContext.getString(R.string.op_not_settable, op.label)
                        err != null -> appContext.getString(R.string.while_in_use_service_failed, err)
                        else -> appContext.getString(R.string.while_in_use_on, op.label)
                    },
                    Toast.LENGTH_LONG
                ).show()
                refreshList()
            }
        }
    }

    /** Sammelaktionen setzen jede Op explizit: "Nur waehrend Nutzung" darf danach nichts mehr erteilen. */
    private fun endWhileInUse() {
        if (wiuStore.ops(packageName).isEmpty()) return
        wiuStore.remove(packageName)
        handler.post { LifecycleScreenService.sync(appContext) }
    }

    private fun updateQuarantineCard() {
        val root = view ?: return
        val since = quarantineStore.since(packageName)
        val card = root.findViewById<View>(R.id.quarantineCard)
        if (since == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val when_ = android.text.format.DateUtils.getRelativeTimeSpanString(
            since, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        )
        root.findViewById<TextView>(R.id.quarantineText).text =
            appContext.getString(R.string.quarantine_card_text, when_)
    }

    private fun releaseQuarantine() {
        setLoading(true)
        executor.execute {
            val ok = de.minipc.appopsshizuku.QuarantineManager.release(appContext, packageName)
            for (op in ops) if (op.permissionName == null) opStates[op.opName] = runCatching { readMode(op) }.getOrNull()
            handler.post {
                setLoading(false)
                refreshList()
                Toast.makeText(
                    appContext,
                    if (ok) R.string.quarantine_released else R.string.quarantine_release_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Rueckfrage vor dem Lockdown - erst danach werden alle Ops gesetzt. */
    private fun confirmBlockAll() {
        // requireContext() (Activity-Kontext) ist Pflicht: mit dem Application-Kontext
        // fehlt das Material-Theme und der Dialog stuerzt ab (live reproduziert 09/2026).
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.block_all_confirm_title)
            .setMessage(R.string.block_all_confirm_msg)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .setPositiveButton(R.string.block_all_action) { _, _ -> blockAll() }
            .show()
    }

    /**
     * QW-4: alle Berechtigungen dieser App auf "blockieren" setzen.
     * Ehrlich: Fehlschlaege werden gezaehlt und gemeldet, nicht verschwiegen.
     */
    private fun blockAll() {
        setLoading(true)
        executor.execute {
            endWhileInUse()
            var failed = 0
            for (op in ops) {
                val res = controller.setMode(packageName, op, "ignore")
                if (res.exitCode != 0) failed++
                opStates[op.opName] = "ignore"
                runCatching { profileStore.setOpMode(packageName, op.opName, "ignore") }
            }
            runCatching {
                de.minipc.appopsshizuku.appops.ChangeLog.recordInfo(
                    appContext, appContext.getString(R.string.log_info_block_all, selfAppLabel())
                )
            }
            handler.post {
                setLoading(false)
                refreshList()
                Toast.makeText(
                    appContext,
                    if (failed == 0) appContext.getString(R.string.block_all_done)
                    else appContext.getString(R.string.block_all_partial, failed),
                    if (failed == 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Rueckfrage vor "Alle auf Standard". Der Text muss sagen, was bei Berechtigungen passiert:
     * entzogen, nie erteilt.
     */
    private fun confirmRestoreAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_all_confirm_title)
            .setMessage(R.string.restore_all_confirm_msg)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .setPositiveButton(R.string.restore_all_action) { _, _ -> restoreAll() }
            .show()
    }

    /**
     * "Alle auf Standard": reine AppOps zurueck auf `default`, Permission-Ops per `pm revoke` entziehen
     * (je Op entschieden in OpReset). Danach wird der ECHTE Systemzustand frisch gelesen — der Wunsch-
     * modus landet nie in der Liste.
     */
    private fun restoreAll() {
        setLoading(true)
        executor.execute {
            endWhileInUse()
            var failed = 0
            for (op in ops) {
                // Ops, die dieses Geraet nicht kennt (Zustand null), gar nicht erst anfassen:
                // der Versuch wuerde nur als Fehler gezaehlt und die Rueckmeldung verwaessern.
                if (opStates[op.opName] == null) continue
                // userFixed = false: "Standard" heisst "die App darf wieder fragen"
                val res = controller.setMode(packageName, op, OpReset.modeFor(op), userFixed = false)
                if (res.exitCode != 0) failed++
                // Berechtigungs-Ops zusaetzlich auf AppOp-Ebene auf Standard setzen (siehe OpReset)
                for (extra in OpReset.extraResetCommands(packageName, op)) {
                    val r = backend.exec(*extra.toTypedArray())
                    if (r.exitCode != 0) failed++
                }
            }
            // Endzustand mit EINEM `dumpsys package` lesen (nicht je Op, nicht geraten)
            dumpsysCache = null
            for (op in ops) {
                opStates[op.opName] = runCatching { readMode(op) }.getOrNull()
            }
            // Alles auf Standard -> kein explizites Profil mehr gespeichert
            profileStore.removeProfile(packageName)
            handler.post {
                setLoading(false)
                refreshList()
                Toast.makeText(
                    appContext,
                    if (failed == 0) appContext.getString(R.string.restore_done)
                    else appContext.getString(R.string.restore_partial, failed),
                    if (failed == 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateStats() {
        val root = view ?: return
        var blocked = 0
        var allowed = 0
        var dflt = 0
        for (op in ops) {
            when (opStates[op.opName]) {
                "deny", "ignore" -> blocked++
                "allow" -> allowed++
                else -> dflt++
            }
        }
        root.findViewById<TextView>(R.id.statBlocked).text = blocked.toString()
        root.findViewById<TextView>(R.id.statAllowed).text = allowed.toString()
        root.findViewById<TextView>(R.id.statDefault).text = dflt.toString()
    }
}
