package de.minipc.appopsshizuku

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import de.minipc.appopsshizuku.appops.ActivityScan
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import java.util.concurrent.Executors
import de.minipc.appopsshizuku.ui.lifecycleHandler
import de.minipc.appopsshizuku.ui.lifecycleExecutor

/**
 * Aktivitaet: welche Apps wecken das Geraet (dumpsys alarm), planen Jobs (jobscheduler) oder fragen
 * Sensoren ab (sensorservice). Heuristische Parser - ein Zeilen-Tipp fuehrt in den Reiter Verhalten
 * (Standby, Akku, Nur bei Bedarf, Hintergrund).
 */
class ActivityScanActivity : AppCompatActivity() {

    private val handler = lifecycleHandler()
    private val executor = lifecycleExecutor()
    private lateinit var backend: ShellBackend
    private val shizukuOn: Boolean get() = backend !is NoopBackend

    private data class Section(val titleRes: Int, val items: List<ActivityScan.Item>, val recognized: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_activity_scan)
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

    private fun dump(vararg args: String): String? =
        backend.exec(*args).takeIf { it.exitCode == 0 }?.stdout

    private fun load() {
        if (!shizukuOn) {
            findViewById<TextView>(R.id.activityInfo).text = getString(R.string.activity_no_shizuku)
            return
        }
        setLoading(true)
        executor.execute {
            val alarm = dump("dumpsys", "alarm")
            val jobs = dump("dumpsys", "jobscheduler")
            val sensor = dump("dumpsys", "sensorservice")
            val own = packageName
            val sections = listOf(
                Section(
                    R.string.activity_alarms,
                    alarm?.let { ActivityScan.items(ActivityScan.parseAlarmWakeups(it), ActivityScan.Kind.ALARM, own) } ?: emptyList(),
                    alarm != null
                ),
                Section(
                    R.string.activity_jobs,
                    jobs?.let { ActivityScan.items(ActivityScan.parseJobs(it), ActivityScan.Kind.JOB, own) } ?: emptyList(),
                    jobs != null
                ),
                Section(
                    R.string.activity_sensors,
                    sensor?.let {
                        ActivityScan.parseSensorPackages(it).filter { p -> p != own }
                            .map { p -> ActivityScan.Item(p, ActivityScan.Kind.SENSOR, 0) }
                            .sortedBy { p -> p.packageName }
                    } ?: emptyList(),
                    sensor != null
                )
            )
            handler.post { setLoading(false); render(sections) }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)

    private fun render(sections: List<Section>) {
        val container = findViewById<LinearLayout>(R.id.activityContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val density = resources.displayMetrics.density
        for (section in sections) {
            container.addView(TextView(this).apply {
                text = getString(R.string.activity_section, getString(section.titleRes), section.items.size)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setPadding(0, (12 * density).toInt(), 0, 0)
            })
            if (!section.recognized) {
                container.addView(note(R.string.activity_not_recognized))
            } else if (section.items.isEmpty()) {
                container.addView(note(R.string.power_none))
            }
            for (entry in section.items.take(50)) {
                val v = inflater.inflate(R.layout.item_power_row, container, false)
                v.findViewById<ImageView>(R.id.powerIcon).setImageDrawable(
                    runCatching { packageManager.getApplicationIcon(entry.packageName) }.getOrNull()
                )
                v.findViewById<TextView>(R.id.powerLabel).text = appLabel(entry.packageName)
                v.findViewById<TextView>(R.id.powerDetail).text = when (entry.kind) {
                    ActivityScan.Kind.ALARM -> resources.getQuantityString(R.plurals.activity_wakeups, entry.count, entry.count)
                    ActivityScan.Kind.JOB -> resources.getQuantityString(R.plurals.activity_job_count, entry.count, entry.count)
                    ActivityScan.Kind.SENSOR -> getString(R.string.activity_sensor_used)
                }
                v.findViewById<MaterialButton>(R.id.powerAction).apply {
                    setText(R.string.activity_open_behavior)
                    setOnClickListener {
                        startActivity(
                            Intent(this@ActivityScanActivity, AppDetailActivity::class.java)
                                .putExtra(AppDetailActivity.EXTRA_PACKAGE, entry.packageName)
                                .putExtra(AppDetailActivity.EXTRA_TAB, 1)
                        )
                    }
                }
                container.addView(v)
            }
        }
    }

    private fun note(res: Int) = TextView(this).apply {
        setText(res)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
    }
}
