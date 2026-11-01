package de.minipc.appopsshizuku

import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.progressindicator.LinearProgressIndicator
import de.minipc.appopsshizuku.appops.ChangeLog
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.NetControl
import de.minipc.appopsshizuku.appops.NetStore
import de.minipc.appopsshizuku.appops.NetSwitch
import de.minipc.appopsshizuku.appops.NetUsage
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Netzverkehr pro App (letzte 7 Tage), Vordergrund und Hintergrund getrennt. Verdaechtige Apps
 * (viel Hintergrundverkehr, lange nicht geoeffnet) stehen oben. Direktaktion: Hintergrunddaten
 * sperren/freigeben; der Zeilen-Tipp oeffnet den Reiter Netz fuer alles Weitere.
 */
class NetUsageActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var backend: ShellBackend
    private lateinit var netStore: NetStore
    private var rows: List<NetUsage.Row> = emptyList()
    private val icons = HashMap<String, Drawable?>()
    private val uidOf = HashMap<String, Int>()
    private val adapter = Adapter()

    private val shizukuOn: Boolean get() = backend !is NoopBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_net_usage)
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
        netStore = NetStore(this)
        findViewById<RecyclerView>(R.id.netusageList).apply {
            layoutManager = LinearLayoutManager(this@NetUsageActivity)
            adapter = this@NetUsageActivity.adapter
        }
        load()
    }

    override fun onDestroy() { executor.shutdown(); super.onDestroy() }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun setLoading(on: Boolean) {
        findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun load() {
        setLoading(true)
        executor.execute {
            // Nutzungszugriff fuer die eigene App sichern (per Shizuku), sonst liefert der
            // NetworkStatsManager nur die eigenen Zahlen.
            if (shizukuOn) runCatching { OnDemandSupport.grantUsageAccess(this, backend) }
            val hasAccess = OnDemandSupport.hasUsageAccess(this)
            val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
            val perUid = if (hasAccess) NetStatsReader.perUid(this, since) else emptyMap()
            val lastUsed = if (hasAccess) lastUsedDaysMap(since) else emptyMap()
            val pm = packageManager
            val launcher = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).map { it.activityInfo.packageName }.filter { it != packageName }.distinct()
            val built = launcher.mapNotNull { pkg ->
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                val bytes = perUid[ai.uid] ?: return@mapNotNull null
                if (bytes.foreground + bytes.background <= 0) return@mapNotNull null
                uidOf[pkg] = ai.uid
                icons.getOrPut(pkg) { runCatching { ai.loadIcon(pm) }.getOrNull() }
                val marks = netStore.get(pkg)
                NetUsage.Row(
                    pkg, ai.loadLabel(pm).toString(), bytes.foreground, bytes.background,
                    lastUsedDays = lastUsed[pkg],
                    offline = NetSwitch.OFFLINE in marks,
                    bgBlocked = NetSwitch.BG_DATA in marks
                )
            }
            handler.post {
                rows = NetUsage.sort(built)
                adapter.notifyDataSetChanged()
                setLoading(false)
                findViewById<TextView>(R.id.netusageInfo).text = when {
                    !hasAccess -> getString(R.string.netusage_no_access)
                    rows.isEmpty() -> getString(R.string.netusage_empty)
                    else -> getString(R.string.netusage_info)
                }
            }
        }
    }

    private fun lastUsedDaysMap(since: Long): Map<String, Int> {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, since, System.currentTimeMillis())
        }.getOrNull().orEmpty()
        val now = System.currentTimeMillis()
        val lastByPkg = HashMap<String, Long>()
        for (u in stats) {
            val prev = lastByPkg[u.packageName] ?: 0L
            if (u.lastTimeUsed > prev) lastByPkg[u.packageName] = u.lastTimeUsed
        }
        return lastByPkg.mapValues { ((now - it.value) / TimeUnit.DAYS.toMillis(1)).toInt() }
    }

    private fun toggleBgData(position: Int) {
        val row = rows.getOrNull(position) ?: return
        if (!shizukuOn) { Toast.makeText(this, R.string.net_no_shizuku, Toast.LENGTH_LONG).show(); return }
        val uid = uidOf[row.packageName] ?: return
        val want = !row.bgBlocked
        executor.execute {
            val net = NetControl(backend)
            val ok = net.set(NetSwitch.BG_DATA, row.packageName, uid, want)
            if (ok) {
                netStore.mark(row.packageName, NetSwitch.BG_DATA, want)
                runCatching { ChangeLog.recordNet(this, row.packageName, NetSwitch.BG_DATA, !want, want) }
            }
            handler.post {
                if (ok) {
                    val idx = rows.indexOfFirst { it.packageName == row.packageName }
                    if (idx >= 0) {
                        rows = rows.toMutableList().also { it[idx] = it[idx].copy(bgBlocked = want) }
                        adapter.notifyItemChanged(idx)
                    }
                } else Toast.makeText(this, R.string.net_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun detail(row: NetUsage.Row): String {
        val parts = mutableListOf(
            getString(R.string.netusage_total, NetUsage.formatBytes(row.total)),
            getString(R.string.netusage_background, NetUsage.formatBytes(row.backgroundBytes))
        )
        row.lastUsedDays?.let {
            if (it >= 1) parts += resources.getQuantityString(R.plurals.netusage_unused_days, it, it)
        }
        if (row.offline) parts += getString(R.string.badge_offline)
        return parts.joinToString(" · ")
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.usageIcon)
            val label: TextView = v.findViewById(R.id.usageLabel)
            val detail: TextView = v.findViewById(R.id.usageDetail)
            val action: MaterialButton = v.findViewById(R.id.usageAction)
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_net_usage, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val row = rows[position]
            h.icon.setImageDrawable(icons[row.packageName])
            h.label.text = row.label
            h.detail.text = detail(row)
            h.action.setText(if (row.bgBlocked) R.string.netusage_unblock_bg else R.string.netusage_block_bg)
            h.action.isEnabled = shizukuOn && !row.offline
            h.action.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) toggleBgData(pos)
            }
            h.itemView.setOnClickListener {
                startActivity(
                    Intent(this@NetUsageActivity, AppDetailActivity::class.java)
                        .putExtra(AppDetailActivity.EXTRA_PACKAGE, row.packageName)
                        .putExtra(AppDetailActivity.EXTRA_TAB, 3)
                )
            }
        }
    }
}
