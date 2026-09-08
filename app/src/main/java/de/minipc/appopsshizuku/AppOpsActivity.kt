package de.minipc.appopsshizuku

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import de.minipc.appopsshizuku.appops.AppOpsController
import de.minipc.appopsshizuku.appops.AppOp
import de.minipc.appopsshizuku.appops.PrivacyOps
import de.minipc.appopsshizuku.appops.PrivacyProfileStore
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.ui.AppOpsAdapter
import de.minipc.appopsshizuku.ui.OpsListItem
import java.util.concurrent.Executors

/**
 * Detail-Ansicht einer App: App-Header, Statistik (blockiert/erlaubt/standard),
 * Privacy-Ops gruppiert in Kategorien (Sensoren / Daten / Konto & Aktivitaet),
 * Mode-Spinner pro Op, "Alle auf Standard" + Lade-Fortschritt.
 */
class AppOpsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "pkg"

        private val CATEGORY_SENSORS = listOf(
            "CAMERA", "RECORD_AUDIO", "BODY_SENSORS", "ACTIVITY_RECOGNITION"
        )
        private val CATEGORY_DATA = listOf(
            "READ_CONTACTS", "WRITE_CONTACTS", "READ_CALENDAR", "WRITE_CALENDAR",
            "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_CLIPBOARD", "WRITE_CLIPBOARD"
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
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var backend: de.minipc.appopsshizuku.shizuku.ShellBackend
    private lateinit var controller: AppOpsController
    private lateinit var profileStore: PrivacyProfileStore
    private lateinit var packageName: String
    private lateinit var adapter: AppOpsAdapter

    /** opName -> aktueller Mode (null = noch nicht geladen) */
    private val opStates = mutableMapOf<String, String?>()
    private val ops: List<AppOp> = PrivacyOps.PRIVACY_OPS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appops)

        findViewById<MaterialToolbar>(R.id.toolbar).let { toolbar ->
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setDisplayShowTitleEnabled(true)
        }

        packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }

        val shizukuOn = runCatching {
            de.minipc.appopsshizuku.shizuku.ShizukuBackend().let {
                it.isAvailable() && it.hasPermission()
            }
        }.getOrDefault(false)
        backend = if (shizukuOn) de.minipc.appopsshizuku.shizuku.ShizukuBackend() else NoopBackend()
        controller = AppOpsController(backend)
        profileStore = PrivacyProfileStore(this)

        loadAppInfo()

        adapter = AppOpsAdapter { op, mode -> onModeSelected(op, mode) }
        findViewById<RecyclerView>(R.id.opList).apply {
            layoutManager = LinearLayoutManager(this@AppOpsActivity)
            this.adapter = this@AppOpsActivity.adapter
        }

        findViewById<MaterialButton>(R.id.restoreAllButton).setOnClickListener {
            restoreAll()
        }

        loadAllModes()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_appops, menu)
        // Icon-Tint fuer Dark Mode: ic_blocked hat Schwarzes fillColor (#FF000000)
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
        menu.findItem(R.id.action_components)?.icon?.setTint(typedValue.data)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_components -> {
                startActivity(
                    Intent(this, ComponentsActivity::class.java)
                        .putExtra(ComponentsActivity.EXTRA_PACKAGE, packageName)
                )
                true
            }
            R.id.action_export_profiles -> {
                exportProfiles()
                true
            }
            R.id.action_import_profiles -> {
                importPicker.launch(arrayOf("application/json", "text/*", "*/*"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importProfiles(uri)
        }

    private fun exportProfiles() {
        executor.execute {
            val profiles = profileStore.allProfiles()
            handler.post {
                if (profiles.isEmpty()) {
                    Toast.makeText(this, R.string.profiles_export_empty, Toast.LENGTH_SHORT).show()
                    return@post
                }
                try {
                    profileStore.exportFile().writeText(PrivacyProfileStore.toJson(profiles))
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_export_ok, profiles.size),
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_export_fail, e.message ?: "?"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * JSON-Datei (SAF-Picker) einlesen: Profile in die Prefs mergen und die
     * Modi der aktuell geoeffneten App direkt anwenden (Klon-Backup: gleiche
     * Ops auf das Klon-Paket setzen).
     */
    private fun importProfiles(uri: Uri) {
        findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = View.VISIBLE
        executor.execute {
            var importedApps = 0
            var appliedOps = 0
            var error: String? = null
            try {
                val json = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalArgumentException("Datei nicht lesbar")
                val profiles = PrivacyProfileStore.fromJson(json)
                profileStore.importProfiles(profiles)
                importedApps = profiles.size

                // Profil dieser App (falls enthalten) live anwenden
                val ownProfile = profiles[packageName]
                if (ownProfile != null) {
                    for (op in ops) {
                        val mode = ownProfile[op.opName] ?: continue
                        val res = controller.setMode(packageName, op, mode)
                        if (res.exitCode == 0) {
                            opStates[op.opName] = mode
                            appliedOps++
                        } else {
                            opStates[op.opName] = try {
                                controller.getMode(packageName, op)
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            handler.post {
                findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = View.GONE
                adapter.submitList(buildItems())
                updateStats()
                if (error != null) {
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_import_fail, error),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_import_ok, importedApps, appliedOps),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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

    private fun loadAllModes() {
        findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = View.VISIBLE
        executor.execute {
            for (op in ops) {
                opStates[op.opName] = try {
                    controller.getMode(packageName, op)
                } catch (e: Exception) {
                    null
                }
            }
            handler.post {
                findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = View.GONE
                adapter.submitList(buildItems())
                updateStats()
            }
        }
    }

    private fun buildItems(): List<OpsListItem> {
        val items = mutableListOf<OpsListItem>()
        val byName = ops.associateBy { it.opName }
        val categories = listOf(
            Pair(getString(R.string.category_sensors), CATEGORY_SENSORS),
            Pair(getString(R.string.category_data), CATEGORY_DATA),
            Pair(getString(R.string.category_account_activity), CATEGORY_ACCOUNT),
            Pair(getString(R.string.category_background), CATEGORY_BACKGROUND),
            Pair(getString(R.string.category_identity), CATEGORY_IDENTITY),
            Pair(getString(R.string.category_system_ui), CATEGORY_SYSTEM_UI)
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
            handler.post {
                if (res.exitCode == 0) {
                    opStates[op.opName] = mode
                    // Profil-Speicherung darf den UI-Thread NIE crashen
                    // (z.B. korruptes/leeres JSON in den Prefs -> JSONException).
                    runCatching { profileStore.setOpMode(packageName, op.opName, mode) }
                    adapter.submitList(buildItems())
                    updateStats()
                    Toast.makeText(
                        this,
                        getString(R.string.op_set_ok, op.label, mode),
                        Toast.LENGTH_SHORT
                    ).show()
                    // deny auf reinen AppOps (Zwischenablage) laesst die Ziel-App beim
                    // Start crashen (SecurityException, live verifiziert) -> Hinweis.
                    if (mode == "deny" && op.permissionName == null) {
                        Toast.makeText(this, R.string.op_deny_warning, Toast.LENGTH_LONG).show()
                    }
                } else {
                    adapter.submitList(buildItems())
                    Toast.makeText(
                        this,
                        getString(R.string.op_set_fail, op.label, res.exitCode),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun restoreAll() {
        findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = View.VISIBLE
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
                findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = View.GONE
                adapter.submitList(buildItems())
                updateStats()
                Toast.makeText(
                    this,
                    if (failed == 0) getString(R.string.restore_done)
                    else "Restore: $failed Fehler",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun updateStats() {
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
        findViewById<TextView>(R.id.statBlocked).text = blocked.toString()
        findViewById<TextView>(R.id.statAllowed).text = allowed.toString()
        findViewById<TextView>(R.id.statDefault).text = dflt.toString()
    }
}
