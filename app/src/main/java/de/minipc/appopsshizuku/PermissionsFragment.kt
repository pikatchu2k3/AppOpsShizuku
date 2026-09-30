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
            "CAMERA", "RECORD_AUDIO", "BODY_SENSORS", "ACTIVITY_RECOGNITION"
        )
        private val CATEGORY_DATA = listOf(
            "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR",
            "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_CLIPBOARD", "WRITE_CLIPBOARD"
        )
        private val CATEGORY_MEDIA = listOf(
            "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO",
            "READ_MEDIA_VISUAL_USER_SELECTED"
        )
        private val CATEGORY_ACCOUNT = listOf("GET_ACCOUNTS")
        private val CATEGORY_BACKGROUND = listOf(
            "RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND",
            "START_FOREGROUND_SERVICES_FROM_BACKGROUND", "GET_USAGE_STATS"
        )
        private val CATEGORY_IDENTITY = listOf("READ_DEVICE_IDENTIFIERS", "MOCK_LOCATION")
        private val CATEGORY_SYSTEM_UI = listOf(
            "SYSTEM_ALERT_WINDOW", "ACCESS_NOTIFICATIONS", "TURN_SCREEN_ON", "PICTURE_IN_PICTURE"
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
            restoreAll()
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
            for (op in ops) {
                opStates[op.opName] = try {
                    controller.getMode(packageName, op)
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

    private fun buildItems(): List<OpsListItem> {
        val items = mutableListOf<OpsListItem>()
        val byName = ops.associateBy { it.opName }
        val categories = listOf(
            Pair(appContext.getString(R.string.category_sensors), CATEGORY_SENSORS),
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
            items.addAll(catOps.map { OpsListItem.OpItem(it, opStates[it.opName]) })
        }
        return items
    }

    private fun onModeSelected(op: AppOp, mode: String) {
        executor.execute {
            val res = controller.setMode(packageName, op, mode)
            // NACH dem Setzen den echten Systemzustand lesen: manche Ops (z.B.
            // TURN_SCREEN_ON, live 09/2026 auf LineageOS 14) werden vom System still
            // ignoriert (exit 0, aber keine Aenderung) -> sonst zeigt die UI "deny",
            // das System ist aber "default" (Irrefuehrung + falscher Blockiert-Zaehler).
            val actual = if (res.exitCode == 0)
                runCatching { controller.getMode(packageName, op) }.getOrNull()
            else null
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
                } else if (res.exitCode == 0) {
                    // Op nicht aenderbar: echten Zustand anzeigen, NIE den Wunsch-Modus
                    opStates[op.opName] = actual ?: "default"
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

    private fun restoreAll() {
        setLoading(true)
        executor.execute {
            var failed = 0
            for (op in ops) {
                val res = controller.setMode(packageName, op, "default")
                if (res.exitCode != 0) failed++
                opStates[op.opName] = "default"
            }
            // Alles auf Standard -> kein explizites Profil mehr gespeichert
            profileStore.removeProfile(packageName)
            handler.post {
                setLoading(false)
                refreshList()
                Toast.makeText(
                    appContext,
                    if (failed == 0) appContext.getString(R.string.restore_done)
                    else "Restore: $failed Fehler",
                    Toast.LENGTH_SHORT
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
