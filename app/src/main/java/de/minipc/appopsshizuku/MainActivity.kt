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
import de.minipc.appopsshizuku.ui.lifecycleHandler
import de.minipc.appopsshizuku.ui.lifecycleExecutor

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

        private const val DRIFT_INTERVAL_MS = 10 * 60 * 1000L
        private const val PANEL_APPS = 0
        private const val PANEL_TOOLS = 1
        private const val PANEL_SETTINGS = 2

        /** Gleichzeitige Shell-Aufrufe beim Blockiert-Scan. */
    }

    private lateinit var manager: ShizukuManager
    private lateinit var profileBackup: ProfileBackup
    private val handler = lifecycleHandler()
    private val executor = lifecycleExecutor(4)

    /** Speichern-Dialog fuer die Sicherung: der Nutzer waehlt den Ort (z. B. Downloads). */
    private val exportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportProfiles(uri)
        }

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

    /** Cache: Paketname -> Kartenfarbe (Icon-Analyse nur einmal je App). */
    private val cardColorCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

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

        // Edge-to-Edge: der Sternenhimmel laeuft hinter die Systemleisten; Inhalt und die
        // schwebende Navigation bekommen die Abstaende, damit nichts verdeckt wird.
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.contentColumn)
                .updatePadding(left = bars.left, top = bars.top, right = bars.right)
            findViewById<View>(R.id.pillNav).let { nav ->
                (nav.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin =
                    bars.bottom + (16 * density).toInt()
                nav.requestLayout()
            }
            val listBottom = bars.bottom + (120 * density).toInt()
            listOf(R.id.appList, R.id.panelTools, R.id.panelSettings).forEach {
                findViewById<View>(it).updatePadding(bottom = listBottom)
            }
            insets
        }
        setupHeader()
        setupNavigation()
        setupTools()
        setupSettings()

        profileBackup = ProfileBackup(this)

        // Feature 3: Bildschirm-Aus-Waechter wieder starten, falls Apps den Haken haben
        // (nach Neustart/Prozess-Ende). Hier ist die App sicher im Vordergrund.
        LifecycleScreenService.sync(this)

        // Direkt-Import ohne Dateidialog: am start ... --es import_file <pfad>
        // (praktisch fuer Skripte/Tasker und fuer Tests; der System-Dialog ist headless unzuverlaessig)
        // NUR in Debug-Builds: MainActivity ist exportiert, ein Release-Build darf sich von fremden Apps
        // keinen Import ohne Rückfrage auslösen lassen.
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        intent?.getStringExtra(EXTRA_IMPORT_FILE)?.takeIf { debuggable && it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.isFile) {
                importProfiles(Uri.fromFile(file), confirm = false)
            } else {
                setBackupStatus(
                    getString(R.string.backup_status_import_fail, "Datei nicht lesbar: $path"), true
                )
            }
        }

        // Nur eine FEHLER-Meldung bleibt ueber einen Neustart stehen (bis man sie antippt);
        // Erfolgsmeldungen verschwinden von selbst und werden nicht wieder angezeigt.
        getSharedPreferences(PREFS_BACKUP, MODE_PRIVATE).let { prefs ->
            val last = prefs.getString(KEY_BACKUP_TEXT, null)
            if (!last.isNullOrBlank() && prefs.getBoolean(KEY_BACKUP_ERROR, false)) {
                showBackupStatus(last, isError = true, onApps = true)
            } else if (!last.isNullOrBlank()) {
                prefs.edit().clear().apply()
            }
        }
        listOf(R.id.backupStatus, R.id.settingsBackupStatus).forEach { id ->
            findViewById<View>(id)?.setOnClickListener { dismissBackupStatus() }
        }
        // Nach einem Absturz einmal anbieten, den Fehlerbericht anzusehen und zu teilen
        if (savedInstanceState == null && ErrorLog.consumeCrashFlag(this)) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.errlog_crash_title)
                .setMessage(R.string.errlog_crash_msg)
                .setNegativeButton(R.string.errlog_crash_later, null)
                .setPositiveButton(R.string.errlog_crash_open) { _, _ ->
                    startActivity(Intent(this, DiagnosticsActivity::class.java))
                }
                .show()
        }
        // Die Liste haengt vom Shizuku-Zustand ab (deaktivierte Apps): loadApps() baut nur neu, wenn sich die Signatur aendert
        manager = ShizukuManager(onStateChanged = { runOnUiThread { updateStatus(); loadApps() } })
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

        adapter = AppListAdapter { app ->
            if (app.removed || app.hidden) {
                confirmRestore(app)
                return@AppListAdapter
            }
            startActivity(
                Intent(this, AppDetailActivity::class.java)
                    .putExtra(AppDetailActivity.EXTRA_PACKAGE, app.packageName)
            )
        }
        findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = this@MainActivity.adapter
        }

        findViewById<MaterialButton>(R.id.driftRestore).setOnClickListener { restoreDrift() }

        // KEIN loadApps() hier: onResume laedt direkt nach onCreate — doppeltes
        // Laden wuerde parallel laufen (race auf allApps)
        updateStatus()
    }

    // ------------------------------------------------------------------ Kopfzeile

    /** Entfernte App wiederherstellen (cmd package install-existing), mit Rueckfrage. */
    private fun confirmRestore(app: AppItem) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_user_title)
            .setMessage(getString(R.string.restore_user_msg, app.label))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.restore_user_action) { _, _ ->
                if (app.hidden) {
                    // Versteckt als Geraeteinhaber: zeigen geht ohne Shizuku
                    executor.execute {
                        val r = DeviceOwnerManager.setHidden(this, app.packageName, false)
                        handler.post {
                            Toast.makeText(
                                this,
                                if (r.ok) getString(R.string.restore_user_done)
                                else getString(R.string.remove_user_failed, r.reason ?: "?"),
                                Toast.LENGTH_LONG
                            ).show()
                            loadApps(force = true)
                        }
                    }
                    return@setPositiveButton
                }
                if (!(manager.isConnected && manager.hasPermission)) {
                    Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                executor.execute {
                    val r = UserRemovalManager.restore(this, ShizukuBackend(), app.packageName)
                    if (r.ok) runCatching {
                        de.minipc.appopsshizuku.appops.ChangeLog.recordInfo(
                            this, getString(R.string.log_info_restored_user, app.packageName)
                        )
                    }
                    handler.post {
                        Toast.makeText(
                            this,
                            if (r.ok) getString(R.string.restore_user_done)
                            else getString(R.string.remove_user_failed, r.reason ?: "?"),
                            Toast.LENGTH_LONG
                        ).show()
                        loadApps(force = true)
                    }
                }
            }
            .show()
    }

    private fun setupHeader() {
        // Erster Buchstabe im Verlauf (Petrol -> Cyan), Rest in der Glas-Schriftfarbe
        val title = findViewById<TextView>(R.id.headerTitle)
        val text = android.text.SpannableString(title.text)
        if (text.isNotEmpty()) {
            text.setSpan(
                de.minipc.appopsshizuku.ui.GradientSpan(0xFF1FB5C9.toInt(), 0xFF3D7BE0.toInt()),
                0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        title.text = text

        findViewById<View>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, ChangeLogActivity::class.java))
        }
        findViewById<View>(R.id.btnTune).setOnClickListener { showTuneMenu(it) }
        findViewById<View>(R.id.btnSearch).setOnClickListener { toggleSearch() }
        findViewById<TextView>(R.id.filterPill).setOnClickListener { setFilter(FILTER_ALL) }

        findViewById<EditText>(R.id.searchInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilterAndSort()
            }
        })
    }

    private fun toggleSearch() {
        val layout = findViewById<View>(R.id.searchLayout)
        val input = findViewById<EditText>(R.id.searchInput)
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (layout.visibility == View.VISIBLE) {
            input.setText("")
            layout.visibility = View.GONE
            imm?.hideSoftInputFromWindow(input.windowToken, 0)
            setHeaderButtonActive(R.id.btnSearch, false)
        } else {
            showPanel(PANEL_APPS)
            layout.visibility = View.VISIBLE
            input.requestFocus()
            imm?.showSoftInput(input, 0)
            setHeaderButtonActive(R.id.btnSearch, true)
        }
    }

    private fun setHeaderButtonActive(id: Int, active: Boolean) {
        val b = findViewById<android.widget.ImageButton>(id)
        b.setBackgroundResource(if (active) R.drawable.bg_glass_circle_selected else R.drawable.bg_glass_circle)
        b.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(this, if (active) R.color.on_pill_selected else R.color.on_glass)
        )
    }

    /** Sortieren, Filtern und Neu laden in einem Menue (ersetzt Dropdown und Filter-Chips). */
    private fun showTuneMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        val m = popup.menu
        val sorts = resources.getStringArray(R.array.sort_options)
        val sortGroup = 1
        val filterGroup = 2
        sorts.forEachIndexed { i, label -> m.add(sortGroup, 100 + i, i, label).isChecked = i == sortMode }
        m.setGroupCheckable(sortGroup, true, true)
        val filters = listOf(
            FILTER_ALL to R.string.filter_all,
            FILTER_BLOCKED to R.string.filter_blocked,
            FILTER_CHANGED to R.string.filter_changed
        )
        filters.forEachIndexed { i, (mode, res) ->
            m.add(filterGroup, 200 + mode, 10 + i, res).isChecked = mode == filterMode
        }
        m.setGroupCheckable(filterGroup, true, true)
        m.add(3, 300, 20, R.string.btn_refresh)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                in 100..199 -> { sortMode = item.itemId - 100; applyFilterAndSort() }
                in 200..299 -> setFilter(item.itemId - 200)
                300 -> loadApps(force = true)
            }
            true
        }
        popup.show()
    }

    private fun setFilter(mode: Int) {
        filterMode = mode
        val pill = findViewById<TextView>(R.id.filterPill)
        if (mode == FILTER_ALL) {
            pill.visibility = View.GONE
        } else {
            val name = getString(if (mode == FILTER_BLOCKED) R.string.filter_blocked else R.string.filter_changed)
            pill.text = getString(R.string.filter_active, name)
            pill.visibility = View.VISIBLE
        }
        setHeaderButtonActive(R.id.btnTune, mode != FILTER_ALL)
        applyFilterAndSort()
    }

    // ------------------------------------------------------------------ Navigation

    private var currentPanel = PANEL_APPS

    private fun setupNavigation() {
        findViewById<View>(R.id.navApps).setOnClickListener { showPanel(PANEL_APPS) }
        findViewById<View>(R.id.navTools).setOnClickListener { showPanel(PANEL_TOOLS) }
        findViewById<View>(R.id.navSettings).setOnClickListener { showPanel(PANEL_SETTINGS) }
        showPanel(PANEL_APPS)
    }

    private fun showPanel(panel: Int) {
        currentPanel = panel
        findViewById<View>(R.id.panelApps).visibility = if (panel == PANEL_APPS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panelTools).visibility = if (panel == PANEL_TOOLS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panelSettings).visibility = if (panel == PANEL_SETTINGS) View.VISIBLE else View.GONE
        // Suche/Filter gehoeren nur zur App-Liste
        if (panel != PANEL_APPS) findViewById<View>(R.id.searchLayout).visibility = View.GONE
        findViewById<View>(R.id.filterPill).visibility =
            if (panel == PANEL_APPS && filterMode != FILTER_ALL) View.VISIBLE else View.GONE
        val items = listOf(
            Triple(R.id.navApps, R.id.navAppsIcon, R.id.navAppsLabel),
            Triple(R.id.navTools, R.id.navToolsIcon, R.id.navToolsLabel),
            Triple(R.id.navSettings, R.id.navSettingsIcon, R.id.navSettingsLabel)
        )
        items.forEachIndexed { i, (box, icon, label) ->
            val selected = i == panel
            findViewById<View>(box).setBackgroundResource(if (selected) R.drawable.bg_pill_selected else 0)
            findViewById<View>(box).isSelected = selected
            val color = androidx.core.content.ContextCompat.getColor(
                this, if (selected) R.color.on_pill_selected else R.color.on_glass
            )
            findViewById<android.widget.ImageView>(icon).imageTintList =
                android.content.res.ColorStateList.valueOf(color)
            findViewById<TextView>(label).setTextColor(color)
        }
    }

    // ------------------------------------------------------------------ Werkzeuge

    private data class Tool(val title: Int, val desc: Int, val icon: Int, val seed: Int, val target: Class<*>)

    private fun setupTools() {
        val tools = listOf(
            Tool(R.string.menu_by_permission, R.string.tool_desc_by_permission, R.drawable.ic_list_check, 0xFF2F7DD6.toInt(), PermissionOverviewActivity::class.java),
            Tool(R.string.menu_net_usage, R.string.tool_desc_net_usage, R.drawable.ic_network, 0xFF1F9E8F.toInt(), NetUsageActivity::class.java),
            Tool(R.string.menu_activity, R.string.tool_desc_activity, R.drawable.ic_pulse, 0xFFD9822B.toInt(), ActivityScanActivity::class.java),
            Tool(R.string.menu_auto_rules, R.string.tool_desc_auto_rules, R.drawable.ic_rule, 0xFF7B5CD6.toInt(), AutoRulesActivity::class.java),
            Tool(R.string.menu_system, R.string.tool_desc_system, R.drawable.ic_shield, 0xFFC0392B.toInt(), SystemActivity::class.java),
            Tool(R.string.menu_work_profile, R.string.tool_desc_work_profile, R.drawable.ic_work, 0xFF3E8E41.toInt(), WorkProfileActivity::class.java),
            Tool(R.string.menu_change_log, R.string.tool_desc_change_log, R.drawable.ic_history, 0xFF2E6FA0.toInt(), ChangeLogActivity::class.java),
            Tool(R.string.diag_title, R.string.tool_desc_diag, R.drawable.ic_tune, 0xFF607D8B.toInt(), DiagnosticsActivity::class.java)
        )
        val container = findViewById<android.widget.LinearLayout>(R.id.toolsContainer)
        val inflater = layoutInflater
        for (t in tools) {
            val v = inflater.inflate(R.layout.item_tool, container, false)
            v.background = AppListAdapter.gradient(v, de.minipc.appopsshizuku.ui.CardColors.cardStart(t.seed))
            v.findViewById<android.widget.ImageView>(R.id.toolIcon).setImageResource(t.icon)
            v.findViewById<TextView>(R.id.toolTitle).setText(t.title)
            v.findViewById<TextView>(R.id.toolDesc).setText(t.desc)
            v.setOnClickListener { startActivity(Intent(this, t.target)) }
            container.addView(v)
        }
    }

    // ------------------------------------------------------------------ Einstellungen

    private fun setupSettings() {
        val quarantine = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.settingQuarantine)
        quarantine.isChecked = de.minipc.appopsshizuku.appops.QuarantineStore(this).enabled
        quarantine.setOnClickListener { toggleQuarantine(quarantine) }

        val dynamic = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.settingDynamicColor)
        if (DynamicColors.isDynamicColorAvailable()) {
            dynamic.isChecked = DesignSettings.isDynamicColorEnabled(this)
            dynamic.setOnClickListener {
                DesignSettings.setDynamicColorEnabled(this, dynamic.isChecked)
                Toast.makeText(
                    this,
                    if (dynamic.isChecked) R.string.dynamic_color_on else R.string.dynamic_color_off,
                    Toast.LENGTH_SHORT
                ).show()
                recreate() // Theme wird nur beim Erzeugen der Activity gesetzt
            }
        } else {
            dynamic.isEnabled = false
            dynamic.isChecked = false
            dynamic.setText(R.string.menu_dynamic_color_unavailable)
        }
        findViewById<View>(R.id.settingExport).setOnClickListener {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
            exportPicker.launch("appopsshizuku-backup-$day.json")
        }
        findViewById<View>(R.id.settingImport).setOnClickListener {
            importPicker.launch(arrayOf("application/json", "text/*", "*/*"))
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        findViewById<TextView>(R.id.settingsAbout).text = getString(R.string.settings_about, version ?: "?")
        findViewById<View>(R.id.shizukuHint).setOnClickListener { showPanel(PANEL_SETTINGS) }
        findViewById<View>(R.id.standingButton).setOnClickListener { toggleStanding() }
        renderStanding()
        findViewById<View>(R.id.ownerButton).setOnClickListener { onOwnerButton() }
        renderOwner()
    }

    private fun renderOwner() {
        val owner = DeviceOwnerManager.isOwner(this)
        findViewById<TextView>(R.id.ownerStatus).setText(if (owner) R.string.owner_on else R.string.owner_off)
        findViewById<MaterialButton>(R.id.ownerButton).setText(if (owner) R.string.owner_release else R.string.owner_setup)
    }

    /** Geraeteinhaber einrichten (mit Erklaerung der Vorbedingungen) oder abgeben. */
    private fun onOwnerButton() {
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        if (DeviceOwnerManager.isOwner(this)) {
            dlg.setTitle(R.string.owner_release)
                .setMessage(R.string.owner_release_msg)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.owner_release) { _, _ ->
                    executor.execute {
                        val ok = DeviceOwnerManager.release(this)
                        handler.post {
                            renderOwner(); loadApps(force = true)
                            Toast.makeText(this, if (ok) R.string.owner_released else R.string.standing_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }.show()
            return
        }
        dlg.setTitle(R.string.owner_setup)
            .setMessage(R.string.owner_setup_msg)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.owner_setup_action) { _, _ ->
                if (!(manager.isConnected && manager.hasPermission)) {
                    Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                executor.execute {
                    val r = DeviceOwnerManager.setup(this, ShizukuBackend())
                    handler.post {
                        renderOwner()
                        val hint = getString(
                            when (r.result) {
                                de.minipc.appopsshizuku.appops.DeviceOwner.SetupResult.OK -> R.string.owner_setup_ok
                                de.minipc.appopsshizuku.appops.DeviceOwner.SetupResult.ACCOUNTS -> R.string.owner_setup_accounts
                                de.minipc.appopsshizuku.appops.DeviceOwner.SetupResult.USERS -> R.string.owner_setup_users
                                de.minipc.appopsshizuku.appops.DeviceOwner.SetupResult.ALREADY_SET -> R.string.owner_setup_already
                                de.minipc.appopsshizuku.appops.DeviceOwner.SetupResult.OTHER -> R.string.owner_setup_other
                            }
                        )
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.owner_title)
                            .setMessage(if (r.result == de.minipc.appopsshizuku.appops.DeviceOwner.SetupResult.OK) hint
                                else hint + "\n\n" + getString(R.string.owner_device_said, r.raw.take(400)))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }.show()
    }

    private fun renderStanding() {
        val has = StandingAccess.has(this)
        findViewById<TextView>(R.id.standingStatus).setText(
            if (has) R.string.standing_on else R.string.standing_off
        )
        findViewById<MaterialButton>(R.id.standingButton).setText(
            if (has) R.string.standing_revoke else R.string.standing_grant
        )
    }

    /** Dauerrecht per Shizuku erteilen bzw. wieder entziehen; Ergebnis wird zurueckgelesen. */
    private fun toggleStanding() {
        if (!(manager.isConnected && manager.hasPermission)) {
            Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val grant = !StandingAccess.has(this)
        executor.execute {
            val b = ShizukuBackend()
            val ok = if (grant) StandingAccess.grant(this, b) else StandingAccess.revoke(this, b)
            handler.post {
                renderStanding()
                Toast.makeText(
                    this,
                    when {
                        !ok -> R.string.standing_failed
                        grant -> R.string.standing_granted
                        else -> R.string.standing_revoked
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Liste neu laden. Die teure Basisliste (App-Namen, Icons, Installationszeiten, `pm list packages -d`) wird nur
     * neu gebaut, wenn sich etwas geaendert hat - Paket-Ereignis, Shizuku-Zustand oder eine eigene Ablage
     * (entfernt/versteckt/getarnt) -, die Status-Symbole kommen dagegen bei JEDEM Aufruf frisch aus den Speichern
     * (sie aendern sich in den Detailseiten). Eine Generationsnummer verwirft veraltete Ergebnisse.
     * [force] erzwingt den Neuaufbau (z. B. nach Wiederherstellen oder Geraeteinhaber-Setup).
     */
    private fun loadApps(force: Boolean = false) {
        val gen = loadGeneration.incrementAndGet()
        if (force) appsDirty.set(true)
        executor.execute {
            val signature = baseSignature()
            if (signature != baseSignatureBuilt) appsDirty.set(true)
            val needBase = appsDirty.getAndSet(false) || baseApps == null
            val base = if (needBase) buildBaseApps().also {
                baseApps = it
                baseSignatureBuilt = signature
            } else baseApps!!

            // Status-Symbole aus den eigenen Speichern (kein Shell-Aufruf)
            val withBadges = base.map {
                it.copy(
                    badges = badgeText(it.packageName),
                    cardColor = cardColorCache.getOrPut(it.packageName) {
                        de.minipc.appopsshizuku.ui.CardColors.cardStart(de.minipc.appopsshizuku.ui.IconColor.of(it.icon))
                    }
                )
            }

            handler.post {
                // Nur das neueste Ergebnis zaehlt; Atomare Zuweisung: keine Mutation, kein Datenrennen
                // mit scanBlockedState()/applyFilterAndSort()
                if (gen != loadGeneration.get()) return@post
                allApps = withBadges
                applyFilterAndSort()
            }
        }
    }

    /** Zuletzt gebaute Basisliste (ohne Status-Symbole) und der Stand der Ablagen, zu dem sie gehoert. */
    @Volatile private var baseApps: List<AppItem>? = null
    @Volatile private var baseSignatureBuilt: Int? = null
    private val appsDirty = java.util.concurrent.atomic.AtomicBoolean(true)
    private val loadGeneration = java.util.concurrent.atomic.AtomicInteger()

    /** Aendert sich, wenn eine eigene Ablage die Liste beeinflusst oder Shizuku (Liste der deaktivierten Apps) wechselt. */
    private fun baseSignature(): Int = listOf(
        UserRemovalManager.removedApps(this).map { it.packageName }.sorted(),
        de.minipc.appopsshizuku.appops.HiddenStore(this).all().sorted(),
        getSharedPreferences("stealth", MODE_PRIVATE).all.filterValues { it == true }.keys.sorted(),
        manager.isConnected && manager.hasPermission
    ).hashCode()

    /** Systemereignis "Paket installiert/entfernt/geaendert" (auch deaktiviert/aktiviert): Basisliste veraltet. */
    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            appsDirty.set(true)
        }
    }
    private var packageReceiverRegistered = false

    override fun onStart() {
        super.onStart()
        if (!packageReceiverRegistered) {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this, packageReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            packageReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (packageReceiverRegistered) {
            runCatching { unregisterReceiver(packageReceiver) }
            packageReceiverRegistered = false
        }
        super.onStop()
    }

    private fun buildBaseApps(): List<AppItem> {
        run {
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
                val disabled = if (res.exitCode == 0 && !res.stderr.contains("Exception", true) && !res.stdout.contains("Exception", true)) {
                    Regex("package:([\\w.]+)").findAll(res.stdout).map { it.groupValues[1] }
                        .filter { it != packageName && it !in seen }
                        .toList()
                } else emptyList()
                for (pkg in disabled) {
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
                            getString(R.string.app_disabled_label, label),
                            icon ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, null),
                            0L, 0L
                        )
                    )
                }
            }

            // Fuer diesen Nutzer entfernte Apps (pm uninstall --user 0): APK liegt noch im System,
            // Tipp auf die Karte stellt sie wieder her
            for (ai in UserRemovalManager.removedApps(this)) {
                val pkg = ai.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg)
                val icon = runCatching { ai.loadIcon(pm) }.getOrNull()
                    ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, null)
                loaded.add(AppItem(pkg, getString(R.string.app_removed_label, label), icon, 0L, 0L, removed = true))
            }

            // Als Geraeteinhaber versteckte Apps
            for (pkg in de.minipc.appopsshizuku.appops.HiddenStore(this).all()) {
                if (pkg == packageName || !seen.add(pkg)) continue
                @Suppress("DEPRECATION")
                val ai = runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.getOrNull()
                val label = ai?.let { runCatching { it.loadLabel(pm).toString() }.getOrNull() } ?: pkg
                val icon = ai?.let { runCatching { it.loadIcon(pm) }.getOrNull() }
                    ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, null)
                loaded.add(AppItem(pkg, getString(R.string.app_hidden_label, label), icon, 0L, 0L, hidden = true))
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

            return loaded
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
                setBackupStatus(
                    getString(R.string.quarantine_report, total, r.failed.size), r.failed.isNotEmpty(), onApps = true
                )
                loadApps()
            }
        }
    }

    private fun toggleQuarantine(item: com.google.android.material.materialswitch.MaterialSwitch) {
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
            .setNegativeButton(R.string.btn_cancel) { _, _ -> item.isChecked = false }
            .setOnCancelListener { item.isChecked = store.enabled }
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
                filterMode == FILTER_BLOCKED -> isBlockedByUs(app.packageName)
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

    /**
     * Filter "Blockiert": dieselbe Grundlage wie die Statuszeile der Karte ("N blockiert") -
     * Berechtigungen, die in dieser App auf Ignorieren/Verweigern gestellt wurden.
     * Frueher wurde jede App per `cmd appops get` abgefragt und jede Zeile "Uid mode: X: ignore"
     * gezaehlt. Die setzt Android aber selbst fuer jede nicht erteilte Berechtigung, deshalb
     * erschien fast jede App als blockiert, auch solche mit "Unveraendert".
     */
    private fun isBlockedByUs(pkg: String): Boolean = runCatching {
        profileStoreForBadges.getProfile(pkg).values.any { it == "deny" || it == "ignore" }
    }.getOrDefault(false)

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
                    R.string.shizuku_dialog_hint,
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
            // Nicht bei jedem Zurueckkehren: je reiner AppOp im Profil kostet das einen Shell-Aufruf
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastDriftCheck == 0L || now - lastDriftCheck > DRIFT_INTERVAL_MS) {
                lastDriftCheck = now
                checkDrift()
            }
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
    private var lastDriftCheck = 0L

    private fun checkDrift() {
        executor.execute {
            val backend = de.minipc.appopsshizuku.shizuku.ShizukuBackend()
            if (!runCatching { backend.isAvailable() && backend.hasPermission() }.getOrDefault(false)) return@execute
            val catalog = de.minipc.appopsshizuku.appops.PrivacyOps.PRIVACY_OPS.associateBy { it.opName }
            val stored = de.minipc.appopsshizuku.appops.PrivacyProfileStore(this).allProfiles()
            val wiu = de.minipc.appopsshizuku.appops.WhileInUseStore(this)
            val installed = packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
            val items = mutableListOf<de.minipc.appopsshizuku.appops.DriftCheck.Item>()
            for ((pkg, ops) in stored) {
                if (pkg !in installed) continue
                for ((opName, want) in ops) {
                    val op = catalog[opName] ?: continue
                    // "Nur waehrend Nutzung": erteilt ist hier gewollt, solange die App vorne ist
                    if (wiu.has(pkg, opName)) continue
                    // Unlesbar oder von der App gar nicht angefordert: nichts zu vergleichen
                    // (nach "alles blockieren" steht jede Op im Profil, auch nicht angeforderte)
                    val actual = runCatching { OpReadback.read(this, backend, pkg, op) }.getOrNull()
                    if (actual == null || actual == OpReadback.NOT_REQUESTED) continue
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

    /** Alle gespeicherten Profile als JSON in die gewaehlte Datei schreiben. */
    private fun exportProfiles(target: Uri) {
        // Sofort Rueckmeldung: das Einlesen aller Apps ueber Shizuku kann dauern
        val button = findViewById<View>(R.id.settingExport)
        button.isEnabled = false
        setBackupStatus(getString(R.string.backup_status_running), false, autoHide = false)
        executor.execute {
            val result = runCatching {
                // Einheitliches Backup: Berechtigungsprofile + Verhaltens-Einstellungen
                // (Standby-Stufe, Akku-Ausnahme, eingefroren, deaktiviert) in EINER Datei.
                val behavior = if (manager.isConnected && manager.hasPermission && allApps.isNotEmpty()) {
                    runCatching {
                        // Eingefroren direkt aus dem PackageManager lesen (kein Shell-Aufruf je App)
                        BehaviorBackup(ShizukuBackend()) { pkg ->
                            runCatching {
                                (packageManager.getApplicationInfo(pkg, 0).flags and
                                    android.content.pm.ApplicationInfo.FLAG_SUSPENDED) != 0
                            }.getOrNull()
                        }.collect(allApps.map { it.packageName })
                    }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
                profileBackup.export(target, behavior)
            }
            handler.post {
                button.isEnabled = true
                result.onSuccess { r ->
                    if (r.count == 0 && r.behaviorCount == 0 && r.lifecycleCount == 0 && r.extrasCount == 0) {
                        setBackupStatus(getString(R.string.profiles_export_empty), false)
                        Toast.makeText(this, R.string.profiles_export_empty, Toast.LENGTH_SHORT).show()
                    } else {
                        setBackupStatus(
                            getString(R.string.backup_status_export_ok, r.count, r.behaviorCount) +
                                "\n" + getString(R.string.backup_status_extras, r.extrasCount) +
                                "\n" + getString(R.string.backup_status_saved_as, r.path), false
                        )
                        Toast.makeText(
                            this,
                            getString(R.string.backup_toast_saved, r.path),
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

    /**
     * JSON-Datei (SAF) einlesen und prüfen. Eine Sicherung ist eine fremde Datei: erst nach der
     * Rückfrage ("Was steht drin?") wird etwas gespeichert oder auf das Gerät angewendet.
     */
    private fun importProfiles(uri: Uri, confirm: Boolean = true) {
        executor.execute {
            val parsed = runCatching { profileBackup.parse(uri) }
            handler.post {
                parsed.onSuccess { r -> if (confirm) confirmImport(r) else applyImport(r) }
                    .onFailure { e -> reportImportFailure(e) }
            }
        }
    }

    private fun confirmImport(r: ProfileBackup.ImportResult) {
        var msg = getString(
            R.string.import_preview_message,
            r.profiles.size, r.behavior.size, r.disableOrFreezeCount, r.extras.rules.size
        )
        if (r.dropped > 0) msg += "\n\n" + getString(R.string.import_preview_dropped, r.dropped)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_preview_title)
            .setMessage(msg)
            .setPositiveButton(R.string.import_preview_apply) { _, _ -> applyImport(r) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun reportImportFailure(e: Throwable) {
        val reason = e.message ?: e.javaClass.simpleName
        setBackupStatus(getString(R.string.backup_status_import_fail, reason), true)
        Toast.makeText(this, getString(R.string.profiles_import_fail, reason), Toast.LENGTH_LONG).show()
    }

    /** Geprüftes Ergebnis übernehmen und die Verhaltens-Einstellungen auf das Gerät anwenden (Hintergrund-Thread). */
    private fun applyImport(imported: ProfileBackup.ImportResult) {
        executor.execute {
            val stored = runCatching { profileBackup.commit(imported) }
            if (stored.isFailure) {
                handler.post { reportImportFailure(stored.exceptionOrNull()!!) }
                return@execute
            }
            val shizukuOk = manager.isConnected && manager.hasPermission
            // Ehrlich: die Zahl der Einstellungen, die das Gerät NICHT übernommen hat
            val errors: List<String>? = if (imported.behavior.isEmpty() || !shizukuOk) null else runCatching {
                val backup = BehaviorBackup(ShizukuBackend())
                imported.behavior.flatMap { (pkg, state) ->
                    backup.restoreToDevice(pkg, state, applicationContext.packageName)
                }
            }.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
            handler.post {
                val message = when {
                    imported.behavior.isEmpty() ->
                        getString(R.string.profiles_import_ok_global, imported.profiles.size)

                    errors == null ->
                        getString(R.string.profiles_import_ok_global, imported.profiles.size) + " – " +
                            getString(R.string.profiles_import_behavior_need_shizuku)

                    errors.isEmpty() -> {
                        setBackupStatus(
                            getString(R.string.backup_status_import_ok, imported.profiles.size, imported.behavior.size),
                            false
                        )
                        getString(R.string.profiles_import_ok_full, imported.profiles.size, imported.behavior.size)
                    }

                    else -> {
                        // Klartext-Grund mitschreiben: so ist der Fehler auch später lesbar
                        setBackupStatus(
                            getString(
                                R.string.backup_status_import_warn,
                                imported.profiles.size, errors.size, errors.take(3).joinToString("; ")
                            ), true
                        )
                        getString(R.string.profiles_import_ok_with_errors, imported.profiles.size, errors.size)
                    }
                }
                // Importierte Screen-Off-Haken wirken erst mit laufendem Dienst
                if (imported.lifecycle.isNotEmpty() || imported.extras.whileInUse.isNotEmpty()) {
                    LifecycleScreenService.sync(this)
                }
                if (imported.extras.rules.isNotEmpty()) {
                    executor.execute { runCatching { AutoRuleManager.evaluate(this) } }
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Laufende Nummer der Meldung: das verzoegerte Ausblenden trifft nur die eigene Meldung. */
    private var backupStatusToken = 0

    /**
     * Meldung zu Sicherung/Import/Quarantaene.
     * - Erfolg: erscheint unter den Knoepfen in den Einstellungen und verschwindet nach 8 s.
     * - Fehler: auch oben im Apps-Reiter, bleibt (auch nach Neustart), bis man sie antippt.
     * [onApps] zeigt eine Erfolgsmeldung ausnahmsweise auch im Apps-Reiter (Quarantaene-Bericht).
     */
    private fun setBackupStatus(
        message: String,
        isError: Boolean,
        autoHide: Boolean = !isError,
        onApps: Boolean = isError
    ) {
        val stamp = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        val text = "$message\n$stamp"
        getSharedPreferences(PREFS_BACKUP, MODE_PRIVATE).edit().apply {
            if (isError) putString(KEY_BACKUP_TEXT, text).putBoolean(KEY_BACKUP_ERROR, true) else clear()
        }.apply()
        showBackupStatus(text, isError, onApps)
        val token = ++backupStatusToken
        if (autoHide) handler.postDelayed({ if (token == backupStatusToken) hideBackupStatus() }, 8000)
    }

    private fun showBackupStatus(text: String, isError: Boolean, onApps: Boolean) {
        findViewById<TextView>(R.id.settingsBackupStatus)?.let { styleBackupStatus(it, text, isError) }
        findViewById<TextView>(R.id.backupStatus)?.let {
            if (onApps) styleBackupStatus(it, text, isError) else it.visibility = View.GONE
        }
    }

    private fun hideBackupStatus() {
        listOf(R.id.backupStatus, R.id.settingsBackupStatus).forEach { id ->
            findViewById<View>(id)?.visibility = View.GONE
        }
    }

    /** Antippen: Meldung weg, auch die gespeicherte Fehlermeldung. */
    private fun dismissBackupStatus() {
        backupStatusToken++
        getSharedPreferences(PREFS_BACKUP, MODE_PRIVATE).edit().clear().apply()
        hideBackupStatus()
    }

    private fun styleBackupStatus(view: TextView, text: String, isError: Boolean) {
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
        // Hinweis oben in der App-Liste, solange Shizuku nicht nutzbar ist
        findViewById<TextView>(R.id.shizukuHint).apply {
            val ok = manager.isConnected && manager.hasPermission
            visibility = if (ok) View.GONE else View.VISIBLE
            if (!ok) setText(R.string.shizuku_hint_pill)
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
