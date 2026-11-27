package de.minipc.appopsshizuku

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import de.minipc.appopsshizuku.appops.NetControl
import de.minipc.appopsshizuku.appops.NetStore
import de.minipc.appopsshizuku.appops.NetSwitch
import de.minipc.appopsshizuku.appops.TrackerCatalog
import de.minipc.appopsshizuku.appops.TrackerCodeScan
import de.minipc.appopsshizuku.appops.TrackerGroup
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import java.io.File
import java.util.concurrent.Executors
import de.minipc.appopsshizuku.ui.lifecycleHandler
import de.minipc.appopsshizuku.ui.lifecycleExecutor

/**
 * Reiter "Netz & Tracker": Diagnose der Tracker-Bausteine (nur lesen) + drei Netz-Schalter.
 * Der Ist-Zustand der Schalter wird bei jedem Oeffnen frisch vom System gelesen; der
 * [NetStore] merkt nur, was WIR aktiviert haben (fuer "Alles zuruecksetzen").
 */
class NetFragment : Fragment() {

    companion object {
        private const val ARG_PACKAGE = "pkg"
        private const val REGISTRAR_PREFIX = "com.google.firebase.components:"
        private const val LOG_TAG = "TrackerCodeScan"

        fun newInstance(packageName: String) = NetFragment().apply {
            arguments = Bundle().apply { putString(ARG_PACKAGE, packageName) }
        }
    }

    private val executor = lifecycleExecutor()
    private val handler = lifecycleHandler()
    private lateinit var appContext: Context
    private lateinit var backend: ShellBackend
    private lateinit var net: NetControl
    private lateinit var store: NetStore
    private lateinit var packageName: String
    private var uid = -1
    private var root: View? = null
    private val switches = mutableMapOf<NetSwitch, MaterialSwitch>()

    /**
     * Schalter, die dieses Geraet nicht anbietet: "Komplett offline" verweigert Android fuer
     * System-Apps ("Can't set package firewall rule for system app ... with appId 1000",
     * am Redmi gemessen). Statt einen Schalter anzubieten, der immer scheitert: sperren + sagen.
     */
    private val locked = mutableSetOf<NetSwitch>()

    /** Klassennamen der App (null = nicht lesbar), einmal pro Fragment geladen. */
    private var foundClasses: Set<String>? = null

    /** Code-Scan: [codeResult] = Ergebnis (null = noch keins), [codeRunningMb] = APK-Groesse waehrend des Laufs. */
    private var codeResult: TrackerCodeScan.Result? = null
    private var codeRunningMb: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = requireContext().applicationContext
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        val info = runCatching { appContext.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        uid = info?.uid ?: -1
        if (info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) locked.add(NetSwitch.OFFLINE)
        val shizukuOn = runCatching {
            ShizukuBackend().let { it.isAvailable() && it.hasPermission() }
        }.getOrDefault(false)
        backend = if (shizukuOn) ShizukuBackend() else NoopBackend()
        net = NetControl(backend)
        store = NetStore(appContext)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_net, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        val list = view.findViewById<ViewGroup>(R.id.netSwitches)
        switches.clear()
        addSwitchRow(list, NetSwitch.BG_DATA, R.string.net_bg_data, R.string.net_bg_data_hint)
        addSwitchRow(
            list, NetSwitch.OFFLINE, R.string.net_offline,
            if (NetSwitch.OFFLINE in locked) R.string.net_offline_system_app else R.string.net_offline_hint
        )
        addSwitchRow(list, NetSwitch.BG_RESTRICT, R.string.net_bg_restrict, R.string.net_bg_restrict_hint)
        view.findViewById<MaterialButton>(R.id.netResetButton).setOnClickListener { resetAll() }
        if (foundClasses == null) loadTrackers() else renderTrackers()
    }

    override fun onResume() {
        super.onResume()
        refreshSwitches()
    }

    override fun onDestroyView() {
        root = null
        switches.clear()
        super.onDestroyView()
    }

    private fun addSwitchRow(parent: ViewGroup, sw: NetSwitch, title: Int, hint: Int) {
        val row = layoutInflater.inflate(R.layout.item_net_switch, parent, false)
        row.findViewById<TextView>(R.id.netSwitchHint).setText(hint)
        val toggle = row.findViewById<MaterialSwitch>(R.id.netSwitch)
        toggle.setText(title)
        toggle.isEnabled = false // bis der Ist-Zustand gelesen ist
        toggle.setOnClickListener { onToggle(sw, toggle.isChecked) }
        switches[sw] = toggle
        parent.addView(row)
    }

    private fun toast(res: Int) = Toast.makeText(appContext, res, Toast.LENGTH_LONG).show()

    private fun canRun(): Boolean {
        if (backend is NoopBackend || uid < 0) {
            toast(R.string.net_no_shizuku)
            refreshSwitches()
            return false
        }
        return true
    }

    /** Ist-Zustand frisch vom System lesen und anzeigen; nicht lesbar = Schalter gesperrt. */
    private fun refreshSwitches(after: (() -> Unit)? = null) {
        if (backend is NoopBackend || uid < 0) {
            switches.values.forEach { it.isEnabled = false }
            return
        }
        executor.execute {
            val states = net.readAll(packageName, uid)
            handler.post {
                if (!isAdded || root == null) return@post
                states.forEach { (sw, active) ->
                    switches[sw]?.apply {
                        isEnabled = active != null && sw !in locked
                        isChecked = active == true
                    }
                }
                after?.invoke()
            }
        }
    }

    private fun onToggle(sw: NetSwitch, wanted: Boolean) {
        if (!canRun()) return
        if (sw in locked) {
            refreshSwitches()
            return
        }
        if (sw == NetSwitch.OFFLINE && wanted) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.net_offline_confirm_title)
                .setMessage(R.string.net_offline_hint)
                .setPositiveButton(R.string.net_offline_confirm_action) { _, _ -> applySwitch(sw, true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> refreshSwitches() }
                .setOnCancelListener { refreshSwitches() }
                .show()
        } else applySwitch(sw, wanted)
    }

    private fun applySwitch(sw: NetSwitch, wanted: Boolean) {
        switches.values.forEach { it.isEnabled = false }
        executor.execute {
            val ok = net.set(sw, packageName, uid, wanted)
            if (ok) {
                store.mark(packageName, sw, wanted)
                runCatching {
                    de.minipc.appopsshizuku.appops.ChangeLog.recordNet(appContext, packageName, sw, !wanted, wanted)
                }
                // Nur merken, wenn WIR die Firewall-Kette eingeschaltet haben (sonst nichts anfassen).
                if (sw == NetSwitch.OFFLINE && wanted && net.lastSetEnabledChain) store.setChainMarked(true)
            }
            handler.post {
                if (!isAdded) return@post
                if (!ok) toast(R.string.net_error)
                refreshSwitches()
            }
        }
    }

    /** Nimmt nur zurueck, was WIR gesetzt haben (laut [NetStore]); jeder Schritt wird zurueckgelesen. */
    private fun resetAll() {
        if (!canRun()) return
        switches.values.forEach { it.isEnabled = false }
        executor.execute {
            val ours = store.get(packageName)
            var failed = false
            for (sw in ours) {
                if (net.set(sw, packageName, uid, false)) store.mark(packageName, sw, false) else failed = true
            }
            // Firewall-Kette nur abschalten, wenn WIR sie eingeschaltet haben und sie niemand
            // mehr braucht - sonst wuerden fremde Deny-Regeln stillschweigend entschaerft.
            if (store.chainMarked() && store.packagesWithOffline().isEmpty()) {
                if (net.setChain(false)) store.setChainMarked(false) else failed = true
            }
            handler.post {
                if (!isAdded) return@post
                toast(if (failed) R.string.net_error else if (ours.isEmpty()) R.string.net_reset_nothing else R.string.net_reset_done)
                refreshSwitches()
            }
        }
    }

    private fun loadTrackers() {
        executor.execute {
            val found = runCatching {
                val info = appContext.packageManager.getPackageInfoCompat(
                    packageName,
                    PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
                        PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS or
                        PackageManager.GET_META_DATA
                )
                val names = mutableSetOf<String>()
                info.activities?.mapTo(names) { it.name }
                info.receivers?.mapTo(names) { it.name }
                info.providers?.mapTo(names) { it.name }
                info.services?.forEach { s ->
                    names.add(s.name)
                    // Firebase-Bausteine stehen als Registrar in den Meta-Daten des Discovery-Dienstes
                    s.metaData?.keySet()?.filter { it.startsWith(REGISTRAR_PREFIX) }
                        ?.mapTo(names) { it.removePrefix(REGISTRAR_PREFIX) }
                }
                names.toSet()
            }.getOrNull()
            handler.post {
                if (!isAdded) return@post
                foundClasses = found
                renderTrackers()
                if (found != null) startCodeScan()
            }
        }
    }

    /**
     * Zweite Erkennung: Marker in den Dex-Dateien der APK(s). Laeuft im selben Einzel-Executor, also
     * nach dem Manifest-Scan. Die APK-Pfade kommen vom PackageManager (Basis + Splits).
     */
    private fun startCodeScan() {
        if (codeResult != null || codeRunningMb != null) return
        codeRunningMb = 0
        executor.execute {
            val files = runCatching {
                val ai = appContext.packageManager.getApplicationInfo(packageName, 0)
                listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()
            }.getOrDefault(emptyList()).map { File(it) }
            val mb = files.sumOf { it.length() } / (1024 * 1024)
            handler.post {
                codeRunningMb = mb
                if (isAdded) renderTrackers()
            }
            val t0 = SystemClock.elapsedRealtime()
            val result = TrackerCodeScan.scanWithInfo(files, TrackerCatalog.probes) { Log.w(LOG_TAG, it) }
            Log.i(
                LOG_TAG,
                "$packageName: ${SystemClock.elapsedRealtime() - t0} ms, ${files.size} APK ($mb MB), " +
                    "${result.bytesRead / (1024 * 1024)} MB Dex gelesen, gefunden=${result.found}, " +
                    "vollstaendig=${result.complete}"
            )
            // Ergebnis immer merken (auch ohne Ansicht): kein zweiter Scan beim Zurueckkehren.
            handler.post {
                codeResult = result
                codeRunningMb = null
                if (isAdded) renderTrackers()
            }
        }
    }

    private fun renderTrackers() {
        val list = root?.findViewById<ViewGroup>(R.id.netTrackers) ?: return
        list.removeAllViews()
        val found = foundClasses
        if (found == null) {
            list.addView(TextView(requireContext()).apply { setText(R.string.net_diag_unreadable) })
            return
        }
        val manifest = TrackerCatalog.scan(found)
        val code = codeResult
        val running = codeRunningMb != null
        val codeFound = code?.found.orEmpty()
        val status = layoutInflater.inflate(R.layout.item_net_tracker, list, false)
        status.findViewById<TextView>(R.id.trackerName).text = when {
            running -> getString(R.string.net_code_running, codeRunningMb)
            code == null -> ""
            else -> getString(
                R.string.net_code_summary,
                manifest.count { (sdk, hit) -> hit || sdk.name in codeFound },
                manifest.count { (sdk, hit) -> !hit && sdk.name in codeFound }
            ) + when {
                code.truncated -> " " + getString(R.string.net_code_truncated)
                !code.complete -> " " + getString(R.string.net_code_unreadable)
                else -> ""
            }
        }
        list.addView(status)
        val groups = listOf(
            TrackerGroup.ANALYTICS to R.string.net_group_analytics,
            TrackerGroup.ADS to R.string.net_group_ads,
            TrackerGroup.ATTRIBUTION to R.string.net_group_attribution
        )
        for ((group, title) in groups) {
            val header = layoutInflater.inflate(R.layout.item_category_header, list, false) as TextView
            header.setText(title)
            list.addView(header)
            for ((sdk, hit) in manifest.filter { it.first.group == group }) {
                val row = layoutInflater.inflate(R.layout.item_net_tracker, list, false)
                row.findViewById<TextView>(R.id.trackerName).text = sdk.name
                val inCode = sdk.name in codeFound
                row.findViewById<TextView>(R.id.trackerState).setText(
                    when {
                        hit && inCode -> R.string.net_found_both
                        hit -> R.string.net_found_manifest
                        inCode -> R.string.net_found_code
                        running -> R.string.net_code_pending
                        code?.complete == false -> R.string.net_not_found_partial
                        else -> R.string.net_not_found
                    }
                )
                list.addView(row)
            }
        }
    }
}
