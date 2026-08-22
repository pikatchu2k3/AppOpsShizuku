package de.minipc.appopsshizuku

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.stealth.ApkModifier
import de.minipc.appopsshizuku.stealth.BinaryXml
import de.minipc.appopsshizuku.stealth.DeviceProfiles
import de.minipc.appopsshizuku.stealth.DeviceSpoofOptions
import de.minipc.appopsshizuku.stealth.StealthPhase
import de.minipc.appopsshizuku.stealth.StealthStealer
import de.minipc.appopsshizuku.stealth.VaultCalcIntegration
import de.minipc.appopsshizuku.ui.ComponentListItem
import de.minipc.appopsshizuku.ui.ComponentsAdapter
import java.io.File
import java.util.concurrent.Executors

/**
 * Stealth-/Komponenten-Steuerung einer App via pm disable-user (Shizuku/Shell):
 * - App komplett deaktivieren/aktivieren (mit Warn-Dialog; System-Apps + eigene App ausgenommen)
 * - Launcher-Activity umschalten (Icon ausblenden)
 * - Alle Activities auflisten (Toggle + Start via am start)
 * - Receiver auflisten (Toggle; BOOT_COMPLETED als "Autostart" markiert)
 * - AppWidget-Provider auflisten (Toggle)
 */
class ComponentsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
        private const val APPWIDGET_META = "android.appwidget.provider"
        private const val TAG = "ComponentsActivity"
        private const val REQ_NOTIF = 5001
    }

    private lateinit var backend: ShellBackend
    private lateinit var packageName: String
    private lateinit var adapter: ComponentsAdapter

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    // Fortschrittsdialog + Ergebnis-Handling (Patch laeuft im StealthPatchService)
    private var progressDialog: AlertDialog? = null
    private var progressDialogView: View? = null
    private var pendingVault = false
    private var pendingVaultPin: String? = null
    private var pendingClonePkg: String? = null
    private var pendingRestore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_components)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }

        val shizukuOn = runCatching {
            ShizukuBackend().let { it.isAvailable() && it.hasPermission() }
        }.getOrDefault(false)
        backend = if (shizukuOn) ShizukuBackend() else NoopBackend()

        loadAppInfo()

        adapter = ComponentsAdapter()
        findViewById<RecyclerView>(R.id.componentsList).apply {
            layoutManager = LinearLayoutManager(this@ComponentsActivity)
            this.adapter = this@ComponentsActivity.adapter
        }

        setupDisableAppSwitch()
        setupFreezeAppSwitch()
        setupStealth()

        loadComponents()
    }

    /**
     * Verbindet sich mit dem laufenden StealthPatchService:
     * - Fortschritt vom Service -> lokaler Dialog (nur solange sichtbar)
     * - Nach App-Wechsel + Rückkehr: laufenden Patch im Dialog fortsetzen,
     *   fertigen Patch als Erfolgs-/Fehlerdialog anzeigen.
     */
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Listener setzen")
        StealthPatchService.progressListener = { phase, pct ->
            handler.post {
                // Ergebnis zuerst pruefen: Das letzte Signal vom Service kommt
                // NACH lastResult -> Erfolgs-/Fehlerdialog statt Progress-Update
                val res = StealthPatchService.lastResult
                if (res != null && !StealthPatchService.running) {
                    Log.d(TAG, "Listener: Ergebnis verarbeiten: ${res.message}")
                    StealthPatchService.lastResult = null
                    handlePatchResult(res)
                } else {
                    Log.d(TAG, "Listener: update $phase pct=$pct")
                    updateProgressDialog(phase, pct)
                }
            }
        }
        if (StealthPatchService.running) {
            Log.d(TAG, "onResume: Service laeuft, Dialog zeigen")
            showProgressDialog()
            updateProgressDialog(
                StealthPatchService.currentPhase ?: StealthPhase.EXTRACT,
                StealthPatchService.currentPercent
            )
        }
        StealthPatchService.lastResult?.let { res ->
            Log.d(TAG, "onResume: lastResult verarbeiten: ${res.message}")
            StealthPatchService.lastResult = null
            handlePatchResult(res)
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Listener nullen")
        StealthPatchService.progressListener = null
        dismissProgressDialog()
    }

    private fun loadAppInfo() {
        val pm = packageManager
        try {
            val info = pm.getApplicationInfo(packageName, 0)
            findViewById<ImageView>(R.id.appIcon).setImageDrawable(pm.getApplicationIcon(info))
            findViewById<TextView>(R.id.appLabel).text = pm.getApplicationLabel(info)
            findViewById<TextView>(R.id.appPackage).text = packageName
        } catch (e: PackageManager.NameNotFoundException) {
            findViewById<TextView>(R.id.appLabel).text = packageName
        }
    }

    private fun setupDisableAppSwitch() {
        val sw = findViewById<SwitchMaterial>(R.id.appDisableSwitch)
        val appInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
        val isSystem = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isSelf = packageName == applicationContext.packageName
        val subtitle = findViewById<TextView>(R.id.disableAppSubtitle)

        sw.isEnabled = !isSystem && !isSelf
        subtitle.text = when {
            isSelf -> getString(R.string.components_own_app)
            isSystem -> getString(R.string.components_system_app)
            else -> getString(R.string.components_disable_warning)
        }

        // Ist-Zustand: App-Level enabled/disabled via PackageManager (kein Shell-Call)
        // Listener erst NACH dem initialen isChecked setzen (Muster ComponentsAdapter)
        val appEnabled = packageManager.getApplicationEnabledSetting(packageName)
        sw.isChecked =
            appEnabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                appEnabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER

        bindAppDisableSwitchListener()
    }

    /** Switch-Listener anhaengen (nach programmatischen isChecked-Aenderungen neu binden). */
    private fun bindAppDisableSwitchListener() {
        val sw = findViewById<SwitchMaterial>(R.id.appDisableSwitch)
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.components_disable_app)
                    .setMessage(R.string.components_disable_warning)
                    .setNegativeButton(R.string.btn_cancel) { _, _ ->
                        // Programmatisches Zuruecksetzen OHNE Listener-Feuer:
                        // sonst wuerde checked=false ein pm enable ausloesen
                        sw.setOnCheckedChangeListener(null)
                        sw.isChecked = false
                        bindAppDisableSwitchListener()
                    }
                    .setPositiveButton(R.string.btn_disable) { _, _ ->
                        toggleApp(false)
                    }
                    .show()
            } else {
                toggleApp(true)
            }
        }
    }

    /**
     * App-Freeze via pm suspend (Shizuku-Shell, kein Root): App wird sofort
     * beendet und kann nicht gestartet werden, bleibt aber mit Daten installiert.
     * Ist-Zustand ueber PackageManager.isPackageSuspended() — `pm list packages`
     * hat keinen Suspended-Filter (-s listet System-Apps).
     */
    private fun setupFreezeAppSwitch() {
        val sw = findViewById<SwitchMaterial>(R.id.appFreezeSwitch)
        val subtitle = findViewById<TextView>(R.id.freezeAppSubtitle)
        val isSelf = packageName == applicationContext.packageName

        sw.isEnabled = !isSelf
        subtitle.text = if (isSelf) {
            getString(R.string.components_own_app)
        } else {
            getString(R.string.components_freeze_warning)
        }

        val suspended = runCatching { packageManager.isPackageSuspended(packageName) }
            .getOrDefault(false)
        sw.isChecked = suspended

        sw.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.components_freeze_app)
                    .setMessage(R.string.components_freeze_warning)
                    .setNegativeButton(R.string.btn_cancel) { _, _ ->
                        sw.isChecked = false
                    }
                    .setPositiveButton(R.string.btn_freeze) { _, _ ->
                        toggleFreeze(true)
                    }
                    .show()
            } else {
                toggleFreeze(false)
            }
        }
    }

    private fun toggleFreeze(freeze: Boolean) {
        val sw = findViewById<SwitchMaterial>(R.id.appFreezeSwitch)
        executor.execute {
            val res = if (freeze) backend.suspendPackage(packageName)
            else backend.unsuspendPackage(packageName)
            handler.post {
                if (isFailed(res)) {
                    sw.isChecked = !freeze
                    Toast.makeText(this, R.string.error_operation_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(
                            if (freeze) R.string.components_frozen else R.string.components_unfrozen
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshSuspendState()
            }
        }
    }

    /** Switch nach dem Freeze-Wechsel neu synchronisieren (Shell-Ergebnis ist massgeblich). */
    private fun refreshSuspendState() {
        val sw = findViewById<SwitchMaterial>(R.id.appFreezeSwitch)
        sw.isChecked = runCatching { packageManager.isPackageSuspended(packageName) }
            .getOrDefault(false)
    }

    private fun toggleApp(enable: Boolean) {
        val sw = findViewById<SwitchMaterial>(R.id.appDisableSwitch)
        executor.execute {
            val res = if (enable) {
                backend.exec("pm", "enable", "--user", "0", packageName)
            } else {
                backend.exec("pm", "disable-user", "--user", "0", packageName)
            }
            handler.post {
                if (isFailed(res)) {
                    sw.setOnCheckedChangeListener(null)
                    sw.isChecked = enable
                    bindAppDisableSwitchListener()
                    Toast.makeText(this, R.string.error_operation_failed, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(
                            if (enable) R.string.components_enable_app else R.string.components_disable_app
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * pm liefert bei SecurityException exit=0, aber die Exception steht in
     * stderr/stdout — nur exitCode reicht nicht (Android 15/16: Shell darf
     * Komponenten-Zustand nur mit CHANGE_COMPONENT_ENABLED_STATE aendern).
     */
    private fun isFailed(res: de.minipc.appopsshizuku.shizuku.ShellResult): Boolean {
        if (res.exitCode != 0) return true
        val out = res.stderr + "\n" + res.stdout
        return out.contains("Exception", ignoreCase = true) ||
            out.contains("cannot change", ignoreCase = true) ||
            out.contains("Error", ignoreCase = true)
    }

    private fun loadComponents() {
        findViewById<ProgressBar>(R.id.loadingProgress).visibility = View.VISIBLE
        executor.execute {
            val items = buildComponentItems()
            handler.post {
                findViewById<ProgressBar>(R.id.loadingProgress).visibility = View.GONE
                adapter.submitList(items)
            }
        }
    }

    private fun buildComponentItems(): List<ComponentListItem> {
        val pm = packageManager
        val info = try {
            pm.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
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
                if (isFailed(res)) {
                    failed = true
                    lastError = res.stderr + res.stdout
                }
            }
            handler.post {
                if (failed) {
                    Toast.makeText(
                        this,
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
            packageManager.queryBroadcastReceivers(
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
        val name = ComponentName(ci.packageName, ci.name).flattenToShortString()
        val label = ci.name.substringAfterLast('.')
        val parts = mutableListOf<String>()
        if (isLauncher) parts.add(getString(R.string.components_launcher))
        if (!ci.exported) parts.add(getString(R.string.components_not_exported))
        if (isActivity) parts.add(getString(R.string.components_start))
        val subtitle = parts.joinToString(" · ")
        val enabled = when (packageManager.getComponentEnabledSetting(ComponentName(ci.packageName, ci.name))) {
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
                if (isFailed(res)) {
                    Toast.makeText(
                        this,
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
                if (res.exitCode != 0) {
                    Toast.makeText(this, R.string.error_start_activity, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Stealth-Modus: Launcher-Icon per AXML-Surgery entfernen
    // ------------------------------------------------------------------

    private var stealthStealer: StealthStealer? = null
    private var originalApkFile: File? = null

    private fun stealthWorkDir(): File = File(cacheDir, "stealth_$packageName")

    private fun setupStealth() {
        if (backend is NoopBackend) {
            // Ohne Shizuku keine APK-Manipulation
            findViewById<MaterialButton>(R.id.stealthApplyButton).isEnabled = false
            findViewById<MaterialButton>(R.id.stealthRestoreButton).isEnabled = false
            return
        }
        stealthStealer = StealthStealer(backend, applicationContext)
        originalApkFile = File(stealthWorkDir(), "original.apk")
        val restoreBtn = findViewById<MaterialButton>(R.id.stealthRestoreButton)
        restoreBtn.isEnabled = originalApkFile?.exists() == true

        findViewById<MaterialButton>(R.id.stealthApplyButton).setOnClickListener {
            val removeIcon =
                findViewById<SwitchMaterial>(R.id.stealthRemoveLauncherIconSwitch).isChecked
            val vaultCalc =
                findViewById<SwitchMaterial>(R.id.stealthVaultCalcSwitch).isChecked
            stealthApply(removeIcon, vaultCalc)
        }
        restoreBtn.setOnClickListener { stealthRestore() }

        // Vault-Switch: PIN-Feld nur zeigen, wenn Vault aktiv
        val vaultSwitch = findViewById<SwitchMaterial>(R.id.stealthVaultCalcSwitch)
        val pinLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.stealthVaultPinLayout
        )
        vaultSwitch.setOnCheckedChangeListener { _, checked ->
            pinLayout.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Spoof-Switch: Build-Felder nur zeigen, wenn Build-Spoof aktiv
        val spoofBuildSwitch = findViewById<SwitchMaterial>(R.id.stealthSpoofBuildSwitch)
        val spoofBuildFields = findViewById<LinearLayout>(R.id.spoofBuildFields)
        spoofBuildSwitch.setOnCheckedChangeListener { _, checked ->
            spoofBuildFields.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Zufalls-Button: komplette Geräteprofil-Sets füllen
        findViewById<com.google.android.material.button.MaterialButton>(R.id.spoofRandomButton)
            .setOnClickListener { fillRandomProfile() }

        // Clone-Switch: Paketname-Feld nur zeigen, wenn Clone aktiv (Default: an)
        val cloneSwitch = findViewById<SwitchMaterial>(R.id.stealthCloneSwitch)
        val clonePkgLayout = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.stealthClonePkgLayout)
        clonePkgLayout.visibility = if (cloneSwitch.isChecked) View.VISIBLE else View.GONE
        cloneSwitch.setOnCheckedChangeListener { _, checked ->
            clonePkgLayout.visibility = if (checked) View.VISIBLE else View.GONE
        }
    }

    private fun readSpoofOptions(): DeviceSpoofOptions {
        val spoofBuild = findViewById<SwitchMaterial>(R.id.stealthSpoofBuildSwitch).isChecked
        val spoofAndroidId = findViewById<SwitchMaterial>(R.id.stealthSpoofAndroidIdSwitch).isChecked
        val spoofTelephony = findViewById<SwitchMaterial>(R.id.stealthSpoofTelephonySwitch).isChecked
        val modelInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofModelInput)
        val manufacturerInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofManufacturerInput)
        val fingerprintInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofFingerprintInput)
        // Brand/Device/Product aus dem Fingerprint ableiten ("<brand>/<product>/<device>:...")
        // — so bleibt das Set konsistent, wenn der User ein Profil wählt oder editiert.
        val fingerprint = fingerprintInput.text?.toString()?.trim().orEmpty()
        val fpParts = fingerprint.split(":")
        val slashParts = fpParts.firstOrNull()?.split("/").orEmpty()
        return DeviceSpoofOptions(
            fakeModel = modelInput.text?.toString()?.trim().orEmpty(),
            fakeManufacturer = manufacturerInput.text?.toString()?.trim().orEmpty(),
            fakeFingerprint = fingerprint,
            fakeBrand = slashParts.getOrNull(0).orEmpty(),
            fakeDevice = slashParts.getOrNull(2).orEmpty(),
            spoofBuild = spoofBuild,
            spoofAndroidId = spoofAndroidId,
            spoofTelephony = spoofTelephony
        )
    }

    /** Füllt die drei Build-Spoof-Felder mit einem zufälligen Geräteprofil. */
    private fun fillRandomProfile() {
        val profile = DeviceProfiles.random()
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofModelInput)
            .setText(profile.model)
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofManufacturerInput)
            .setText(profile.manufacturer)
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofFingerprintInput)
            .setText(profile.fingerprint)
        Toast.makeText(
            this,
            "Profil: ${profile.model} (${profile.manufacturer})",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stealthApply(removeLauncherIcon: Boolean, vaultCalc: Boolean) {
        val excludeRecents =
            findViewById<SwitchMaterial>(R.id.stealthExcludeFromRecentsSwitch).isChecked
        val secureSnapshot =
            findViewById<SwitchMaterial>(R.id.stealthSecureSnapshotSwitch).isChecked
        val requireVpn =
            findViewById<SwitchMaterial>(R.id.stealthRequireVpnSwitch).isChecked
        val spoofOptions = readSpoofOptions()
        val cloneSwitch = findViewById<SwitchMaterial>(R.id.stealthCloneSwitch).isChecked
        val clonePkgInput = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.stealthClonePkgInput)
        if (!excludeRecents && !removeLauncherIcon && !secureSnapshot && !requireVpn && !vaultCalc && !spoofOptions.anyEnabled && !cloneSwitch) {
            Toast.makeText(this, R.string.stealth_need_launcher, Toast.LENGTH_SHORT).show()
            return
        }
        // Vault-PIN validieren (falls aktiv): nur Ziffern, 4-10 Stellen
        var vaultPin: String? = null
        if (vaultCalc) {
            val pinInput = findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.stealthVaultPinInput
            )
            val pin = pinInput.text?.toString()?.trim().orEmpty()
            if (pin.isEmpty()) {
                // Leer = Default-PIN "12345"
                vaultPin = null
            } else if (!pin.all { it.isDigit() } || pin.length < 4 || pin.length > 10) {
                Toast.makeText(this, R.string.stealth_vault_pin_invalid, Toast.LENGTH_LONG).show()
                return
            } else {
                vaultPin = pin
            }
        }
        // Clone-Paketnamen berechnen (Standard: <original>.stealth)
        // WICHTIG: Android-Paketnamen brauchen mindestens einen Punkt (2 Segmente).
        // Eingabe ohne Punkt ("test") -> "test.stealth" anhängen, sonst schlägt der
        // Installer mit "Installation fehlgeschlagen" fehl (realer User-Fall 08/2026).
        val cloneNewPkg: String? = if (cloneSwitch) {
            val rawInput = clonePkgInput.text?.toString()?.trim().orEmpty()
                .lowercase().replace(Regex("[^a-z0-9._]"), "")
            val base = rawInput.ifEmpty { "$packageName.stealth" }
            if (base.contains('.')) base else "$base.stealth"
        } else null
        // Clone-Label: eigener Name -> sprechender Teil (test.stealth -> "test",
        // "test" ohne Punkt -> "test"); Default -> Original-Label + " (Stealth)"
        // (unterscheidbar, kein doppeltes "FOSS Browser")
        val cloneLabel: String? = if (cloneSwitch) {
            val rawInput = clonePkgInput.text?.toString()?.trim().orEmpty()
            if (rawInput.isNotEmpty()) {
                rawInput.substringBeforeLast('.', rawInput).ifBlank { rawInput }
            } else {
                val origLabel = try {
                    packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
                } catch (e: Exception) { null }
                if (origLabel.isNullOrBlank()) null else "${origLabel} (Stealth)"
            }
        } else null
        // Bestaetigung: Neu-Signierung + ggf. automatische Deinstallation des Originals.
        // App-Bundles (Split-APKs, z.B. Google Maps) bekommen einen Zusatz-Hinweis —
        // der Klon kopiert alle Splits mit (realer User-Fall 08/2026: MISSING_SPLIT).
        val splitWarning = if (hasSplitApks()) getString(R.string.stealth_confirm_split_warning) else null
        val message = buildString {
            append(getString(if (cloneNewPkg != null) R.string.stealth_confirm_message_clone else R.string.stealth_confirm_message))
            if (splitWarning != null) append("\n\n").append(splitWarning)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stealth_confirm_title)
            .setMessage(message)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.stealth_confirm_ok) { _, _ ->
                runStealth(excludeRecents, removeLauncherIcon, secureSnapshot, requireVpn, vaultCalc, vaultPin, spoofOptions, cloneNewPkg, cloneLabel)
            }
            .show()
    }

    /** true, wenn die Ziel-App aus mehreren APKs besteht (App-Bundle, Split-APKs). */
    private fun hasSplitApks(): Boolean = runCatching {
        !packageManager.getApplicationInfo(packageName, 0).splitNames.isNullOrEmpty()
    }.getOrDefault(false)

    private fun runStealth(
        excludeRecents: Boolean,
        removeLauncherIcon: Boolean,
        secureSnapshot: Boolean,
        requireVpn: Boolean,
        vaultCalc: Boolean,
        vaultPin: String?,
        spoofOptions: DeviceSpoofOptions = DeviceSpoofOptions(),
        cloneNewPkg: String? = null,
        cloneLabel: String? = null
    ) {
        if (backend is NoopBackend) {
            Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        // Notification-Berechtigung (Android 13+): OHNE sie ist die
        // Fortschritts-Benachrichtigung im Shade versteckt. Der Schalter in den
        // Einstellungen existiert erst dank Manifest-Eintrag POST_NOTIFICATIONS
        // (realer User-Fall 08/2026) — hier aktiv nachfragen; der Patch laeuft
        // trotzdem weiter (Fortschritt im Dialog).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIF
            )
        }
        // Zustand fuer den Ergebnis-Pfad merken (Vault-Konfiguration nach Erfolg)
        pendingVault = vaultCalc
        pendingVaultPin = vaultPin
        pendingClonePkg = cloneNewPkg
        pendingRestore = false
        // Patch im Foreground-Service starten: laeuft auch weiter, wenn der
        // User zu einer anderen App wechselt (OxygenOS-Freezer, Hintergrund-Cpuset)
        val intent = Intent(this, StealthPatchService::class.java).apply {
            action = StealthPatchService.ACTION_PATCH
            putExtra(StealthPatchService.EXTRA_PACKAGE, packageName)
            putExtra(StealthPatchService.EXTRA_EXCLUDE_RECENTS, excludeRecents)
            putExtra(StealthPatchService.EXTRA_REMOVE_ICON, removeLauncherIcon)
            putExtra(StealthPatchService.EXTRA_SECURE_SNAPSHOT, secureSnapshot)
            putExtra(StealthPatchService.EXTRA_REQUIRE_VPN, requireVpn)
            putExtra(StealthPatchService.EXTRA_VAULT, vaultCalc)
            vaultPin?.let { putExtra(StealthPatchService.EXTRA_VAULT_PIN, it) }
            cloneNewPkg?.let { putExtra(StealthPatchService.EXTRA_CLONE_PKG, it) }
            cloneLabel?.let { putExtra(StealthPatchService.EXTRA_CLONE_LABEL, it) }
            spoofOptions.putInto(this)
        }
        ContextCompat.startForegroundService(this, intent)
        showProgressDialog()
    }

    // ------------------------------------------------------------------
    // Fortschrittsdialog (Progress-Circle + Phasentext + Prozent) + Ergebnis
    // ------------------------------------------------------------------

    private fun showProgressDialog() {
        dismissProgressDialog()
        // Solange der Patch-Dialog sichtbar ist, bleibt die App Vordergrund
        // (top-app cpuset + volle CPU-Takte — auf OxygenOS der zuverlaessigste
        // Boost; Qwen-Empfehlung 08/2026). Screen an, bis der Patch fertig ist.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = layoutInflater.inflate(R.layout.dialog_progress, null)
        // View direkt merken: dialog.findViewById(android.R.id.custom) liefert
        // bei MaterialAlertDialogBuilder NULL (realer Bug 08/2026, E2E)!
        progressDialogView = view
        progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stealth_working)
            .setView(view)
            .setCancelable(false)
            .show()
    }

    private fun updateProgressDialog(phase: StealthPhase, percent: Int?) {
        val dialog = progressDialog
        if (dialog == null) return
        if (!dialog.isShowing) return
        val view = progressDialogView ?: return
        val text = view.findViewById<TextView>(R.id.progressText)
        val pctView = view.findViewById<TextView>(R.id.progressPercent)
        val ring = view.findViewById<CircularProgressIndicator>(R.id.progressRing)
        text.text = StealthPatchService.phaseText(this, phase)
        // GESAMT-Fortschritt (ueber alle Phasen gewichtet) IN den Ring schreiben
        val overall = StealthPatchService.overallPercent(phase, percent)
        if (overall != null) {
            pctView.text = getString(R.string.stealth_progress_percent, overall)
            pctView.visibility = View.VISIBLE
            ring.isIndeterminate = false
            ring.setProgressCompat(overall, true)
        } else {
            // Unbestimmte Phase (z.B. Signieren): Ring dreht, kein % im Kreis
            pctView.text = ""
            pctView.visibility = View.VISIBLE
            ring.isIndeterminate = true
        }
    }

    private fun dismissProgressDialog() {
        progressDialog?.takeIf { it.isShowing }?.dismiss()
        progressDialog = null
        progressDialogView = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Erfolgs-/Fehlerdialog statt Toast (Toast ist zu kurz zum Lesen). */
    private fun showResultDialog(success: Boolean, message: String) {
        val title =
            if (success) R.string.stealth_dialog_success_title else R.string.stealth_dialog_error_title
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }

    private fun handlePatchResult(res: PatchResult) {
        // Progress-Dialog zuerst schliessen, sonst haengt er unter dem
        // Ergebnisdialog sichtbar im Baum (realer Bug 08/2026, E2E).
        dismissProgressDialog()
        if (res.success) {
            markStealth(!pendingRestore)
            findViewById<MaterialButton>(R.id.stealthRestoreButton).isEnabled = !pendingRestore
            // Vault: Ziel-Component + PIN an VaultCalculator übergeben
            if (!pendingRestore && pendingVault) {
                val original = originalApkFile
                if (original != null && original.exists()) {
                    val launcher = BinaryXml.launcherActivityClass(
                        ApkModifier.extractManifest(original)
                    )
                    if (launcher != null) {
                        val vaultPkg = pendingClonePkg ?: packageName
                        val vaultLauncher = if (pendingClonePkg != null) {
                            val oldPkg = BinaryXml.packageName(ApkModifier.extractManifest(original))
                            if (oldPkg != null) launcher.replaceFirst(oldPkg, vaultPkg)
                            else launcher
                        } else launcher
                        VaultCalcIntegration.configureVaultCalculator(
                            this@ComponentsActivity,
                            vaultPkg,
                            vaultLauncher,
                            pendingVaultPin
                        )
                    } else {
                        Toast.makeText(
                            this,
                            R.string.stealth_vault_no_launcher,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            showResultDialog(true, res.message)
        } else {
            showResultDialog(false, res.message)
        }
    }

    /** Merkt sich gestealthte Pakete (Liste zeigt sie weiterhin an). */
    private fun markStealth(stealthy: Boolean) {
        getSharedPreferences("stealth", MODE_PRIVATE)
            .edit().putBoolean(packageName, stealthy).apply()
    }

    private fun stealthRestore() {
        if (backend is NoopBackend) {
            Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        pendingRestore = true
        pendingVault = false
        val intent = Intent(this, StealthPatchService::class.java).apply {
            action = StealthPatchService.ACTION_RESTORE
            putExtra(StealthPatchService.EXTRA_PACKAGE, packageName)
        }
        ContextCompat.startForegroundService(this, intent)
        showProgressDialog()
    }

    private fun reload() {
        executor.execute {
            val items = buildComponentItems()
            handler.post { adapter.submitList(items) }
        }
    }
}
