package de.minipc.appopsshizuku

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import com.google.android.material.button.MaterialButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import de.minipc.appopsshizuku.appops.BehaviorBackup
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.ProfileBackup
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.shizuku.ShizukuManager
import de.minipc.appopsshizuku.ui.AppItem
import de.minipc.appopsshizuku.ui.AppListAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Hauptansicht: alle Apps mit Launcher-Icon.
 * - Live-Suche + Filter-Chips (Alle/Blockiert)
 * - Sortierung: Name / Installationsdatum / Aktualisierungsdatum
 * - Shizuku-Status-Chip + Verbinden (Permission-Dialog mit Fallback)
 * - FAB zum Aktualisieren, Empty State
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Import ohne Dateidialog: beim Start mit "--es import_file <pfad>" aufrufbar (auch per Tasker). */
        const val EXTRA_IMPORT_FILE = "import_file"

        private const val PREFS_BACKUP = "backup_status"
        private const val KEY_BACKUP_TEXT = "text"
        private const val KEY_BACKUP_ERROR = "error"

        const val SORT_NAME = 0
        const val SORT_INSTALL = 1
        const val SORT_UPDATE = 2
        const val FILTER_ALL = 0
        const val FILTER_BLOCKED = 1
        const val FILTER_CHANGED = 2

        /** Gleichzeitige Shell-Aufrufe beim Blockiert-Scan. */
        private const val SCAN_THREADS = 4
    }

    private lateinit var manager: ShizukuManager
    private lateinit var profileBackup: ProfileBackup
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)

    /** SAF-Picker fuer den Profil-Import (JSON), global von der Toolbar aus. */
    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importProfiles(uri)
        }

    /** Alle Apps (ungefiltert, Basis fuer Filter+Sortierung). Nur per Referenz-Tausch aendern. */
    private var allApps: List<AppItem> = emptyList()
    private lateinit var adapter: AppListAdapter

    private var sortMode = SORT_NAME
    private var query = ""
    private var filterMode = FILTER_ALL

    /** Cache: Paketname -> hat mind. eine blockierte Op (ignore/deny auf UID-Ebene) */
    private val blockedCache = mutableMapOf<String, Boolean>()

    /** Cache: Paketname -> Icon (max. 100 Eintraege, sonst Icon-Neuladen bei jedem onResume) */
    private val iconCache = LruCache<String, Drawable>(100)

    /** Icon aus dem Cache; nur bei Cache-Miss den PackageManager fragen. */
    private fun cachedIcon(pm: PackageManager, pkg: String): Drawable? =
        iconCache.get(pkg) ?: try {
            pm.getApplicationIcon(pkg).also { iconCache.put(pkg, it) }
        } catch (e: Exception) {
            null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.title = getString(R.string.app_name)

        // Edge-to-Edge: Systemleisten-Abstaende auf das Wurzel-Layout legen,
        // damit die Toolbar unter der Statusleiste und die Liste ueber der
        // Navigationsleiste bleiben (kein Inhalt/Tap geht verloren).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        profileBackup = ProfileBackup(this)

        // Feature 3: Bildschirm-Aus-Waechter wieder starten, falls Apps den Haken haben
        // (nach Neustart/Prozess-Ende). Hier ist die App sicher im Vordergrund.
        LifecycleScreenService.sync(this)

        // Direkt-Import ohne Dateidialog: am start ... --es import_file <pfad>
        // (praktisch fuer Skripte/Tasker und fuer Tests; der System-Dialog ist headless unzuverlaessig)
        intent?.getStringExtra(EXTRA_IMPORT_FILE)?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.isFile) {
                importProfiles(Uri.fromFile(file))
            } else {
                setBackupStatus(
                    getString(R.string.backup_status_import_fail, "Datei nicht lesbar: $path"), true
                )
            }
        }

        // Letzte Backup-Meldung wieder anzeigen (bleibt auch nach App-Neustart lesbar)
        getSharedPreferences(PREFS_BACKUP, MODE_PRIVATE).let { prefs ->
            val last = prefs.getString(KEY_BACKUP_TEXT, null)
            if (!last.isNullOrBlank()) {
                showBackupStatus(last, prefs.getBoolean(KEY_BACKUP_ERROR, false))
            }
        }
        manager = ShizukuManager(onStateChanged = { runOnUiThread { updateStatus() } })
        manager.refresh()

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            manager.refresh()
            when {
                !manager.isConnected -> Toast.makeText(
                    this, "Shizuku starten (App oeffnen), dann erneut pruefen", Toast.LENGTH_LONG
                ).show()
                !manager.hasPermission -> requestShizukuPermission()
                else -> updateStatus()
            }
        }

        setupSearchAndSort()

        adapter = AppListAdapter { app ->
            startActivity(
                Intent(this, AppDetailActivity::class.java)
                    .putExtra(AppDetailActivity.EXTRA_PACKAGE, app.packageName)
            )
        }
        findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = this@MainActivity.adapter
        }

        findViewById<ChipGroup>(R.id.filterChipGroup).setOnCheckedStateChangeListener { _, checkedIds ->
            filterMode = when {
                R.id.chipBlocked in checkedIds -> FILTER_BLOCKED
                R.id.chipChanged in checkedIds -> FILTER_CHANGED
                else -> FILTER_ALL
            }
            if (filterMode == FILTER_BLOCKED && blockedCache.isEmpty()) {
                scanBlockedState()
            } else {
                applyFilterAndSort()
            }
        }

        findViewById<MaterialButton>(R.id.driftRestore).setOnClickListener { restoreDrift() }

        findViewById<FloatingActionButton>(R.id.fabRefresh).setOnClickListener {
            blockedCache.clear()
            loadApps()
        }

        // KEIN loadApps() hier: onResume laedt direkt nach onCreate — doppeltes
        // Laden wuerde parallel laufen (race auf allApps)
        updateStatus()
    }

    private fun setupSearchAndSort() {
        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilterAndSort()
            }
        })

        val spinner = findViewById<AutoCompleteTextView>(R.id.sortSpinner)
        spinner.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                resources.getStringArray(R.array.sort_options)
            )
        )
        spinner.setText(resources.getStringArray(R.array.sort_options)[sortMode], false)
        spinner.setOnItemClickListener { _, _, pos, _ ->
            sortMode = pos
            applyFilterAndSort()
        }
        // Tastatur unterdruecken (Exposed Dropdown zeigt nur die Liste)
        spinner.inputType = android.text.InputType.TYPE_NULL
        spinner.keyListener = null
    }

    private fun loadApps() {
        executor.execute {
            val pm = packageManager
            val resolved = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            val seen = mutableSetOf<String>()
            val loaded = mutableListOf<AppItem>()
            for (ri in resolved) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) continue
                if (!seen.add(pkg)) continue
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    pkg
                }
                val icon = cachedIcon(pm, pkg)
                var firstInstall = 0L
                var lastUpdate = 0L
                try {
                    val pi = pm.getPackageInfo(pkg, 0)
                    firstInstall = pi.firstInstallTime
                    lastUpdate = pi.lastUpdateTime
                } catch (e: Exception) {
                    // Zeiten nicht verfuegbar -> 0
                }
                loaded.add(AppItem(pkg, label, icon, firstInstall, lastUpdate))
            }

            // Deaktivierte Apps nachladen (re-aktivierbar) — via Shell pm list packages -d
            val backend = if (manager.isConnected && manager.hasPermission) ShizukuBackend() else NoopBackend()
            if (backend is ShizukuBackend) {
                val res = backend.exec("pm", "list", "packages", "-d")
                android.util.Log.d("AppOpsShizuku", "pm list -d: exit=${res.exitCode} stderr=${res.stderr.take(200)}")
                android.util.Log.d("AppOpsShizuku", "pm list -d stdout=${res.stdout.take(400)}")
                val disabled = if (res.exitCode == 0 && !res.stderr.contains("Exception", true) && !res.stdout.contains("Exception", true)) {
                    Regex("package:([\\w.]+)").findAll(res.stdout).map { it.groupValues[1] }
                        .filter { it != packageName && it !in seen }
                        .toList()
                } else emptyList()
                android.util.Log.d("AppOpsShizuku", "disabled gefiltert: $disabled")
                for (pkg in disabled) {
                    seen.add(pkg)
                    val label = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) {
                        android.util.Log.d("AppOpsShizuku", "label fail $pkg: ${e.message}")
                        pkg
                    }
                    val icon = cachedIcon(pm, pkg)
                    loaded.add(
                        AppItem(
                            pkg,
                            getString(R.string.app_disabled_label, label),
                            icon ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, null),
                            0L, 0L
                        )
                    )
                    android.util.Log.d("AppOpsShizuku", "added disabled: $pkg")
                }
            }

            // Gestealthte Apps (Launcher-Icon entfernt -> nicht in queryIntentActivities)
            // weiterhin anzeigen, damit sie auffindbar/steuerbar bleiben
            val stealthPrefs = getSharedPreferences("stealth", MODE_PRIVATE)
            for ((pkg, v) in stealthPrefs.all) {
                if (v != true || pkg in seen) continue
                seen.add(pkg)
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
                val icon = cachedIcon(pm, pkg)
                loaded.add(
                    AppItem(
                        pkg,
                        getString(R.string.stealth_label, label),
                        icon ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, null),
                        0L, 0L
                    )
                )
            }

            // Status-Symbole aus den eigenen Speichern (kein Shell-Aufruf)
            val withBadges = loaded.map { it.copy(badges = badgeText(it.packageName)) }

            handler.post {
                // Atomare Zuweisung: keine Mutation, kein Datenrennen mit
                // scanBlockedState()/applyFilterAndSort()
                allApps = withBadges
                applyFilterAndSort()
            }
        }
    }

    private val profileStoreForBadges by lazy { de.minipc.appopsshizuku.appops.PrivacyProfileStore(this) }
    private val netStoreForBadges by lazy { de.minipc.appopsshizuku.appops.NetStore(this) }
    private val lifecycleStoreForBadges by lazy { de.minipc.appopsshizuku.appops.LifecycleStore(this) }

    /** Status-Symbole als kurzer Text ("3 blockiert · Offline · Nur bei Bedarf"). */
    private fun badgeText(pkg: String): String {
        val set = runCatching {
            de.minipc.appopsshizuku.appops.AppBadges.compute(
                profile = profileStoreForBadges.getProfile(pkg),
                net = netStoreForBadges.get(pkg),
                lifecycle = lifecycleStoreForBadges.get(pkg),
                whileInUse = badgeWhileInUse(pkg),
                quarantined = badgeQuarantined(pkg)
            )
        }.getOrNull() ?: return ""
        return set.badges.joinToString(" · ") { b ->
            when (b) {
                de.minipc.appopsshizuku.appops.AppBadge.BLOCKED -> getString(R.string.badge_blocked, set.blockedCount)
                de.minipc.appopsshizuku.appops.AppBadge.OFFLINE -> getString(R.string.badge_offline)
                de.minipc.appopsshizuku.appops.AppBadge.BG_NET -> getString(R.string.badge_bg_net)
                de.minipc.appopsshizuku.appops.AppBadge.ON_DEMAND -> getString(R.string.badge_on_demand)
                de.minipc.appopsshizuku.appops.AppBadge.SCREEN_OFF -> getString(R.string.badge_screen_off)
                de.minipc.appopsshizuku.appops.AppBadge.WHILE_IN_USE -> getString(R.string.badge_while_in_use)
                de.minipc.appopsshizuku.appops.AppBadge.QUARANTINE -> getString(R.string.badge_quarantine)
            }
        }
    }

    private val wiuStoreForBadges by lazy { de.minipc.appopsshizuku.appops.WhileInUseStore(this) }
    private val quarantineStoreForBadges by lazy { de.minipc.appopsshizuku.appops.QuarantineStore(this) }

    private fun badgeWhileInUse(pkg: String): Boolean = wiuStoreForBadges.ops(pkg).isNotEmpty()
    private fun badgeQuarantined(pkg: String): Boolean = quarantineStoreForBadges.isQuarantined(pkg)

    /** Quarantaene: neue Apps seit dem letzten Oeffnen erfassen (im Hintergrund, mit Rueckmeldung). */
    private fun checkQuarantine() {
        executor.execute {
            val r = runCatching { QuarantineManager.check(this) }.getOrNull() ?: return@execute
            val total = r.applied.size + r.failed.size
            if (total == 0) return@execute
            handler.post {
                setBackupStatus(getString(R.string.quarantine_report, total, r.failed.size), r.failed.isNotEmpty())
                loadApps()
            }
        }
    }

    private fun toggleQuarantine(item: MenuItem) {
        val store = quarantineStoreForBadges
        if (store.enabled) {
            store.enabled = false
            item.isChecked = false
            Toast.makeText(this, R.string.quarantine_off, Toast.LENGTH_LONG).show()
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quarantine_enable_title)
            .setMessage(R.string.quarantine_enable_msg)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.quarantine_enable_action) { _, _ ->
                // Ab jetzt: nur Apps, die NACH dem Einschalten installiert werden
                store.checkedUntil = System.currentTimeMillis()
                store.enabled = true
                item.isChecked = true
            }
            .show()
    }

    /** Live-Filter (Query) + Filter-Chip + Sortierung auf allApps. */
    private fun applyFilterAndSort() {
        val q = query.lowercase()
        val filtered = allApps.filter { app ->
            when {
                q.isNotEmpty() && !app.label.lowercase().contains(q) && !app.packageName.lowercase().contains(q) -> false
                filterMode == FILTER_BLOCKED -> blockedCache[app.packageName] == true
                filterMode == FILTER_CHANGED -> app.badges.isNotEmpty()
                else -> true
            }
        }
        val sorted = when (sortMode) {
            SORT_INSTALL -> filtered.sortedByDescending { it.firstInstallTime }
            SORT_UPDATE -> filtered.sortedByDescending { it.lastUpdateTime }
            else -> filtered.sortedBy { it.label.lowercase() }
        }
        adapter.submitList(sorted)
        findViewById<View>(R.id.emptyState).visibility =
            if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Scannt alle Apps (ein cmd appops get pro App) und cached, welche blockierte Ops haben. */
    private fun scanBlockedState() {
        val pm = packageManager
        val backend = if (manager.isConnected && manager.hasPermission) ShizukuBackend() else NoopBackend()
        if (backend is NoopBackend) {
            Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_LONG).show()
            filterMode = FILTER_ALL
            findViewById<ChipGroup>(R.id.filterChipGroup).check(R.id.chipAll)
            return
        }
        Toast.makeText(this, R.string.scanning_apps, Toast.LENGTH_SHORT).show()
        val list = allApps
        executor.execute {
            // Parallel statt nacheinander: ein Shell-Aufruf je App, 4 gleichzeitig. Bei 200 Apps
            // war der serielle Scan deutlich spuerbar; die Binder-Aufrufe vertragen das problemlos.
            val pool = Executors.newFixedThreadPool(SCAN_THREADS)
            try {
                pool.invokeAll(list.map { app ->
                    java.util.concurrent.Callable {
                        val res = backend.exec("cmd", "appops", "get", app.packageName)
                        val blocked = res.exitCode == 0 &&
                            de.minipc.appopsshizuku.appops.BlockedScan.hasBlockedOp(res.stdout)
                        synchronized(blockedCache) { blockedCache[app.packageName] = blocked }
                    }
                })
            } finally {
                pool.shutdown()
            }
            handler.post { applyFilterAndSort() }
        }
    }

    /**
     * Shizuku-Berechtigung anfragen. Der Dialog wird vom Shizuku-Server als
     * Activity in der Shizuku-App gezeigt — auf manchen Setups erscheint er nicht.
     * Fallback: Shizuku-App oeffnen; onResume aktualisiert den Status.
     */
    private fun requestShizukuPermission() {
        manager.requestPermission(this)
        handler.postDelayed({
            manager.refresh()
            if (!manager.hasPermission) {
                openShizukuApp()
                Toast.makeText(
                    this,
                    "Berechtigungs-Dialog nicht sichtbar? In der Shizuku-App unter 'Berechtigungen' autorisieren.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 2000)
    }

    private fun openShizukuApp() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage("moe.shizuku.privileged.api")
            )
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_shizuku_app_missing), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        manager.refresh()
        updateStatus()
        // Liste neu laden: nach Rueckkehr aus Komponenten-Ansicht koennen Apps
        // deaktiviert/aktiviert worden sein
        loadApps()
        checkQuarantine()
        // Automatik-Regeln bei jedem Oeffnen auswerten (WLAN-Regeln sonst nur im ~15-min-Takt)
        executor.execute { runCatching { AutoRuleManager.evaluate(this) } }
        if (manager.isConnected && manager.hasPermission) {
            BootReceiver.clear(this)
            checkDrift()
        } else {
            findViewById<View>(R.id.driftCard).visibility = View.GONE
        }
    }

    /** opName+Paket -> gewuenschter Modus, der gerade NICHT durchgesetzt ist. */
    private var driftItems: List<de.minipc.appopsshizuku.appops.DriftCheck.Item> = emptyList()

    /**
     * Gespeichertes Profil mit dem Ist-Zustand vergleichen (nur Apps mit Profil, meist wenige).
     * Zeigt eine Leiste "N Einstellungen nicht mehr aktiv - wiederherstellen". Nichts wird dabei gesetzt.
     */
    private fun checkDrift() {
        executor.execute {
            val backend = de.minipc.appopsshizuku.shizuku.ShizukuBackend()
            if (!runCatching { backend.isAvailable() && backend.hasPermission() }.getOrDefault(false)) return@execute
            val controller = de.minipc.appopsshizuku.appops.AppOpsController(backend)
            val catalog = de.minipc.appopsshizuku.appops.PrivacyOps.PRIVACY_OPS.associateBy { it.opName }
            val stored = de.minipc.appopsshizuku.appops.PrivacyProfileStore(this).allProfiles()
            val installed = packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
            val items = mutableListOf<de.minipc.appopsshizuku.appops.DriftCheck.Item>()
            for ((pkg, ops) in stored) {
                if (pkg !in installed) continue
                for ((opName, want) in ops) {
                    val op = catalog[opName] ?: continue
                    val actual = runCatching { controller.getMode(pkg, op) }.getOrNull()
                    if (de.minipc.appopsshizuku.appops.DriftCheck.isDrift(op.permissionName, want, actual)) {
                        items += de.minipc.appopsshizuku.appops.DriftCheck.Item(pkg, opName, want)
                    }
                }
            }
            handler.post {
                driftItems = items
                val card = findViewById<View>(R.id.driftCard)
                if (items.isEmpty()) {
                    card.visibility = View.GONE
                } else {
                    card.visibility = View.VISIBLE
                    findViewById<TextView>(R.id.driftText).text =
                        getString(R.string.drift_text, items.size, items.map { it.packageName }.distinct().size)
                }
            }
        }
    }

    private fun restoreDrift() {
        val items = driftItems
        if (items.isEmpty()) return
        findViewById<View>(R.id.driftCard).visibility = View.GONE
        executor.execute {
            val backend = de.minipc.appopsshizuku.shizuku.ShizukuBackend()
            val controller = de.minipc.appopsshizuku.appops.AppOpsController(backend)
            val catalog = de.minipc.appopsshizuku.appops.PrivacyOps.PRIVACY_OPS.associateBy { it.opName }
            var failed = 0
            for (it in items) {
                val op = catalog[it.opName] ?: continue
                val res = controller.setMode(it.packageName, op, it.want)
                if (res.exitCode != 0) failed++
            }
            handler.post {
                val f = failed
                Toast.makeText(
                    this,
                    if (f == 0) getString(R.string.drift_restored, items.size)
                    else getString(R.string.drift_restored_partial, items.size - f, f),
                    Toast.LENGTH_LONG
                ).show()
                checkDrift()
                loadApps()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        menu.findItem(R.id.action_quarantine)?.isChecked = quarantineStoreForBadges.enabled
        menu.findItem(R.id.action_dynamic_color).apply {
            if (DynamicColors.isDynamicColorAvailable()) {
                isChecked = DesignSettings.isDynamicColorEnabled(this@MainActivity)
            } else {
                isEnabled = false
                isChecked = false
                setTitle(R.string.menu_dynamic_color_unavailable)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_quarantine -> {
                toggleQuarantine(item)
                true
            }
            R.id.action_auto_rules -> {
                startActivity(Intent(this, AutoRulesActivity::class.java))
                true
            }
            R.id.action_change_log -> {
                startActivity(Intent(this, ChangeLogActivity::class.java))
                true
            }
            R.id.action_system -> {
                startActivity(Intent(this, SystemActivity::class.java))
                true
            }
            R.id.action_by_permission -> {
                startActivity(Intent(this, PermissionOverviewActivity::class.java))
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
            R.id.action_dynamic_color -> {
                val enabled = !DesignSettings.isDynamicColorEnabled(this)
                DesignSettings.setDynamicColorEnabled(this, enabled)
                item.isChecked = enabled
                Toast.makeText(
                    this,
                    if (enabled) R.string.dynamic_color_on else R.string.dynamic_color_off,
                    Toast.LENGTH_SHORT
                ).show()
                recreate() // Theme wird nur beim Erzeugen der Activity gesetzt
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Alle gespeicherten Profile als JSON in den App-Ordner schreiben. */
    private fun exportProfiles() {
        executor.execute {
            val result = runCatching {
                // Einheitliches Backup: Berechtigungsprofile + Verhaltens-Einstellungen
                // (Standby-Stufe, Akku-Ausnahme, eingefroren, deaktiviert) in EINER Datei.
                val behavior = if (manager.isConnected && manager.hasPermission && allApps.isNotEmpty()) {
                    runCatching {
                        BehaviorBackup(ShizukuBackend()).collect(allApps.map { it.packageName })
                    }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
                profileBackup.export(behavior)
            }
            handler.post {
                result.onSuccess { r ->
                    if (r.count == 0 && r.behaviorCount == 0 && r.lifecycleCount == 0) {
                        setBackupStatus(getString(R.string.profiles_export_empty), false)
                        Toast.makeText(this, R.string.profiles_export_empty, Toast.LENGTH_SHORT).show()
                    } else {
                        setBackupStatus(
                            getString(R.string.backup_status_export_ok, r.count, r.behaviorCount), false
                        )
                        Toast.makeText(
                            this,
                            getString(R.string.profiles_export_ok, r.count, r.behaviorCount),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }.onFailure { e ->
                    val reason = e.message ?: e.javaClass.simpleName
                    setBackupStatus(getString(R.string.backup_status_export_fail, reason), true)
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_export_fail, reason),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** JSON-Datei (SAF) einlesen und in die Prefs mergen (global, alle Apps). */
    private fun importProfiles(uri: Uri) {
        executor.execute {
            val result = runCatching { profileBackup.import(uri) }
            handler.post {
                result.onSuccess { imported ->
                    val shizukuOk = manager.isConnected && manager.hasPermission
                    val message = when {
                        imported.behavior.isEmpty() ->
                            getString(R.string.profiles_import_ok_global, imported.profiles.size)

                        !shizukuOk ->
                            getString(R.string.profiles_import_ok_global, imported.profiles.size) + " – " +
                                getString(R.string.profiles_import_behavior_need_shizuku)

                        else -> {
                            // Ehrlich: die Zahl der Einstellungen, die das Geraet NICHT uebernommen hat
                            val errors = runCatching {
                                val backup = BehaviorBackup(ShizukuBackend())
                                imported.behavior.flatMap { (pkg, state) ->
                                    backup.restoreToDevice(pkg, state, applicationContext.packageName)
                                }
                            }.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }

                            if (errors.isEmpty()) {
                                setBackupStatus(
                                    getString(
                                        R.string.backup_status_import_ok,
                                        imported.profiles.size, imported.behavior.size
                                    ), false
                                )
                                getString(
                                    R.string.profiles_import_ok_full,
                                    imported.profiles.size, imported.behavior.size
                                )
                            } else {
                                // Klartext-Grund mitschreiben: so ist der Fehler auch spaeter lesbar
                                setBackupStatus(
                                    getString(
                                        R.string.backup_status_import_warn,
                                        imported.profiles.size, errors.size,
                                        errors.take(3).joinToString("; ")
                                    ), true
                                )
                                getString(
                                    R.string.profiles_import_ok_with_errors,
                                    imported.profiles.size, errors.size
                                )
                            }
                        }
                    }
                    // Importierte Screen-Off-Haken wirken erst mit laufendem Dienst
                    if (imported.lifecycle.isNotEmpty()) LifecycleScreenService.sync(this)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    val reason = e.message ?: e.javaClass.simpleName
                    setBackupStatus(getString(R.string.backup_status_import_fail, reason), true)
                    Toast.makeText(
                        this,
                        getString(R.string.profiles_import_fail, reason),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Bleibende Rueckmeldung fuer Export/Import: sichtbar oben im Hauptbildschirm UND im
     * App-Speicher abgelegt - so ist auch ein Fehlergrund noch lesbar, wenn der Toast weg ist.
     */
    private fun setBackupStatus(message: String, isError: Boolean) {
        val stamp = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        val text = "$message\n$stamp"
        getSharedPreferences(PREFS_BACKUP, MODE_PRIVATE).edit()
            .putString(KEY_BACKUP_TEXT, text)
            .putBoolean(KEY_BACKUP_ERROR, isError)
            .apply()
        showBackupStatus(text, isError)
    }

    private fun showBackupStatus(text: String, isError: Boolean) {
        val view = findViewById<TextView>(R.id.backupStatus) ?: return
        view.text = text
        view.visibility = View.VISIBLE
        view.setBackgroundColor(
            themeColor(
                if (isError) com.google.android.material.R.attr.colorErrorContainer
                else com.google.android.material.R.attr.colorSurfaceVariant
            )
        )
        view.setTextColor(
            themeColor(
                if (isError) com.google.android.material.R.attr.colorOnErrorContainer
                else com.google.android.material.R.attr.colorOnSurfaceVariant
            )
        )
    }

    /** Theme-Farbe (Attribut) aufloesen — respektiert Material-You/Dark-Mode. */
    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun updateStatus() {
        val card = findViewById<MaterialCardView>(R.id.statusCard)
        val title = findViewById<TextView>(R.id.statusTitle)
        val detail = findViewById<TextView>(R.id.statusDetail)
        val button = findViewById<Button>(R.id.connectButton)

        val bgAttr: Int
        val fgAttr: Int
        when {
            !manager.isConnected -> {
                title.setText(R.string.status_shizuku_disconnected)
                detail.setText(R.string.status_detail_disconnected)
                button.setText(R.string.btn_connect)
                bgAttr = com.google.android.material.R.attr.colorSecondaryContainer
                fgAttr = com.google.android.material.R.attr.colorOnSecondaryContainer
            }
            !manager.hasPermission -> {
                // Berechtigung fehlt = gesperrt/verweigert, kein Fehler -> tertiary statt error.
                title.setText(R.string.status_permission_missing)
                detail.setText(R.string.status_detail_permission)
                button.setText(R.string.btn_connect)
                bgAttr = com.google.android.material.R.attr.colorTertiaryContainer
                fgAttr = com.google.android.material.R.attr.colorOnTertiaryContainer
            }
            else -> {
                title.setText(R.string.status_shizuku_connected)
                detail.setText(R.string.status_detail_connected)
                button.setText(R.string.btn_check)
                bgAttr = com.google.android.material.R.attr.colorPrimaryContainer
                fgAttr = com.google.android.material.R.attr.colorOnPrimaryContainer
            }
        }
        val fg = themeColor(fgAttr)
        card.setCardBackgroundColor(themeColor(bgAttr))
        title.setTextColor(fg)
        detail.setTextColor(fg)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ShizukuManager.REQUEST_CODE) {
            manager.refresh()
            updateStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        manager.removeListeners()
    }
}
