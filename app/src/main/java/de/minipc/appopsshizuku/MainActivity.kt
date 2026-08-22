package de.minipc.appopsshizuku

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.shizuku.ShizukuManager
import de.minipc.appopsshizuku.ui.AppItem
import de.minipc.appopsshizuku.ui.AppListAdapter
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
        const val SORT_NAME = 0
        const val SORT_INSTALL = 1
        const val SORT_UPDATE = 2
        const val FILTER_ALL = 0
        const val FILTER_BLOCKED = 1
    }

    private lateinit var manager: ShizukuManager
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)

    /** Alle Apps (ungefiltert, Basis fuer Filter+Sortierung). Nur per Referenz-Tausch aendern. */
    private var allApps: List<AppItem> = emptyList()
    private lateinit var adapter: AppListAdapter

    private var sortMode = SORT_NAME
    private var query = ""
    private var filterMode = FILTER_ALL

    /** Cache: Paketname -> hat mind. eine blockierte Op (ignore/deny auf UID-Ebene) */
    private val blockedCache = mutableMapOf<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
                Intent(this, AppOpsActivity::class.java)
                    .putExtra(AppOpsActivity.EXTRA_PACKAGE, app.packageName)
            )
        }
        findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = this@MainActivity.adapter
        }

        findViewById<ChipGroup>(R.id.filterChipGroup).setOnCheckedStateChangeListener { _, checkedIds ->
            filterMode = if (R.id.chipBlocked in checkedIds) FILTER_BLOCKED else FILTER_ALL
            if (filterMode == FILTER_BLOCKED && blockedCache.isEmpty()) {
                scanBlockedState()
            } else {
                applyFilterAndSort()
            }
        }

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
                val icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
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
                    val icon = try {
                        pm.getApplicationIcon(pkg)
                    } catch (e: Exception) {
                        android.util.Log.d("AppOpsShizuku", "icon fail $pkg: ${e.message}")
                        null
                    }
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
                val icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (e: Exception) {
                    null
                }
                loaded.add(
                    AppItem(
                        pkg,
                        getString(R.string.stealth_label, label),
                        icon ?: resources.getDrawable(android.R.drawable.sym_def_app_icon, null),
                        0L, 0L
                    )
                )
            }

            handler.post {
                // Atomare Zuweisung: keine Mutation, kein Datenrennen mit
                // scanBlockedState()/applyFilterAndSort()
                allApps = loaded
                applyFilterAndSort()
            }
        }
    }

    /** Live-Filter (Query) + Filter-Chip + Sortierung auf allApps. */
    private fun applyFilterAndSort() {
        val q = query.lowercase()
        val filtered = allApps.filter { app ->
            when {
                q.isNotEmpty() && !app.label.lowercase().contains(q) && !app.packageName.lowercase().contains(q) -> false
                filterMode == FILTER_BLOCKED -> blockedCache[app.packageName] == true
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
            for (app in list) {
                val res = backend.exec("cmd", "appops", "get", app.packageName)
                val blocked = res.exitCode == 0 &&
                    Regex("Uid mode: [^:]*: (ignore|deny)").containsMatchIn(res.stdout)
                synchronized(blockedCache) {
                    blockedCache[app.packageName] = blocked
                }
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
            Toast.makeText(this, "Shizuku-App nicht gefunden (shizuku.rikka.app)", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        manager.refresh()
        updateStatus()
        // Liste neu laden: nach Rueckkehr aus Komponenten-Ansicht koennen Apps
        // deaktiviert/aktiviert worden sein
        loadApps()
    }

    private fun updateStatus() {
        val chip = findViewById<Chip>(R.id.statusChip)
        when {
            !manager.isConnected -> chip.text = getString(R.string.status_disconnected)
            !manager.hasPermission -> chip.text = getString(R.string.status_permission_denied)
            else -> chip.text = getString(R.string.status_connected)
        }
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
