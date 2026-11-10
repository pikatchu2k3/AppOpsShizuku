package de.minipc.appopsshizuku

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import de.minipc.appopsshizuku.appops.ActivityScan
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.Diagnostics
import de.minipc.appopsshizuku.appops.Diagnostics.Result
import de.minipc.appopsshizuku.appops.Diagnostics.Status
import de.minipc.appopsshizuku.appops.WorkProfile
import de.minipc.appopsshizuku.appops.parseChainEnabled
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import java.util.concurrent.Executors

/**
 * Diagnose: prueft, welche Hebel auf DIESEM Geraet funktionieren. Jede Probe ist harmlos (nur lesen,
 * oder ein Befehl ohne Ziel). Das Ergebnis laesst sich als Text teilen - es enthaelt Modell und
 * Android-Version, aber keine App-Liste und keine persoenlichen Daten.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var lastReport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_diagnostics)
        de.minipc.appopsshizuku.ui.SpaceScreen.apply(this)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<MaterialButton>(R.id.diagRun).setOnClickListener { run() }
        findViewById<MaterialButton>(R.id.diagShare).setOnClickListener { share() }
        run()
    }

    override fun onDestroy() { executor.shutdown(); super.onDestroy() }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun run() {
        findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = View.VISIBLE
        findViewById<MaterialButton>(R.id.diagRun).isEnabled = false
        executor.execute {
            val lines = probe()
            val header = "App Ops Shizuku ${versionName()} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            val report = Diagnostics.report(header, lines)
            handler.post {
                lastReport = report
                render(header, lines)
                findViewById<LinearProgressIndicator>(R.id.loadingBar).visibility = View.GONE
                findViewById<MaterialButton>(R.id.diagRun).isEnabled = true
                findViewById<MaterialButton>(R.id.diagShare).isEnabled = true
            }
        }
    }

    private fun versionName() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun probe(): List<Pair<String, Result>> {
        val out = mutableListOf<Pair<String, Result>>()
        fun add(label: Int, id: String, status: Status, detail: String = "") {
            out += getString(label) to Result(id, status, detail)
        }
        val b: ShellBackend = ShizukuBackend()
        val shizuku = runCatching { b.isAvailable() && b.hasPermission() }.getOrDefault(false)
        add(R.string.diag_shizuku, "shizuku", if (shizuku) Status.OK else Status.FAIL)
        add(R.string.diag_standing, "standing", if (StandingAccess.has(this)) Status.OK else Status.FAIL)
        add(R.string.diag_usage, "usage", if (OnDemandSupport.hasUsageAccess(this)) Status.OK else Status.FAIL)
        if (!shizuku) return out

        fun text(vararg a: String) = b.exec(*a).let { it.stdout + "\n" + it.stderr }
        // Befehle ohne Ziel: antworten mit "kein Paket", aendern nichts
        add(R.string.diag_user_fixed, "user_fixed", Diagnostics.commandKnown(text("pm", "set-permission-flags")))
        add(R.string.diag_suspend_dialog, "suspend_dialog", Diagnostics.commandKnown(text("pm", "suspend", "--dialogMessage", "x")))
        // Netz (nur lesen)
        val chain = text("cmd", "connectivity", "get-chain3-enabled")
        add(
            R.string.diag_offline, "offline",
            if (parseChainEnabled(chain) != null) Status.OK else Diagnostics.commandKnown(chain).let { if (it == Status.OK) Status.UNKNOWN else it }
        )
        b.exec("cmd", "connectivity", "get-background-networking-enabled-for-uid", android.os.Process.myUid().toString()).let {
            add(R.string.diag_bg_data, "bg_data", Diagnostics.readable(it.exitCode, it.stdout + it.stderr))
        }
        b.exec("cmd", "netpolicy", "list", "restrict-background-blacklist").let {
            add(R.string.diag_bg_restrict, "bg_restrict", Diagnostics.readable(it.exitCode, it.stdout))
        }
        b.exec("cmd", "appops", "get", packageName).let {
            add(R.string.diag_appops, "appops", Diagnostics.readable(it.exitCode, it.stdout))
        }
        b.exec("settings", "get", "global", "private_dns_mode").let {
            add(R.string.diag_dns, "dns", Diagnostics.readable(it.exitCode, it.stdout))
        }
        b.exec("cmd", "role", "get-role-holders", "android.app.role.SMS").let {
            add(R.string.diag_roles, "roles", Diagnostics.readable(it.exitCode, it.stdout.ifBlank { "-" }))
        }
        // Parser der Aktivitaets-Ansicht
        b.exec("dumpsys", "alarm").let {
            val n = ActivityScan.parseAlarmWakeups(it.stdout).size
            add(R.string.diag_alarm, "alarm", Diagnostics.parserStatus(it.exitCode, it.stdout, n), "$n")
        }
        b.exec("dumpsys", "jobscheduler").let {
            val n = ActivityScan.parseJobs(it.stdout).size
            add(R.string.diag_jobs, "jobs", Diagnostics.parserStatus(it.exitCode, it.stdout, n), "$n")
        }
        b.exec("dumpsys", "sensorservice").let {
            val n = ActivityScan.parseSensorPackages(it.stdout).size
            add(R.string.diag_sensors, "sensors", Diagnostics.parserStatus(it.exitCode, it.stdout, n), "$n")
        }
        // Arbeitsprofil: nur lesen (Nutzerliste, erlaubte Anzahl) - es wird nichts angelegt
        val users = WorkProfile.parseUsers(text("pm", "list", "users"))
        val max = Regex("""(\d+)""").find(text("pm", "get-max-users"))?.groupValues?.get(1)?.toIntOrNull()
        add(
            R.string.diag_profiles, "profiles",
            when {
                users.isEmpty() -> Status.FAIL
                (max ?: 1) > 1 -> Status.OK
                else -> Status.UNKNOWN
            },
            getString(R.string.diag_profiles_detail, users.size, max ?: 0, if (WorkProfile.managedProfile(users) != null) 1 else 0)
        )
        return out
    }

    private fun render(header: String, lines: List<Pair<String, Result>>) {
        val list = findViewById<LinearLayout>(R.id.diagList)
        list.removeAllViews()
        fun row(text: String, color: Int, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 6, 0, 6)
        }
        list.addView(row(header, ContextCompat.getColor(this, R.color.on_glass_dim)))
        val onGlass = ContextCompat.getColor(this, R.color.on_glass)
        for ((label, r) in lines) {
            val mark = getString(
                when (r.status) {
                    Status.OK -> R.string.diag_mark_ok
                    Status.FAIL -> R.string.diag_mark_fail
                    Status.UNKNOWN -> R.string.diag_mark_unknown
                }
            )
            val detail = if (r.detail.isNotBlank()) " (${r.detail})" else ""
            list.addView(row("$mark  $label$detail", onGlass))
        }
    }

    private fun share() {
        val text = lastReport ?: return
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                getString(R.string.diag_share)
            )
        )
    }
}
