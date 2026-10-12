package de.minipc.appopsshizuku

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import de.minipc.appopsshizuku.appops.LifecycleEntry
import de.minipc.appopsshizuku.appops.LifecycleManager
import de.minipc.appopsshizuku.appops.LifecycleProfile
import de.minipc.appopsshizuku.appops.LifecycleResult
import de.minipc.appopsshizuku.appops.LifecycleStore
import de.minipc.appopsshizuku.appops.ScreenAction
import de.minipc.appopsshizuku.appops.ScreenOffEffect
import de.minipc.appopsshizuku.appops.ScreenOffExecutor
import de.minipc.appopsshizuku.appops.StepOutcome
import de.minipc.appopsshizuku.appops.describeSnapshot
import de.minipc.appopsshizuku.appops.lifecycleRefusal
import de.minipc.appopsshizuku.appops.screenOffAllowed
import de.minipc.appopsshizuku.appops.shouldClearSnapshot
import de.minipc.appopsshizuku.appops.snapshotToKeep
import de.minipc.appopsshizuku.appops.updateSuspendedByUs
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShellResult
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.shizuku.isFailure
import de.minipc.appopsshizuku.ui.GuardedSwitch
import de.minipc.appopsshizuku.ui.ComponentListItem
import de.minipc.appopsshizuku.ui.ComponentsAdapter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Reiter "Verhalten" der App-Detailseite.
 *
 * Sechs Karten, alle ohne Root (Shizuku-Shell):
 *  1. App deaktivieren      `pm disable-user` / `pm enable`   (nicht fuer die eigene App)
 *  2. App einfrieren        `pm suspend` / `pm unsuspend`
 *  3. App-Daten zuruecksetzen `pm clear`                     (nicht fuer eigene/System-App)
 *  4. Standby-Stufe         `am set-standby-bucket`          (QW-6)
 *  5. Akku-Ausnahme         `cmd deviceidle whitelist +/-`   (QW-6)
 *  6. App optimieren        `cmd package compile -m speed`   (QW-7)
 *  7. Lifecycle-Profil      Akku-Ausnahme -> Standby-Stufe -> RUN_(ANY_)IN_BACKGROUND (Feature 3)
 *  8. Screen-Off-Exit       pm suspend / am force-stop beim Bildschirm-Aus (Feature 3)
 *
 * Ehrlichkeitsregel: nach jeder Aenderung wird der Zustand neu vom Geraet gelesen.
 * Behaelt das Geraet den alten Wert, sagt die App das - statt Erfolg zu behaupten.
 */
class BehaviorFragment : Fragment() {

    companion object {
        const val ARG_PACKAGE = "pkg"

        fun newInstance(packageName: String) = BehaviorFragment().apply {
            arguments = Bundle().apply { putString(ARG_PACKAGE, packageName) }
        }

        // Werte aus ActivityManagerConstants (Standby-Buckets)
        private const val BUCKET_ACTIVE = 10
        private const val BUCKET_WORKING_SET = 20
        private const val BUCKET_FREQUENT = 30
        private const val BUCKET_RARE = 40
        private const val BUCKET_RESTRICTED = 45
        private const val BUCKET_NEVER = 50

        // Komponentenliste (umgezogen aus ComponentsActivity)
        private const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        private const val APPWIDGET_META = "android.appwidget.provider"
        private const val TAG = "BehaviorFragment"

        /** Log-Praefix fuer alles aus Feature 3 (Lifecycle-Profil, Screen-Off-Exit). */
        private const val LIFECYCLE_TAG = "AppOps.Lifecycle"
        private const val REQ_NOTIF = 5002
    }

    private lateinit var packageName: String
    private lateinit var root: View
    private lateinit var disableSw: GuardedSwitch
    private lateinit var freezeSw: GuardedSwitch
    private lateinit var batterySw: GuardedSwitch
    private lateinit var executor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var componentsAdapter: ComponentsAdapter

    private var backend: ShellBackend = NoopBackend()
    private var shizukuUsable = false

    /** Fehler beim letzten Start des Bildschirm-Aus-Dienstes (null = kein Fehler). */
    private var screenOffSyncError: String? = null

    /** Rohmeldung des Geraets zum Standby-Befehl (fuer die ehrliche Diagnose-Zeile). */
    private var standbyRaw: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_behavior, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()
        executor = Executors.newSingleThreadExecutor()

        setupDisableSwitch(view)
        setupFreezeSwitch(view)
        setupClearDataButton(view)
        setupStandby(view)
        setupBattery(view)
        setupCompile(view)
        setupLifecycle(view)
        setupScreenOff(view)

        componentsAdapter = ComponentsAdapter()
        view.findViewById<RecyclerView>(R.id.componentsList).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = componentsAdapter
            isNestedScrollingEnabled = false
        }

        refreshEverything(view)
        loadComponents()
    }

    override fun onResume() {
        super.onResume()
        // Shizuku kann sich zwischenzeitlich verbunden haben -> Backend neu aufloesen
        if (isAdded && this::root.isInitialized && root.parent != null) refreshEverything(root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        executor.shutdown()
    }

    /** Backend wie in den uebrigen Screens: Shizuku nur, wenn verfuegbar UND erlaubt. */
    private fun resolveBackend(): Boolean {
        shizukuUsable = runCatching {
            ShizukuBackend().let { it.isAvailable() && it.hasPermission() }
        }.getOrDefault(false)
        backend = if (shizukuUsable) ShizukuBackend() else NoopBackend()
        return shizukuUsable
    }

    private fun refreshEverything(view: View) {
        resolveBackend()
        // Neue Funktionen brauchen Shizuku; ohne Berechtigung erklaeren und sperren.
        val note = view.findViewById<TextView>(R.id.behaviorShizukuNote)
        note.visibility = if (shizukuUsable) View.GONE else View.VISIBLE
        view.findViewById<MaterialButton>(R.id.standbyActiveButton).isEnabled = shizukuUsable
        view.findViewById<MaterialButton>(R.id.standbyFrequentButton).isEnabled = shizukuUsable
        view.findViewById<MaterialButton>(R.id.standbyRareButton).isEnabled = shizukuUsable
        view.findViewById<MaterialButton>(R.id.standbyRestrictedButton).isEnabled = shizukuUsable
        view.findViewById<SwitchMaterial>(R.id.batterySwitch).isEnabled = shizukuUsable
        view.findViewById<MaterialButton>(R.id.compileButton).isEnabled = shizukuUsable

        refreshEnabledState()
        refreshSuspendState()
        refreshLifecycle()
        refreshScreenOff()
        if (shizukuUsable) {
            loadStandbyState()
            loadBatteryState()
        } else {
            view.findViewById<TextView>(R.id.standbyState).setText(R.string.behavior_value_unknown)
            view.findViewById<TextView>(R.id.batteryState).setText(R.string.behavior_value_unknown)
        }
    }

    // ---------------------------------------------------------------- Karte 1: deaktivieren

    private fun setupDisableSwitch(view: View) {
        val sw = GuardedSwitch(view.findViewById(R.id.appDisableSwitch)).also { disableSw = it }
        val subtitle = view.findViewById<TextView>(R.id.disableAppSubtitle)
        val isSelf = packageName == requireContext().packageName
        val isSystem = runCatching {
            (requireContext().packageManager.getApplicationInfo(packageName, 0).flags and
                ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)

        sw.view.isEnabled = !isSelf
        subtitle.text = when {
            isSelf -> getString(R.string.components_disable_own)
            isSystem -> getString(R.string.components_disable_system)
            else -> getString(R.string.components_disable_warning)
        }

        sw.onUserToggle { checked ->
            if (checked) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.components_disable_app)
                    .setMessage(R.string.components_disable_warning)
                    .setNegativeButton(R.string.btn_cancel) { _, _ -> sw.setQuietly(false) }
                    .setOnCancelListener { sw.setQuietly(false) }
                    .setPositiveButton(R.string.components_disable_action) { _, _ -> toggleEnabled(false) }
                    .show()
            } else {
                toggleEnabled(true)
            }
        }
    }

    private fun refreshEnabledState() {
        val state = runCatching {
            requireContext().packageManager.getApplicationEnabledSetting(packageName)
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        disableSw.setQuietly(state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
    }

    private fun toggleEnabled(enable: Boolean) {
        executor.execute {
            val res = if (enable) backend.exec("pm", "enable", "--user", "0", packageName)
            else backend.exec("pm", "disable-user", "--user", "0", packageName)
            handler.post {
                if (res.isFailure()) {
                    Toast.makeText(requireContext(), R.string.error_operation_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(if (enable) R.string.components_enabled else R.string.components_disabled),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshEnabledState()
            }
        }
    }

    // ---------------------------------------------------------------- Karte 2: einfrieren

    private fun setupFreezeSwitch(view: View) {
        val sw = GuardedSwitch(view.findViewById(R.id.appFreezeSwitch)).also { freezeSw = it }
        val subtitle = view.findViewById<TextView>(R.id.freezeAppSubtitle)
        val isSelf = packageName == requireContext().packageName

        sw.view.isEnabled = !isSelf
        subtitle.text = if (isSelf) {
            getString(R.string.components_own_app)
        } else {
            getString(R.string.components_freeze_warning)
        }

        sw.onUserToggle { checked ->
            if (checked) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.components_freeze_app)
                    .setMessage(R.string.components_freeze_warning)
                    .setNegativeButton(R.string.btn_cancel) { _, _ -> sw.setQuietly(false) }
                    .setOnCancelListener { sw.setQuietly(false) }
                    .setPositiveButton(R.string.btn_freeze) { _, _ -> toggleFreeze(true) }
                    .show()
            } else {
                toggleFreeze(false)
            }
        }
    }

    private fun toggleFreeze(freeze: Boolean) {
        executor.execute {
            val res = if (freeze) backend.suspendPackage(packageName) else backend.unsuspendPackage(packageName)
            handler.post {
                if (res.isFailure()) {
                    freezeSw.setQuietly(!freeze)
                    Toast.makeText(requireContext(), R.string.error_operation_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(if (freeze) R.string.components_frozen else R.string.components_unfrozen),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshSuspendState()
            }
        }
    }

    private fun refreshSuspendState() {
        freezeSw.setQuietly(
            runCatching { requireContext().packageManager.isPackageSuspended(packageName) }
                .getOrDefault(false)
        )
    }

    // ------------------------------------------------------- Karte 3: Daten zuruecksetzen

    private fun setupClearDataButton(view: View) {
        val button = view.findViewById<MaterialButton>(R.id.clearDataButton)
        val subtitle = view.findViewById<TextView>(R.id.clearDataSubtitle)
        val isSelf = packageName == requireContext().packageName
        val isSystem = runCatching {
            (requireContext().packageManager.getApplicationInfo(packageName, 0).flags and
                ApplicationInfo.FLAG_SYSTEM) != 0
        }.getOrDefault(false)

        if (isSelf || isSystem) {
            button.isEnabled = false
            subtitle.text = getString(
                if (isSystem) R.string.components_clear_data_system else R.string.components_clear_data_own
            )
            return
        }

        button.setOnClickListener {
            val input = EditText(requireContext())
            input.inputType = InputType.TYPE_CLASS_TEXT
            input.hint = packageName
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.components_clear_data_confirm_title)
                .setMessage(R.string.components_clear_data_confirm_msg)
                .setView(input)
                .setNegativeButton(R.string.btn_cancel) { _, _ -> }
                .setPositiveButton(R.string.components_clear_data_action) { _, _ ->
                    if (input.text?.toString()?.trim() != packageName) {
                        Toast.makeText(requireContext(), R.string.error_operation_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        executor.execute {
                            val res = backend.exec("pm", "clear", packageName)
                            handler.post {
                                Toast.makeText(
                                    requireContext(),
                                    getString(
                                        if (res.isFailure()) R.string.error_operation_failed
                                        else R.string.components_clear_data_done
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                .show()
        }
    }

    // ------------------------------------------- Karte 4: Standby-Stufe (QW-6)

    private fun setupStandby(view: View) {
        view.findViewById<MaterialButton>(R.id.standbyActiveButton)
            .setOnClickListener { setStandbyBucket("active", BUCKET_ACTIVE) }
        view.findViewById<MaterialButton>(R.id.standbyFrequentButton)
            .setOnClickListener { setStandbyBucket("frequent", BUCKET_FREQUENT) }
        view.findViewById<MaterialButton>(R.id.standbyRareButton)
            .setOnClickListener { setStandbyBucket("rare", BUCKET_RARE) }
        view.findViewById<MaterialButton>(R.id.standbyRestrictedButton)
            .setOnClickListener { setStandbyBucket("restricted", BUCKET_RESTRICTED) }
    }

    private fun loadStandbyState() {
        val label = root.findViewById<TextView>(R.id.standbyState)
        executor.execute {
            val res = backend.exec("cmd", "activity", "get-standby-bucket", packageName)
            val bucket = res.stdout.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()
            standbyRaw = if (bucket == null) rawOf(res) else ""
            handler.post {
                if (!isAdded) return@post
                label.text = standbyText(bucket)
            }
        }
    }

    /** Zustandstext inkl. Rohmeldung des Geraets, wenn der Wert nicht lesbar war. */
    private fun standbyText(bucket: Int?): String {
        val head = getString(R.string.behavior_standby_current, bucketLabel(bucket, bucket))
        return if (bucket != null && standbyRaw.isBlank()) head
        else head + "\n" + getString(R.string.behavior_device_says, standbyRaw.ifBlank { "-" }.take(160))
    }

    private fun rawOf(res: ShellResult): String =
        (res.stdout.trim() + " " + res.stderr.trim()).replace(Regex("\\s+"), " ").trim()

    private fun setStandbyBucket(bucketName: String, expected: Int) {
        executor.execute {
            val setRes = backend.exec("am", "set-standby-bucket", packageName, bucketName)
            // Ehrlichkeit: den Wert danach frisch vom Geraet lesen, nicht annehmen
            val res = backend.exec("cmd", "activity", "get-standby-bucket", packageName)
            val now = res.stdout.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()
            standbyRaw = if (now == expected) "" else "set: ${rawOf(setRes)} | ist: ${rawOf(res)}"
            handler.post {
                loadStandbyState()
                val ok = now == expected
                Toast.makeText(
                    requireContext(),
                    getString(
                        if (ok) R.string.behavior_standby_done else R.string.behavior_standby_failed,
                        bucketLabel(now, now)
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Bucket-Nummer -> Text. Unbekannte Werte werden mit Nummer gezeigt, nicht geraten. */
    private fun bucketLabel(bucket: Int?, raw: Int?): String = when (bucket) {
        BUCKET_ACTIVE -> getString(R.string.behavior_bucket_active)
        BUCKET_WORKING_SET -> getString(R.string.behavior_bucket_working_set)
        BUCKET_FREQUENT -> getString(R.string.behavior_bucket_frequent)
        BUCKET_RARE -> getString(R.string.behavior_bucket_rare)
        BUCKET_RESTRICTED -> getString(R.string.behavior_bucket_restricted)
        BUCKET_NEVER -> getString(R.string.behavior_bucket_never)
        5 -> getString(R.string.behavior_bucket_exempted)
        else -> if (raw == null) getString(R.string.behavior_value_unknown) else "Stufe $raw"
    }

    // ------------------------------------------- Karte 5: Akku-Ausnahme (QW-6)

    private fun setupBattery(view: View) {
        val sw = GuardedSwitch(view.findViewById(R.id.batterySwitch)).also { batterySw = it }
        sw.onUserToggle { checked ->
            executor.execute {
                val res = backend.exec("cmd", "deviceidle", "whitelist", (if (checked) "+" else "-") + packageName)
                val after = isWhitelisted()
                handler.post {
                    if (res.isFailure() || after != checked) {
                        sw.setQuietly(after)
                        Toast.makeText(requireContext(), R.string.behavior_battery_failed, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(
                                if (checked) R.string.behavior_battery_added
                                else R.string.behavior_battery_removed
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    loadBatteryState()
                }
            }
        }
    }

    /** Liest die Ausnahmeliste des Geraets (Zeilen: "com.pkg,uid" oder "system-excidle,com.pkg,uid"). */
    private fun isWhitelisted(): Boolean {
        val res = backend.exec("dumpsys", "deviceidle", "whitelist")
        return whitelistContains(res.stdout, packageName)
    }

    private fun loadBatteryState() {
        val label = root.findViewById<TextView>(R.id.batteryState)
        executor.execute {
            val res = backend.exec("dumpsys", "deviceidle", "whitelist")
            val listed = whitelistContains(res.stdout, packageName)
            handler.post {
                if (!isAdded) return@post
                batterySw.setQuietly(listed)
                label.text = getString(
                    if (listed) R.string.behavior_battery_current_on else R.string.behavior_battery_current_off
                )
            }
        }
    }

    // ------------------------------------------- Karte 6: App optimieren (QW-7)

    private fun setupCompile(view: View) {
        val button = view.findViewById<MaterialButton>(R.id.compileButton)
        button.setOnClickListener {
            button.isEnabled = false
            button.setText(R.string.behavior_aot_running)
            executor.execute {
                // -m speed: auf Geschwindigkeit neu kompilieren, -f erzwingt auch ohne Aenderung
                val res = backend.execTimeout(180, "cmd", "package", "compile", "-m", "speed", "-f", packageName)
                handler.post {
                    button.isEnabled = shizukuUsable
                    button.setText(R.string.behavior_aot_button)
                    val out = (res.stdout + " " + res.stderr).trim()
                    val ok = out.contains("Success", ignoreCase = true) ||
                        out.contains("Nothing to do", ignoreCase = true)
                    Toast.makeText(
                        requireContext(),
                        getString(if (ok) R.string.behavior_aot_done else R.string.behavior_aot_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ------------------------------------------- Karte 7: Lifecycle-Profil (Feature 3)

    private fun isSystemApp(): Boolean = runCatching {
        (requireContext().packageManager.getApplicationInfo(packageName, 0).flags and
            ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    private fun bucketButtonId(bucket: Int): Int = when (bucket) {
        BUCKET_WORKING_SET -> R.id.lifecycleBucketWorkingSet
        BUCKET_FREQUENT -> R.id.lifecycleBucketFrequent
        else -> R.id.lifecycleBucketActive
    }

    private fun bucketOfButton(id: Int): Int = when (id) {
        R.id.lifecycleBucketWorkingSet -> BUCKET_WORKING_SET
        R.id.lifecycleBucketFrequent -> BUCKET_FREQUENT
        else -> BUCKET_ACTIVE
    }

    private fun setupLifecycle(view: View) {
        val profile = LifecycleStore(requireContext()).get(packageName).profile()
        view.findViewById<MaterialButtonToggleGroup>(R.id.lifecycleBucketGroup)
            .check(bucketButtonId(profile.standbyBucket))
        view.findViewById<MaterialCheckBox>(R.id.lifecycleBatteryCheck).isChecked = profile.batteryExempt
        view.findViewById<MaterialCheckBox>(R.id.lifecycleCompileCheck).isChecked = profile.compile
        view.findViewById<MaterialButton>(R.id.lifecycleApplyButton).setOnClickListener { applyLifecycle() }
        view.findViewById<MaterialButton>(R.id.lifecycleRevertButton).setOnClickListener { revertLifecycle() }
    }

    /** Buttons + Zeile "gemerkter Ursprungszustand" aus dem Speicher aktualisieren. */
    private fun refreshLifecycle() {
        val before = LifecycleStore(requireContext()).get(packageName).before
        val refusal = lifecycleRefusal(packageName, requireContext().packageName, isSystemApp(), shizukuUsable)
        root.findViewById<MaterialButton>(R.id.lifecycleApplyButton).isEnabled = refusal == null
        root.findViewById<MaterialButton>(R.id.lifecycleRevertButton).isEnabled = refusal == null && before != null
        root.findViewById<TextView>(R.id.lifecycleSnapshot).text = when {
            refusal != null -> refusal
            before != null -> getString(R.string.lifecycle_saved_before, describeSnapshot(before))
            else -> getString(R.string.lifecycle_no_snapshot)
        }
    }

    private fun applyLifecycle() {
        val profile = LifecycleProfile(
            standbyBucket = bucketOfButton(
                root.findViewById<MaterialButtonToggleGroup>(R.id.lifecycleBucketGroup).checkedButtonId
            ),
            batteryExempt = root.findViewById<MaterialCheckBox>(R.id.lifecycleBatteryCheck).isChecked,
            compile = root.findViewById<MaterialCheckBox>(R.id.lifecycleCompileCheck).isChecked
        )
        val ctx = requireContext().applicationContext
        val isSystem = isSystemApp()
        setLifecycleBusy(true)
        executor.execute {
            val result = LifecycleManager(backend, ctx.packageName).apply(packageName, profile, isSystem)
            LifecycleStore(ctx).update(packageName) {
                it.copy(
                    standbyBucket = profile.standbyBucket,
                    batteryExempt = profile.batteryExempt,
                    compile = profile.compile,
                    // Ursprungszustand nur beim ERSTEN Anwenden merken
                    before = snapshotToKeep(it.before, result.before)
                )
            }
            logLifecycle("anwenden", result)
            handler.post {
                if (!isAdded) return@post
                setLifecycleBusy(false)
                showLifecycleResult(result, revert = false)
                loadStandbyState()
                loadBatteryState()
            }
        }
    }

    private fun revertLifecycle() {
        val ctx = requireContext().applicationContext
        val before = LifecycleStore(ctx).get(packageName).before ?: return
        val isSystem = isSystemApp()
        setLifecycleBusy(true)
        executor.execute {
            val result = LifecycleManager(backend, ctx.packageName).revert(packageName, before, isSystem)
            if (shouldClearSnapshot(result)) {
                LifecycleStore(ctx).update(packageName) { it.copy(before = null) }
            }
            logLifecycle("zuruecknehmen", result)
            handler.post {
                if (!isAdded) return@post
                setLifecycleBusy(false)
                showLifecycleResult(result, revert = true)
                loadStandbyState()
                loadBatteryState()
            }
        }
    }

    private fun setLifecycleBusy(busy: Boolean) {
        val apply = root.findViewById<MaterialButton>(R.id.lifecycleApplyButton)
        apply.setText(if (busy) R.string.lifecycle_running else R.string.lifecycle_apply)
        if (busy) {
            apply.isEnabled = false
            root.findViewById<MaterialButton>(R.id.lifecycleRevertButton).isEnabled = false
        } else {
            refreshLifecycle()
        }
    }

    /** Jeder Schritt einzeln, mit Wunsch, Istwert und Rohmeldung - nie ein Sammel-"Erfolg". */
    private fun showLifecycleResult(result: LifecycleResult, revert: Boolean) {
        val label = root.findViewById<TextView>(R.id.lifecycleResult)
        label.visibility = View.VISIBLE
        val refused = result.refused
        if (refused != null) {
            label.text = refused
            return
        }
        val checked = result.outcomes.count { it.status != StepOutcome.Status.SKIPPED }
        val head = if (result.failures.isEmpty()) getString(R.string.lifecycle_result_ok, checked)
        else getString(R.string.lifecycle_result_failed, result.failures.size, checked)
        val lines = result.outcomes.joinToString("\n") { it.describe() }
        val tail = if (revert && !shouldClearSnapshot(result)) "\n" + getString(R.string.lifecycle_revert_kept) else ""
        label.text = "$head\n$lines$tail"
    }

    private fun logLifecycle(what: String, result: LifecycleResult) {
        result.refused?.let { Log.i(LIFECYCLE_TAG, "$packageName $what abgelehnt: $it") }
        result.outcomes.forEach { Log.i(LIFECYCLE_TAG, "$packageName $what: ${it.describe()}") }
    }

    // ------------------------------------------- Karte 8: Screen-Off-Exit (Feature 3)

    private fun setupScreenOff(view: View) {
        val entry = LifecycleStore(requireContext()).get(packageName)
        val sw = view.findViewById<SwitchMaterial>(R.id.screenOffSwitch)
        val group = view.findViewById<RadioGroup>(R.id.screenOffEffectGroup)
        val release = view.findViewById<MaterialCheckBox>(R.id.screenOffReleaseCheck)

        // Erst Zustand setzen, dann Listener - sonst loest das Laden schon Aktionen aus.
        sw.isChecked = entry.screenOffExit == true
        group.check(
            if (entry.screenOffEffect == ScreenOffEffect.FORCE_STOP) R.id.screenOffEffectForceStop
            else R.id.screenOffEffectSuspend
        )
        release.isChecked = entry.releaseOnScreenOn ?: true

        sw.setOnCheckedChangeListener { _, checked -> onScreenOffToggled(checked) }
        group.setOnCheckedChangeListener { _, id ->
            saveScreenOff {
                it.copy(
                    screenOffEffect = if (id == R.id.screenOffEffectForceStop) ScreenOffEffect.FORCE_STOP
                    else ScreenOffEffect.SUSPEND
                )
            }
            refreshScreenOff()
        }
        release.setOnCheckedChangeListener { _, checked -> saveScreenOff { it.copy(releaseOnScreenOn = checked) } }
    }

    private fun saveScreenOff(change: (LifecycleEntry) -> LifecycleEntry) =
        LifecycleStore(requireContext()).update(packageName, change)

    private fun onScreenOffToggled(checked: Boolean) {
        val sw = root.findViewById<SwitchMaterial>(R.id.screenOffSwitch)
        if (checked && !shizukuUsable) {
            // Ehrlich: ohne Shizuku kann der Dienst nichts anhalten -> gar nicht erst einschalten
            sw.isChecked = false
            Toast.makeText(requireContext(), R.string.screen_off_no_shizuku, Toast.LENGTH_LONG).show()
            return
        }
        val effect = if (root.findViewById<RadioGroup>(R.id.screenOffEffectGroup).checkedRadioButtonId ==
            R.id.screenOffEffectForceStop
        ) ScreenOffEffect.FORCE_STOP else ScreenOffEffect.SUSPEND
        saveScreenOff {
            it.copy(
                screenOffExit = checked,
                screenOffEffect = effect,
                releaseOnScreenOn = root.findViewById<MaterialCheckBox>(R.id.screenOffReleaseCheck).isChecked
            )
        }
        Log.i(LIFECYCLE_TAG, "$packageName Screen-Off-Exit ${if (checked) "an" else "aus"} ($effect)")
        if (checked && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(), arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
            )
        }
        if (!checked) releaseIfSuspendedByUs()
        screenOffSyncError = LifecycleScreenService.sync(requireContext())
        refreshScreenOff()
    }

    /** Haken aus, waehrend WIR die App eingefroren haben -> sofort freigeben und pruefen. */
    private fun releaseIfSuspendedByUs() {
        val ctx = requireContext().applicationContext
        executor.execute {
            val store = LifecycleStore(ctx)
            if (packageName !in store.suspendedByUs) return@execute
            val results = ScreenOffExecutor(backend)
                .run(listOf(ScreenAction(packageName, ScreenAction.Kind.UNSUSPEND)))
            store.suspendedByUs = updateSuspendedByUs(store.suspendedByUs, results)
            results.forEach { Log.i(LIFECYCLE_TAG, "Haken aus: ${it.describe()}") }
            handler.post {
                if (!isAdded) return@post
                refreshSuspendState()
                if (results.any { !it.ok }) {
                    Toast.makeText(requireContext(), results.first().describe(), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshScreenOff() {
        val ctx = requireContext()
        val allowed = screenOffAllowed(packageName, ctx.packageName, isSystemApp())
        val sw = root.findViewById<SwitchMaterial>(R.id.screenOffSwitch)
        val on = sw.isChecked
        // Ausschalten muss immer moeglich bleiben, Einschalten nur mit Shizuku
        sw.isEnabled = allowed && (shizukuUsable || on)
        val suspend = root.findViewById<RadioGroup>(R.id.screenOffEffectGroup).checkedRadioButtonId ==
            R.id.screenOffEffectSuspend
        root.findViewById<RadioGroup>(R.id.screenOffEffectGroup).let { g ->
            for (i in 0 until g.childCount) g.getChildAt(i).isEnabled = allowed
        }
        root.findViewById<MaterialCheckBox>(R.id.screenOffReleaseCheck).isEnabled = allowed && suspend

        val lines = mutableListOf<String>()
        when {
            packageName == ctx.packageName -> lines.add(getString(R.string.screen_off_excluded_own))
            !allowed -> lines.add(getString(R.string.screen_off_excluded_system))
            !shizukuUsable -> lines.add(getString(R.string.screen_off_no_shizuku))
        }
        if (allowed && on) {
            val err = screenOffSyncError
            lines.add(
                if (err != null) getString(R.string.screen_off_service_failed, err)
                else getString(R.string.screen_off_service_running)
            )
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                lines.add(getString(R.string.screen_off_notif_denied))
            }
        }
        LifecycleStore(ctx).lastReport.takeIf { it.isNotBlank() }
            ?.let { lines.add(getString(R.string.screen_off_last, it)) }
        root.findViewById<TextView>(R.id.screenOffState).text = lines.joinToString("\n")
    }

    // ---------------------------------------------- Komponenten (aus ComponentsActivity)

    private fun loadComponents() {
        root.findViewById<ProgressBar>(R.id.loadingProgress).visibility = View.VISIBLE
        executor.execute {
            val items = buildComponentItems()
            handler.post {
                if (!isAdded) return@post
                root.findViewById<ProgressBar>(R.id.loadingProgress).visibility = View.GONE
                componentsAdapter.submitList(items)
            }
        }
    }

    private fun buildComponentItems(): List<ComponentListItem> {
        val pm = requireContext().packageManager
        val info = try {
            // MATCH_DISABLED_COMPONENTS: bereits deaktivierte Komponenten muessen in der Liste bleiben
            pm.getPackageInfoCompat(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
                    PackageManager.MATCH_DISABLED_COMPONENTS
            )
        } catch (e: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        val items = mutableListOf<ComponentListItem>()

        // Launcher-Activities (Icon ausblenden)
        val launcher = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).filter { it.activityInfo.packageName == packageName }.map { it.activityInfo }
        if (launcher.isNotEmpty()) {
            items.add(ComponentListItem.SectionHeader(getString(R.string.components_launcher)))
            val section = launcher.map { act ->
                toItem(act, isActivity = true, isLauncher = true)
            }
            items.add(sectionActions(R.string.components_launcher, section))
            items.addAll(section)
        }

        // Weitere Activities
        val launcherNames = launcher.map { it.name }.toSet()
        val others = info.activities?.toList()?.filter { it.name !in launcherNames }.orEmpty()
        if (others.isNotEmpty()) {
            items.add(ComponentListItem.SectionHeader(getString(R.string.components_activities)))
            val section = others.map { act ->
                toItem(act, isActivity = true)
            }
            items.add(sectionActions(R.string.components_activities, section))
            items.addAll(section)
        }

        // Receiver (Autostart-Markierung)
        val receivers = info.receivers?.toList().orEmpty()
        if (receivers.isNotEmpty()) {
            items.add(ComponentListItem.SectionHeader(getString(R.string.components_receivers)))
            val bootReceivers = findBootReceivers()
            val section = receivers.map { rc ->
                toItem(rc, isActivity = false, hasBoot = rc.name in bootReceivers)
            }
            items.add(sectionActions(R.string.components_receivers, section))
            items.addAll(section)
        }

        // AppWidget-Provider
        val widgetProviders = info.providers?.toList()
            ?.filter { it.metaData?.containsKey(APPWIDGET_META) == true }
            .orEmpty()
        if (widgetProviders.isNotEmpty()) {
            items.add(ComponentListItem.SectionHeader(getString(R.string.components_widgets)))
            val section = widgetProviders.map { pr ->
                toItem(pr, isActivity = false)
            }
            items.add(sectionActions(R.string.components_widgets, section))
            items.addAll(section)
        }
        return items
    }

    /** „Alle aktivieren/deaktivieren"-Zeile unter einem Sektions-Header. */
    private fun sectionActions(
        sectionTitle: Int,
        section: List<ComponentListItem.ComponentItem>
    ): ComponentListItem.SectionActions = ComponentListItem.SectionActions(
        sectionId = getString(sectionTitle),
        components = section.map { it.componentName },
        onToggleAll = ::toggleComponents
    )

    /** Aktiviert/deaktiviert alle Komponenten einer Sektion nacheinander. */
    private fun toggleComponents(components: List<String>, enable: Boolean) {
        if (components.isEmpty()) return
        executor.execute {
            var failed = false
            var lastError = ""
            for (component in components) {
                val res = if (enable) {
                    backend.exec("pm", "enable", "--user", "0", component)
                } else {
                    backend.exec("pm", "disable-user", "--user", "0", component)
                }
                if (res.isFailure()) {
                    failed = true
                    lastError = res.stderr + res.stdout
                }
            }
            handler.post {
                if (!isAdded) return@post
                if (failed) {
                    Toast.makeText(
                        requireContext(),
                        if (backend is NoopBackend) R.string.error_operation_failed
                        else R.string.components_no_component_permission,
                        Toast.LENGTH_LONG
                    ).show()
                    Log.w(TAG, "toggleComponents($enable) Fehler: $lastError")
                }
                reload()
            }
        }
    }

    private fun findBootReceivers(): Set<String> {
        return try {
            requireContext().packageManager.queryBroadcastReceivers(
                Intent(BOOT_COMPLETED).setPackage(packageName), 0
            ).map { it.activityInfo.name }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun toItem(
        ci: ComponentInfo,
        isActivity: Boolean,
        isLauncher: Boolean = false,
        hasBoot: Boolean = false
    ): ComponentListItem.ComponentItem {
        val pm = requireContext().packageManager
        val name = ComponentName(ci.packageName, ci.name).flattenToShortString()
        val label = ci.name.substringAfterLast('.')
        val parts = mutableListOf<String>()
        if (isLauncher) parts.add(getString(R.string.components_launcher))
        if (!ci.exported) parts.add(getString(R.string.components_not_exported))
        if (isActivity) parts.add(getString(R.string.components_start))
        val subtitle = parts.joinToString(" · ")
        val enabled = when (pm.getComponentEnabledSetting(ComponentName(ci.packageName, ci.name))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> ci.enabled // DEFAULT -> Manifest-Zustand
        }
        return ComponentListItem.ComponentItem(
            componentName = name,
            label = label,
            subtitle = subtitle,
            enabled = enabled,
            isActivity = isActivity,
            hasBootReceiver = hasBoot,
            isExported = ci.exported,
            onToggle = ::toggleComponent,
            onStart = ::startComponent
        )
    }

    private fun toggleComponent(component: String, enable: Boolean) {
        executor.execute {
            val res = if (enable) {
                backend.exec("pm", "enable", "--user", "0", component)
            } else {
                backend.exec("pm", "disable-user", "--user", "0", component)
            }
            handler.post {
                if (!isAdded) return@post
                if (res.isFailure()) {
                    Toast.makeText(
                        requireContext(),
                        if (backend is NoopBackend) R.string.error_operation_failed
                        else R.string.components_no_component_permission,
                        Toast.LENGTH_LONG
                    ).show()
                }
                reload()
            }
        }
    }

    private fun startComponent(component: String) {
        executor.execute {
            // 0x10800000 = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            // -> Task erscheint NICHT im Verlauf (Stealth-Start)
            val res = backend.exec("am", "start", "-n", component, "-f", "0x10800000")
            handler.post {
                if (!isAdded) return@post
                if (res.exitCode != 0) {
                    Toast.makeText(requireContext(), R.string.error_start_activity, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun reload() {
        executor.execute {
            val items = buildComponentItems()
            handler.post {
                if (!isAdded) return@post
                componentsAdapter.submitList(items)
            }
        }
    }
}

/**
 * Prueft, ob ein Paket in der Ausnahmeliste von `dumpsys deviceidle whitelist` steht.
 * Herausgezogen (top-level, ohne Android-Abhaengigkeit), damit es unit-testbar ist.
 */
fun whitelistContains(dump: String, packageName: String): Boolean =
    dump.lineSequence().any { line ->
        val parts = line.trim().split(',')
        parts.any { it.trim() == packageName }
    }
