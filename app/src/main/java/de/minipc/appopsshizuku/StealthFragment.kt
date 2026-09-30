package de.minipc.appopsshizuku

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import android.content.Intent
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.stealth.ApkModifier
import de.minipc.appopsshizuku.stealth.BinaryXml
import de.minipc.appopsshizuku.stealth.DeviceProfiles
import de.minipc.appopsshizuku.stealth.DeviceSpoofOptions
import de.minipc.appopsshizuku.stealth.StealthPhase
import de.minipc.appopsshizuku.stealth.StealthStealer
import de.minipc.appopsshizuku.stealth.VaultIntegration
import java.io.File

/**
 * Reiter "Tarnung" der App-Detailseite (umgezogen aus ComponentsActivity):
 * Klonen/Stealth-Patch mit allen Optionen (Recents, Icon entfernen, Secure Snapshot,
 * VPN-Guard, Vault, Label, Spoofing/Geraeteprofil), Apply und Restore.
 *
 * Der eigentliche Patch laeuft weiterhin im [StealthPatchService] (Foreground-Service);
 * dieses Fragment ist nur der Host der Fortschrittsanzeige (Companion-Kanal).
 */
class StealthFragment : Fragment() {

    companion object {
        const val ARG_PACKAGE = "pkg"
        private const val TAG = "StealthFragment"
        private const val REQ_NOTIF = 5001

        fun newInstance(packageName: String) = StealthFragment().apply {
            arguments = Bundle().apply { putString(ARG_PACKAGE, packageName) }
        }
    }

    private lateinit var packageName: String
    private lateinit var root: View
    private val handler = Handler(Looper.getMainLooper())

    private var backend: ShellBackend = NoopBackend()

    private var progressDialog: AlertDialog? = null
    private var progressDialogView: View? = null
    private var pendingVault = false
    private var pendingVaultPin: String? = null
    private var pendingClonePkg: String? = null
    private var pendingRestore = false

    private var stealthStealer: StealthStealer? = null
    private var originalApkFile: File? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_stealth, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        packageName = requireArguments().getString(ARG_PACKAGE).orEmpty()

        val shizukuOn = runCatching {
            ShizukuBackend().let { it.isAvailable() && it.hasPermission() }
        }.getOrDefault(false)
        backend = if (shizukuOn) ShizukuBackend() else NoopBackend()

        setupStealth()
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

    // ------------------------------------------------------------------
    // Stealth-Modus: Launcher-Icon per AXML-Surgery entfernen
    // ------------------------------------------------------------------

    private fun stealthWorkDir(): File = File(requireContext().cacheDir, "stealth_$packageName")

    /** Dauerhafter Aufbewahrungsort für Original-APKs (überlebt Cache-Leeren). */
    private fun stealthPreservedDir(): File = File(requireContext().filesDir, "stealth_$packageName")

    /** Original-APK auflösen: dauerhafter Ort zuerst, sonst Legacy-Cache. */
    private fun resolveOriginalApkFile(): File? {
        val preserved = File(stealthPreservedDir(), "original.apk")
        if (preserved.exists() && preserved.length() > 0) return preserved
        val cached = File(stealthWorkDir(), "original.apk")
        return if (cached.exists() && cached.length() > 0) cached else null
    }

    private fun setupStealth() {
        if (backend is NoopBackend) {
            // Ohne Shizuku keine APK-Manipulation
            root.findViewById<MaterialButton>(R.id.stealthApplyButton).isEnabled = false
            root.findViewById<MaterialButton>(R.id.stealthRestoreButton).isEnabled = false
            return
        }
        stealthStealer = StealthStealer(backend, requireContext().applicationContext)
        originalApkFile = resolveOriginalApkFile()
        val restoreBtn = root.findViewById<MaterialButton>(R.id.stealthRestoreButton)
        restoreBtn.isEnabled = originalApkFile?.exists() == true

        root.findViewById<MaterialButton>(R.id.stealthApplyButton).setOnClickListener {
            val removeIcon =
                root.findViewById<SwitchMaterial>(R.id.stealthRemoveLauncherIconSwitch).isChecked
            val vaultCalc =
                root.findViewById<SwitchMaterial>(R.id.stealthVaultCalcSwitch).isChecked
            stealthApply(removeIcon, vaultCalc)
        }
        restoreBtn.setOnClickListener { stealthRestore() }

        // Vault-Switch: PIN-Feld nur zeigen, wenn Vault aktiv
        val vaultSwitch = root.findViewById<SwitchMaterial>(R.id.stealthVaultCalcSwitch)
        val pinLayout = root.findViewById<com.google.android.material.textfield.TextInputLayout>(
            R.id.stealthVaultPinLayout
        )
        vaultSwitch.setOnCheckedChangeListener { _, checked ->
            pinLayout.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Spoof-Switch: Build-Felder nur zeigen, wenn Build-Spoof aktiv
        val spoofBuildSwitch = root.findViewById<SwitchMaterial>(R.id.stealthSpoofBuildSwitch)
        val spoofBuildFields = root.findViewById<LinearLayout>(R.id.spoofBuildFields)
        spoofBuildSwitch.setOnCheckedChangeListener { _, checked ->
            spoofBuildFields.visibility = if (checked) View.VISIBLE else View.GONE
        }

        // Zufalls-Button: komplette Geräteprofil-Sets füllen
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.spoofRandomButton)
            .setOnClickListener { fillRandomProfile() }

        // Clone-Switch: Paketname-Feld nur zeigen, wenn Clone aktiv (Default: an)
        val cloneSwitch = root.findViewById<SwitchMaterial>(R.id.stealthCloneSwitch)
        val clonePkgLayout = root.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.stealthClonePkgLayout)
        clonePkgLayout.visibility = if (cloneSwitch.isChecked) View.VISIBLE else View.GONE
        cloneSwitch.setOnCheckedChangeListener { _, checked ->
            clonePkgLayout.visibility = if (checked) View.VISIBLE else View.GONE
        }
    }

    private fun readSpoofOptions(): DeviceSpoofOptions {
        val spoofBuild = root.findViewById<SwitchMaterial>(R.id.stealthSpoofBuildSwitch).isChecked
        val spoofAndroidId = root.findViewById<SwitchMaterial>(R.id.stealthSpoofAndroidIdSwitch).isChecked
        val spoofTelephony = root.findViewById<SwitchMaterial>(R.id.stealthSpoofTelephonySwitch).isChecked
        val modelInput = root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofModelInput)
        val manufacturerInput = root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofManufacturerInput)
        val fingerprintInput = root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofFingerprintInput)
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
        root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofModelInput)
            .setText(profile.model)
        root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofManufacturerInput)
            .setText(profile.manufacturer)
        root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.spoofFingerprintInput)
            .setText(profile.fingerprint)
        Toast.makeText(
            requireContext(),
            "Profil: ${profile.model} (${profile.manufacturer})",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun stealthApply(removeLauncherIcon: Boolean, vaultCalc: Boolean) {
        val excludeRecents =
            root.findViewById<SwitchMaterial>(R.id.stealthExcludeFromRecentsSwitch).isChecked
        val secureSnapshot =
            root.findViewById<SwitchMaterial>(R.id.stealthSecureSnapshotSwitch).isChecked
        val requireVpn =
            root.findViewById<SwitchMaterial>(R.id.stealthRequireVpnSwitch).isChecked
        val spoofOptions = readSpoofOptions()
        val cloneSwitch = root.findViewById<SwitchMaterial>(R.id.stealthCloneSwitch).isChecked
        val clonePkgInput = root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.stealthClonePkgInput)
        if (!excludeRecents && !removeLauncherIcon && !secureSnapshot && !requireVpn && !vaultCalc && !spoofOptions.anyEnabled && !cloneSwitch) {
            Toast.makeText(requireContext(), R.string.stealth_need_launcher, Toast.LENGTH_SHORT).show()
            return
        }
        // Vault-PIN validieren (falls aktiv): nur Ziffern, 4-10 Stellen
        var vaultPin: String? = null
        if (vaultCalc) {
            val pinInput = root.findViewById<com.google.android.material.textfield.TextInputEditText>(
                R.id.stealthVaultPinInput
            )
            val pin = pinInput.text?.toString()?.trim().orEmpty()
            if (pin.isEmpty()) {
                // Leer = Default-PIN "12345"
                vaultPin = null
            } else if (!pin.all { it.isDigit() } || pin.length < 4 || pin.length > 10) {
                Toast.makeText(requireContext(), R.string.stealth_vault_pin_invalid, Toast.LENGTH_LONG).show()
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
                    requireContext().packageManager.getApplicationInfo(packageName, 0).loadLabel(requireContext().packageManager).toString()
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
        MaterialAlertDialogBuilder(requireContext())
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
        !requireContext().packageManager.getApplicationInfo(packageName, 0).splitNames.isNullOrEmpty()
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
            Toast.makeText(requireContext(), R.string.status_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        // Notification-Berechtigung (Android 13+): OHNE sie ist die
        // Fortschritts-Benachrichtigung im Shade versteckt. Der Schalter in den
        // Einstellungen existiert erst dank Manifest-Eintrag POST_NOTIFICATIONS
        // (realer User-Fall 08/2026) — hier aktiv nachfragen; der Patch laeuft
        // trotzdem weiter (Fortschritt im Dialog).
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                requireActivity(),
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
        val intent = Intent(requireContext(), StealthPatchService::class.java).apply {
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
        ContextCompat.startForegroundService(requireContext(), intent)
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
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = layoutInflater.inflate(R.layout.dialog_progress, null)
        // View direkt merken: dialog.findViewById(android.R.id.custom) liefert
        // bei MaterialAlertDialogBuilder NULL (realer Bug 08/2026, E2E)!
        progressDialogView = view
        progressDialog = MaterialAlertDialogBuilder(requireContext())
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
        text.text = StealthPatchService.phaseText(requireContext(), phase)
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
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Erfolgs-/Fehlerdialog statt Toast (Toast ist zu kurz zum Lesen). */
    private fun showResultDialog(success: Boolean, message: String) {
        val title =
            if (success) R.string.stealth_dialog_success_title else R.string.stealth_dialog_error_title
        MaterialAlertDialogBuilder(requireContext())
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
            root.findViewById<MaterialButton>(R.id.stealthRestoreButton).isEnabled = !pendingRestore
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
                        VaultIntegration.configureVaultCalculator(
                            requireContext(),
                            vaultPkg,
                            vaultLauncher,
                            pendingVaultPin
                        )
                    } else {
                        Toast.makeText(
                            requireContext(),
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
        requireContext().getSharedPreferences("stealth", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(packageName, stealthy).apply()
    }

    private fun stealthRestore() {
        if (backend is NoopBackend) {
            Toast.makeText(requireContext(), R.string.status_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        pendingRestore = true
        pendingVault = false
        val intent = Intent(requireContext(), StealthPatchService::class.java).apply {
            action = StealthPatchService.ACTION_RESTORE
            putExtra(StealthPatchService.EXTRA_PACKAGE, packageName)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
        showProgressDialog()
    }
}
