package de.minipc.appopsshizuku

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
import de.minipc.appopsshizuku.appops.AppOpsController
import de.minipc.appopsshizuku.appops.ChangeLog
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.NetControl
import de.minipc.appopsshizuku.appops.NetStore
import de.minipc.appopsshizuku.appops.NetSwitch
import de.minipc.appopsshizuku.appops.PrivacyOps
import de.minipc.appopsshizuku.appops.PrivacyProfileStore
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import java.util.concurrent.Executors

/**
 * Zeigt das gemeinsame Aenderungsprotokoll. Op- und Netz-Eintraege lassen sich einzeln rueckgaengig
 * machen; der Eintrag verschwindet erst, wenn das Zuruecksetzen zurueckgelesen bestaetigt ist.
 */
class ChangeLogActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var backend: ShellBackend
    private var entries: List<ChangeLog.Entry> = emptyList()
    private val adapter = Adapter()

    private val shizukuOn: Boolean get() = backend !is NoopBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_change_log)
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
        findViewById<RecyclerView>(R.id.logList).apply {
            layoutManager = LinearLayoutManager(this@ChangeLogActivity)
            adapter = this@ChangeLogActivity.adapter
        }
        reload()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun reload() {
        entries = ChangeLog.all(this)
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.logEmpty).visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun undo(entry: ChangeLog.Entry) {
        if (!shizukuOn) {
            Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        executor.execute {
            val ok = runCatching {
                when (entry.type) {
                    ChangeLog.Type.OP -> {
                        val op = PrivacyOps.PRIVACY_OPS.firstOrNull { it.opName == entry.opName } ?: return@runCatching false
                        val mode = entry.prevMode ?: "default"
                        AppOpsController(backend).setMode(entry.packageName!!, op, mode).exitCode == 0.also {
                            runCatching { PrivacyProfileStore(this).setOpMode(entry.packageName, op.opName, mode) }
                        }
                    }
                    ChangeLog.Type.NET -> {
                        val sw = NetSwitch.values().firstOrNull { it.name == entry.netSwitch } ?: return@runCatching false
                        val uid = packageManager.getApplicationInfo(entry.packageName!!, 0).uid
                        val on = entry.prevOn == true
                        NetControl(backend).set(sw, entry.packageName, uid, on).also {
                            if (it) NetStore(this).mark(entry.packageName, sw, on)
                        }
                    }
                    ChangeLog.Type.INFO -> false
                }
            }.getOrDefault(false)
            if (ok) ChangeLog.remove(this, entry.id)
            handler.post {
                Toast.makeText(this, if (ok) R.string.log_undone else R.string.log_undo_failed, Toast.LENGTH_LONG).show()
                reload()
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.changeLabel)
            val time: TextView = v.findViewById(R.id.changeTime)
            val undo: MaterialButton = v.findViewById(R.id.changeUndo)
        }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_change, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = entries[position]
            h.label.text = e.label
            h.time.text = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            h.undo.visibility = if (e.undoable) View.VISIBLE else View.GONE
            h.undo.setOnClickListener { undo(e) }
        }
    }
}
