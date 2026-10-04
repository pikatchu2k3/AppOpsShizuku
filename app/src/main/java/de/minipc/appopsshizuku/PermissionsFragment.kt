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
import de.minipc.appopsshizuku.appops.AppOp
import de.minipc.appopsshizuku.appops.OpEffect
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
        private const val ARG_PACKAGE = "pkg"

        private val CATEGORY_SENSORS = listOf(
            "CAMERA", "RECORD_AUDIO", "BODY_SENSORS", "ACTIVITY_RECOGNITION",
            "BLUETOOTH_SCAN", "NEARBY_WIFI_DEVICES", "ACCESS_LOCAL_NETWORK"
        )
        private val CATEGORY_LOCATION = listOf(
            "FINE_LOCATION", "COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "ACCESS_MEDIA_LOCATION"
        )
        private val CATEGORY_DATA = listOf(
            "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR",
            "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_CLIPBOARD", "WRITE_CLIPBOARD",
            "READ_PHONE_STATE", "READ_SMS", "RECEIVE_SMS"
        )
        private val CATEGORY_MEDIA = listOf(
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
            "READ_MEDIA_VISUAL_USER_SELECTED", "MANAGE_EXTERNAL_STORAGE"
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
    private lateinit var packageName: String
    private var adapter: AppOpsAdapter? = null

    /** opName -> aktueller Mode (null = noch nicht geladen) */
    private val opStates = mutableMapOf<String, String?>()
    private val ops: List<AppOp> = PrivacyOps.PRIVACY_OPS
    private var loaded = false
    private var loading = false

    /** Zwischenspeicher fuer `dumpsys package <pkg>`: pro Ladevorgang EINMAL lesen,
     *  nicht einmal je Permission-Op (kostet sonst mehrere Sekunden pro Reiterwechsel). */
    private var dumpsysCache: String? = null

    /** Zwischenspeicher fuer `pm list permissions` (Geraetewissen), ebenfalls einmal pro Ladevorgang. */
    private var permissionsCache: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = requireContext().applicationContext
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()

        val shizukuOn = runCatching {
            de.minipc.appopsshizuku.shizuku.ShizukuBackend().let {
                it.isAvailable() && it.hasPermission()
            }
        }.getOrDefault(false)
        backend = if (shizukuOn) de.minipc.appopsshizuku.shizuku.ShizukuBackend() else NoopBackend()
        controller = AppOpsController(backend)
        profileStore = PrivacyProfileStore(appContext)
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
        adapter?.submitList(buildItems())
        updateStats()
    }

    private fun loadAllModes() {
        loading = true
        setLoading(true)
        executor.execute {
            dumpsysCache = null
            permissionsCache = null
            for (op in ops) {
                opStates[op.opName] = try {
                    readMode(op)
                } catch (e: Exception) {
                    null
                }
            }
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
        if (!op.readViaPermission) return controller.getMode(packageName, op)
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
            Pair(appContext.getString(R.string.category_system_ui), CATEGORY_SYSTEM_UI)
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
            else -> OpsListItem.OpItem(op, state)
        }
    }

    private fun onModeSelected(selected: AppOp, mode: String) {
        // Die Liste kann eine beschriftete Kopie liefern ("nicht setzbar"/"nicht angefordert")
        // -> Original aus dem Katalog nehmen, sonst landet der Zusatz im gespeicherten Profil.
        val op = ops.firstOrNull { it.opName == selected.opName } ?: selected
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
            handler.post {
                if (effective) {
                    opStates[op.opName] = mode
                    // Profil-Speicherung darf den UI-Thread NIE crashen
                    // (z.B. korruptes/leeres JSON in den Prefs -> JSONException).
                    runCatching { profileStore.setOpMode(packageName, op.opName, mode) }
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.op_set_ok, op.label, mode),
                        Toast.LENGTH_SHORT
                    ).show()
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
            var failed = 0
            for (op in ops) {
                val res = controller.setMode(packageName, op, "ignore")
                if (res.exitCode != 0) failed++
                opStates[op.opName] = "ignore"
                runCatching { profileStore.setOpMode(packageName, op.opName, "ignore") }
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
            var failed = 0
            for (op in ops) {
                // Ops, die dieses Geraet nicht kennt (Zustand null), gar nicht erst anfassen:
                // der Versuch wuerde nur als Fehler gezaehlt und die Rueckmeldung verwaessern.
                if (opStates[op.opName] == null) continue
                val res = controller.setMode(packageName, op, OpReset.modeFor(op))
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
