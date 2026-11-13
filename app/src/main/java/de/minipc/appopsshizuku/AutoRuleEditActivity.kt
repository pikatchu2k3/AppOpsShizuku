package de.minipc.appopsshizuku

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import de.minipc.appopsshizuku.appops.AutoRule
import de.minipc.appopsshizuku.appops.AutoRuleStore
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.RuleAction
import de.minipc.appopsshizuku.appops.RuleTrigger
import java.util.UUID

/** Formular zum Anlegen/Bearbeiten einer Automatik-Regel. */
class AutoRuleEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"

        /** Ab Android 12 muessen FINE und COARSE zusammen angefragt werden, sonst kommt kein Dialog. */
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private lateinit var store: AutoRuleStore
    private var existing: AutoRule? = null
    private var startMin = 23 * 60
    private var endMin = 7 * 60
    private var pickedApps: MutableList<String> = mutableListOf()
    private val dayChips = mutableListOf<Chip>()

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Der Netzname braucht den GENAUEN Standort; "nur ungefaehr" reicht nicht
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) fillCurrentSsid()
            else Toast.makeText(this, R.string.rule_wifi_needs_location, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_auto_rule_edit)
        de.minipc.appopsshizuku.ui.SpaceScreen.apply(this)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = AutoRuleStore(this)

        buildDayChips()
        findViewById<MaterialButton>(R.id.startTime).setOnClickListener { pickTime(true) }
        findViewById<MaterialButton>(R.id.endTime).setOnClickListener { pickTime(false) }
        findViewById<RadioGroup>(R.id.triggerGroup).setOnCheckedChangeListener { _, _ -> updateSections() }
        findViewById<RadioGroup>(R.id.actionGroup).setOnCheckedChangeListener { _, _ -> updateSections() }
        findViewById<MaterialButton>(R.id.wifiUseCurrent).setOnClickListener { useCurrentSsid() }
        findViewById<MaterialButton>(R.id.pickApps).setOnClickListener { pickApps() }
        findViewById<MaterialButton>(R.id.saveRule).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.deleteRule).setOnClickListener { delete() }

        intent.getStringExtra(EXTRA_ID)?.let { id -> existing = store.all().firstOrNull { it.id == id } }
        existing?.let { fillFrom(it) }
        updateTimeButtons()
        updateSections()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun buildDayChips() {
        val group = findViewById<ChipGroup>(R.id.dayChips)
        val names = resources.getStringArray(R.array.weekday_short)
        for (i in 0..6) {
            val chip = Chip(this).apply {
                setChipDrawable(
                    com.google.android.material.chip.ChipDrawable.createFromAttributes(
                        this@AutoRuleEditActivity, null, 0,
                        com.google.android.material.R.style.Widget_Material3_Chip_Filter
                    )
                )
                text = names[i]
                isCheckable = true
                isChecked = true
            }
            dayChips += chip
            group.addView(chip)
        }
    }

    private fun fillFrom(r: AutoRule) {
        findViewById<TextInputEditText>(R.id.ruleNameInput).setText(r.name)
        findViewById<RadioGroup>(R.id.triggerGroup).check(
            if (r.trigger == RuleTrigger.TIME) R.id.triggerTime else R.id.triggerWifi
        )
        startMin = r.startMin; endMin = r.endMin
        dayChips.forEachIndexed { i, c -> c.isChecked = (r.days shr i) and 1 == 1 }
        findViewById<TextInputEditText>(R.id.wifiSsidInput).setText(r.ssid)
        findViewById<RadioGroup>(R.id.wifiWhenGroup).check(
            if (r.whenConnected) R.id.wifiConnected else R.id.wifiDisconnected
        )
        findViewById<RadioGroup>(R.id.actionGroup).check(
            if (r.action == RuleAction.LOCKDOWN) R.id.actionLockdown else R.id.actionOffline
        )
        pickedApps = r.packages.toMutableList()
        findViewById<MaterialButton>(R.id.deleteRule).visibility = View.VISIBLE
    }

    private fun updateSections() {
        val time = findViewById<RadioGroup>(R.id.triggerGroup).checkedRadioButtonId == R.id.triggerTime
        findViewById<View>(R.id.timeSection).visibility = if (time) View.VISIBLE else View.GONE
        findViewById<View>(R.id.wifiSection).visibility = if (time) View.GONE else View.VISIBLE
        val offline = findViewById<RadioGroup>(R.id.actionGroup).checkedRadioButtonId == R.id.actionOffline
        findViewById<MaterialButton>(R.id.pickApps).visibility = if (offline) View.VISIBLE else View.GONE
        updatePickAppsLabel()
    }

    private fun updateTimeButtons() {
        findViewById<MaterialButton>(R.id.startTime).text =
            getString(R.string.rule_from, String.format("%02d:%02d", startMin / 60, startMin % 60))
        findViewById<MaterialButton>(R.id.endTime).text =
            getString(R.string.rule_to, String.format("%02d:%02d", endMin / 60, endMin % 60))
    }

    private fun updatePickAppsLabel() {
        findViewById<MaterialButton>(R.id.pickApps).text =
            getString(R.string.rule_pick_apps_n, pickedApps.size)
    }

    private fun pickTime(start: Boolean) {
        val cur = if (start) startMin else endMin
        TimePickerDialog(this, { _, h, m ->
            if (start) startMin = h * 60 + m else endMin = h * 60 + m
            updateTimeButtons()
        }, cur / 60, cur % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun useCurrentSsid() {
        if (!AutoRuleManager.hasLocationPermission(this)) {
            requestLocation.launch(LOCATION_PERMISSIONS)
            return
        }
        fillCurrentSsid()
    }

    private fun fillCurrentSsid() {
        val ssid = AutoRuleManager.currentSsid(this)
        if (ssid == null) {
            Toast.makeText(this, R.string.rule_wifi_unreadable, Toast.LENGTH_LONG).show()
        } else {
            findViewById<TextInputEditText>(R.id.wifiSsidInput).setText(ssid)
        }
    }

    private fun pickApps() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.packageName }.filter { it != packageName }.distinct()
            .map { it to runCatching { pm.getApplicationInfo(it, 0).loadLabel(pm).toString() }.getOrDefault(it) }
            .sortedBy { it.second.lowercase() }
        val labels = apps.map { it.second }.toTypedArray()
        val checked = apps.map { it.first in pickedApps }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_pick_apps)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pickedApps = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toMutableList()
                updatePickAppsLabel()
            }
            .show()
    }

    private fun collectDays(): Int {
        var d = 0
        dayChips.forEachIndexed { i, c -> if (c.isChecked) d = d or (1 shl i) }
        return d
    }

    private fun save() {
        val name = findViewById<TextInputEditText>(R.id.ruleNameInput).text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.rule_name_required, Toast.LENGTH_LONG).show()
            return
        }
        val time = findViewById<RadioGroup>(R.id.triggerGroup).checkedRadioButtonId == R.id.triggerTime
        val offline = findViewById<RadioGroup>(R.id.actionGroup).checkedRadioButtonId == R.id.actionOffline
        val ssid = findViewById<TextInputEditText>(R.id.wifiSsidInput).text?.toString()?.trim().orEmpty()
        if (time && collectDays() == 0) {
            Toast.makeText(this, R.string.rule_day_required, Toast.LENGTH_LONG).show(); return
        }
        if (time && startMin == endMin) {
            Toast.makeText(this, R.string.rule_window_required, Toast.LENGTH_LONG).show(); return
        }
        if (!time && ssid.isEmpty()) {
            Toast.makeText(this, R.string.rule_wifi_required, Toast.LENGTH_LONG).show(); return
        }
        if (offline && pickedApps.isEmpty()) {
            Toast.makeText(this, R.string.rule_apps_required, Toast.LENGTH_LONG).show(); return
        }
        // WLAN-Regel braucht die Standortberechtigung dieser App, um den Netznamen zu lesen.
        if (!time && !AutoRuleManager.hasLocationPermission(this)) {
            requestLocation.launch(LOCATION_PERMISSIONS)
            Toast.makeText(this, R.string.rule_wifi_needs_location, Toast.LENGTH_LONG).show()
            return
        }
        val rule = AutoRule(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            enabled = existing?.enabled ?: true,
            trigger = if (time) RuleTrigger.TIME else RuleTrigger.WIFI,
            days = collectDays(),
            startMin = startMin, endMin = endMin,
            ssid = ssid,
            whenConnected = findViewById<RadioGroup>(R.id.wifiWhenGroup).checkedRadioButtonId == R.id.wifiConnected,
            action = if (offline) RuleAction.APPS_OFFLINE else RuleAction.LOCKDOWN,
            packages = pickedApps
        )
        store.put(rule)
        val app = applicationContext
        val wifi = !time
        Thread {
            // Hintergrund-Standort fuer die eigene App (nur bei WLAN-Regeln, Best-Effort per Shizuku):
            // sonst liest der Weckruf im Hintergrund keinen Netznamen.
            if (wifi) runCatching { AutoRuleManager.grantBackgroundLocation(app) }
            runCatching { AutoRuleManager.evaluate(app) }
        }.start()
        finish()
    }

    private fun delete() {
        existing?.let {
            store.remove(it.id)
            AutoRuleManager.scheduleNext(this)
        }
        finish()
    }
}
