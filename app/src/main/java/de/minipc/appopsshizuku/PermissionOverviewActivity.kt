package de.minipc.appopsshizuku

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import de.minipc.appopsshizuku.appops.AccessLog
import de.minipc.appopsshizuku.appops.AppOp
import de.minipc.appopsshizuku.appops.AppOpsController
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.OpEffect
import de.minipc.appopsshizuku.appops.PermissionOverview
import de.minipc.appopsshizuku.appops.PermissionOverview.Row
import de.minipc.appopsshizuku.appops.PrivacyProfileStore
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.ui.OpLabels
import de.minipc.appopsshizuku.ui.ToggleGuard
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import de.minipc.appopsshizuku.ui.lifecycleHandler
import de.minipc.appopsshizuku.ui.lifecycleExecutor

/**
 * "Nach Berechtigung": Eine Berechtigung waehlen, sehen, welche Apps sie haben (und wann sie sie
 * zuletzt benutzt haben), einzeln umschalten oder allen Nutzer-Apps auf einmal entziehen.
 *
 * Erteilt/entzogen bei Berechtigungs-Ops kommt lokal aus dem PackageManager (ohne Shizuku sichtbar);
 * Zugriffszeiten und reine AppOps (Zwischenablage, Hintergrund ...) brauchen Shizuku.
 * Nach jedem Schalten wird der echte Zustand zurueckgelesen.
 */
class PermissionOverviewActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "overview"
        private const val KEY_OP = "op"
        private const val THREADS = 4
    }

    private val handler = lifecycleHandler()
    private val executor = lifecycleExecutor()
    private lateinit var backend: ShellBackend
    private lateinit var controller: AppOpsController
    private lateinit var profileStore: PrivacyProfileStore
    private lateinit var ops: List<AppOp>
    private lateinit var selected: AppOp
    private var rows: List<Row> = emptyList()
    private val icons = HashMap<String, Drawable?>()
    private val adapter = RowAdapter()
    /** Laufender Ladevorgang; aeltere Ergebnisse (Op gewechselt) werden verworfen. */
    private var generation = 0

    private val shizukuOn: Boolean get() = backend !is NoopBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_permission_overview)
        de.minipc.appopsshizuku.ui.SpaceScreen.apply(this)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        backend = runCatching {
            ShizukuBackend().takeIf { it.isAvailable() && it.hasPermission() }
        }.getOrNull() ?: NoopBackend()
        controller = AppOpsController(backend)
        profileStore = PrivacyProfileStore(this)
        ops = OpLabels.localized(this)

        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_OP, "CAMERA")
        selected = ops.firstOrNull { it.opName == saved } ?: ops.first()

        val picker = findViewById<AutoCompleteTextView>(R.id.opPicker)
        picker.setAdapter(ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ops.map { it.label }))
        picker.setText(selected.label, false)
        picker.inputType = android.text.InputType.TYPE_NULL
        picker.keyListener = null
        picker.setOnItemClickListener { _, _, pos, _ ->
            selected = ops[pos]
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_OP, selected.opName).apply()
            load()
        }

        findViewById<RecyclerView>(R.id.overviewList).apply {
            layoutManager = LinearLayoutManager(this@PermissionOverviewActivity)
            adapter = this@PermissionOverviewActivity.adapter
        }
        findViewById<MaterialButton>(R.id.bulkBlockButton).setOnClickListener { confirmBulk() }
        load()
    }

    /** Rueckkehr aus der App-Detailseite: dort kann sich der Zustand geaendert haben. */
    override fun onRestart() {
        super.onRestart()
        load()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setLoading(on: Boolean) {
        findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun load() {
        val op = selected
        val gen = ++generation
        setLoading(true)
        rows = emptyList()
        adapter.notifyDataSetChanged()
        updateSummary()
        executor.execute {
            val result = readRows(op)
            handler.post {
                if (gen != generation || isFinishing) return@post
                rows = PermissionOverview.sort(result)
                adapter.notifyDataSetChanged()
                setLoading(false)
                updateSummary()
            }
        }
    }

    /** Alle Launcher-Apps (ohne uns), fuer Berechtigungs-Ops nur die, die sie anfordern. */
    private fun readRows(op: AppOp): List<Row> {
        val pm = packageManager
        val pkgs = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }.filter { it != packageName }.distinct()

        data class Base(val pkg: String, val label: String, val system: Boolean, val localMode: String?)
        val bases = pkgs.mapNotNull { pkg ->
            val pi = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()
                ?: return@mapNotNull null
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val local = op.permissionName?.let { grantedState(pi, it) ?: return@mapNotNull null }
            if (op.permissionName == null && !shizukuOn) return@mapNotNull null
            icons.getOrPut(pkg) { runCatching { ai.loadIcon(pm) }.getOrNull() }
            Base(pkg, ai.loadLabel(pm).toString(), (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0, local)
        }
        if (!shizukuOn) return bases.map { Row(it.pkg, it.label, it.system, it.localMode) }

        // Zugriffszeiten (und bei reinen AppOps der Modus) mit je EINER Abfrage pro App, 4 parallel
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            return pool.invokeAll(bases.map { b ->
                Callable {
                    val res = backend.exec("cmd", "appops", "get", b.pkg, op.opName)
                    val ok = res.exitCode == 0
                    val mode = b.localMode ?: if (ok) controller.parseMode(res.stdout, op) else null
                    Row(b.pkg, b.label, b.system, mode, if (ok) AccessLog.parse(res.stdout)[op.opName] else null)
                }
            }).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    /** "allow"/"deny" aus den Grant-Flags; null = App fordert die Berechtigung nicht an. */
    private fun grantedState(pi: PackageInfo, permission: String): String? {
        val idx = pi.requestedPermissions?.indexOf(permission) ?: -1
        if (idx < 0) return null
        val flags = pi.requestedPermissionsFlags?.getOrNull(idx) ?: return null
        return if ((flags and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) "allow" else "deny"
    }

    private fun readActual(op: AppOp, pkg: String): String? {
        val perm = op.permissionName
        if (perm != null) {
            val pi = runCatching { packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()
                ?: return null
            return grantedState(pi, perm)
        }
        return controller.getMode(pkg, op)
    }

    private fun updateSummary() {
        val text = findViewById<TextView>(R.id.overviewSummary)
        val bulk = findViewById<MaterialButton>(R.id.bulkBlockButton)
        val allowed = rows.count { it.allowed }
        text.text = when {
            selected.permissionName == null && !shizukuOn -> getString(R.string.overview_needs_shizuku)
            rows.isEmpty() -> getString(R.string.overview_empty)
            else -> getString(R.string.overview_summary, allowed, rows.size) +
                if (!shizukuOn) "\n" + getString(R.string.overview_readonly) else ""
        }
        val targets = PermissionOverview.bulkTargets(rows).size
        bulk.isEnabled = shizukuOn && targets > 0
        bulk.text = getString(R.string.overview_bulk_block, targets)
    }

    private fun confirmBulk() {
        val targets = PermissionOverview.bulkTargets(rows)
        if (targets.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.overview_bulk_title, selected.label))
            .setMessage(getString(R.string.overview_bulk_msg, targets.size, targets.joinToString(", ") { it.label }))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.overview_bulk_action) { _, _ -> applyBulk(targets) }
            .show()
    }

    private fun applyBulk(targets: List<Row>) {
        val op = selected
        setLoading(true)
        executor.execute {
            var failed = 0
            for (row in targets) if (!setAndVerify(op, row.packageName, allow = false)) failed++
            handler.post {
                setLoading(false)
                Toast.makeText(
                    this,
                    if (failed == 0) getString(R.string.overview_bulk_done, targets.size)
                    else getString(R.string.overview_bulk_partial, targets.size - failed, failed),
                    Toast.LENGTH_LONG
                ).show()
                load()
            }
        }
    }

    /** Setzen + zuruecklesen; Profil nur bei bestaetigtem Erfolg speichern. */
    private fun setAndVerify(op: AppOp, pkg: String, allow: Boolean): Boolean {
        val mode = PermissionOverview.toggleMode(op, allow)
        val res = controller.setMode(pkg, op, mode)
        if (res.exitCode != 0) return false
        val actual = readActual(op, pkg)
        val ok = OpEffect.isSetEffective(op.permissionName, mode, actual)
        if (ok) runCatching { profileStore.setOpMode(pkg, op.opName, mode) }
        return ok
    }

    private fun onToggle(position: Int, allow: Boolean) {
        val row = rows.getOrNull(position) ?: return
        val op = selected
        if (!shizukuOn) {
            Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_LONG).show()
            adapter.notifyItemChanged(position)
            return
        }
        executor.execute {
            val ok = setAndVerify(op, row.packageName, allow)
            val actual = readActual(op, row.packageName)
            handler.post {
                if (op != selected) return@post
                val idx = rows.indexOfFirst { it.packageName == row.packageName }
                if (idx < 0) return@post
                if (ok) runCatching {
                    de.minipc.appopsshizuku.appops.ChangeLog.recordOp(this, row.packageName, op, row.mode, actual ?: "default")
                }
                rows = rows.toMutableList().also { it[idx] = it[idx].copy(mode = actual) }
                adapter.notifyItemChanged(idx)
                updateSummary()
                if (!ok) {
                    Toast.makeText(this, getString(R.string.op_not_settable, op.label), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun detailText(row: Row): String {
        val parts = mutableListOf(
            getString(
                when {
                    row.mode == null -> R.string.overview_state_unknown
                    row.allowed -> R.string.overview_state_allowed
                    else -> R.string.overview_state_blocked
                }
            )
        )
        val now = System.currentTimeMillis()
        val a = row.access
        when {
            a?.running == true -> parts += getString(R.string.access_running)
            a?.lastAccessAgoMs != null -> parts += getString(
                R.string.access_last_used,
                DateUtils.getRelativeTimeSpanString(now - a.lastAccessAgoMs, now, DateUtils.MINUTE_IN_MILLIS)
            )
        }
        a?.lastRejectAgoMs?.let {
            parts += getString(
                R.string.access_last_rejected,
                DateUtils.getRelativeTimeSpanString(now - it, now, DateUtils.MINUTE_IN_MILLIS)
            )
        }
        if (row.isSystem) parts += getString(R.string.overview_system_app)
        return parts.joinToString(" · ")
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rowIcon)
            val label: TextView = v.findViewById(R.id.rowLabel)
            val detail: TextView = v.findViewById(R.id.rowDetail)
            val switch: MaterialSwitch = v.findViewById(R.id.rowSwitch)
            val guard = ToggleGuard()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_perm_app, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val row = rows[position]
            h.icon.setImageDrawable(icons[row.packageName])
            h.label.text = row.label
            h.detail.text = detailText(row)
            h.switch.contentDescription = getString(R.string.overview_switch_desc, selected.label, row.label)
            h.switch.isEnabled = row.mode != null
            h.switch.setOnCheckedChangeListener(null)
            h.guard.silently { h.switch.isChecked = row.allowed }
            h.switch.setOnCheckedChangeListener { _, checked ->
                h.guard.ifUser {
                    val pos = h.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onToggle(pos, checked)
                }
            }
            h.itemView.setOnClickListener {
                startActivity(
                    Intent(this@PermissionOverviewActivity, AppDetailActivity::class.java)
                        .putExtra(AppDetailActivity.EXTRA_PACKAGE, row.packageName)
                )
            }
        }
    }
}
