package de.minipc.appopsshizuku

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.Lockdown
import de.minipc.appopsshizuku.appops.LockdownStore
import de.minipc.appopsshizuku.appops.PowerfulAccess
import de.minipc.appopsshizuku.appops.PrivacyOps
import de.minipc.appopsshizuku.appops.PrivateDns
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.ui.OpLabels
import java.util.concurrent.Executors

/**
 * Seite "Systemweit": Lockdown-Szene (auch als Schnellstart-Kachel), Private DNS und die Uebersicht
 * maechtiger Rechte (Bedienungshilfen, Benachrichtigungszugriff, Tastaturen, Geraeteadministratoren,
 * Rollen, Always-on-VPN). Alles wird nach dem Setzen zurueckgelesen; was die Shell nicht darf,
 * fuehrt in die passende System-Einstellung statt etwas vorzutaeuschen.
 */
class SystemActivity : AppCompatActivity() {

    private companion object {
        const val PREFS = "system_wide"
        const val KEY_DNS_PREV_MODE = "dns_prev_mode"
        const val KEY_DNS_PREV_HOST = "dns_prev_host"
        const val KEY_DNS_SAVED = "dns_prev_saved"
        const val ID_OFF = 1
        const val ID_AUTO = 2
        const val ID_CUSTOM = 3
        const val ID_PRESET_BASE = 100
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var backend: ShellBackend
    private lateinit var lockdown: LockdownStore
    private var dnsState: PrivateDns.State? = null

    private var shizukuOn = false

    /** Einstellungen les-/schreibbar: ueber Shizuku oder das Dauerrecht WRITE_SECURE_SETTINGS. */
    private val canSettings: Boolean get() = shizukuOn || StandingAccess.has(this)

    /** Eine Zeile der Liste "Maechtige Rechte". */
    private data class PowerRow(
        val pkg: String,
        val detail: String,
        val action: Action,
        val component: PowerfulAccess.Component? = null,
        val imeId: String? = null,
        val settingsAction: String? = null
    )

    private enum class Action { REMOVE_ACCESSIBILITY, REMOVE_LISTENER, DISABLE_IME, OPEN_SETTINGS, NONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_system)
        de.minipc.appopsshizuku.ui.SpaceScreen.apply(this)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val real = runCatching {
            ShizukuBackend().takeIf { it.isAvailable() && it.hasPermission() }
        }.getOrNull() ?: NoopBackend()
        shizukuOn = real !is NoopBackend
        // Mit Dauerrecht gehen "settings"-Befehle direkt, auch ohne Shizuku
        backend = SettingsBackend(applicationContext, real)
        lockdown = LockdownStore(this)
        findViewById<TextView>(R.id.systemShizukuHint).apply {
            visibility = if (shizukuOn) View.GONE else View.VISIBLE
            if (!shizukuOn && canSettings) setText(R.string.system_standing_only)
        }

        setupLockdown()
        setupDns()
        findViewById<MaterialButton>(R.id.powerRefresh).setOnClickListener { loadPower() }
        loadDns()
        loadPower()
    }

    override fun onDestroy() {
        executor.shutdown()
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

    private fun sh(vararg args: String): String? =
        backend.exec(*args).takeIf { it.exitCode == 0 }?.stdout

    // ------------------------------------------------------------------ Lockdown

    private fun setupLockdown() {
        val group = findViewById<ChipGroup>(R.id.lockdownOps)
        val byName = OpLabels.localized(this).associateBy { it.opName }
        for (name in Lockdown.CHOICES) {
            val chip = Chip(this).apply {
                setChipDrawable(
                    com.google.android.material.chip.ChipDrawable.createFromAttributes(
                        this@SystemActivity, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter
                    )
                )
                text = byName[name]?.label ?: name
                isCheckable = true
                isChecked = name in lockdown.ops
                tag = name
                setOnCheckedChangeListener { _, _ -> saveLockdownOps() }
            }
            group.addView(chip)
        }
        findViewById<MaterialButton>(R.id.lockdownToggle).setOnClickListener { toggleLockdown() }
        renderLockdown()
    }

    private fun saveLockdownOps() {
        val group = findViewById<ChipGroup>(R.id.lockdownOps)
        val chosen = (0 until group.childCount).map { group.getChildAt(it) as Chip }
            .filter { it.isChecked }.map { it.tag as String }.toSet()
        lockdown.ops = chosen
        renderLockdown()
    }

    private fun renderLockdown() {
        val active = lockdown.active
        val status = findViewById<TextView>(R.id.lockdownStatus)
        status.text = if (active) {
            getString(
                R.string.lockdown_status_on,
                DateUtils.getRelativeTimeSpanString(lockdown.since, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                lockdown.revoked.size
            )
        } else getString(R.string.lockdown_status_off)
        // Waehrend des Lockdowns die Auswahl sperren: beim Beenden zaehlt, was entzogen wurde
        val group = findViewById<ChipGroup>(R.id.lockdownOps)
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = !active
        findViewById<MaterialButton>(R.id.lockdownToggle).apply {
            setText(if (active) R.string.lockdown_stop else R.string.lockdown_start)
            isEnabled = shizukuOn && (active || lockdown.ops.isNotEmpty())
        }
    }

    private fun toggleLockdown() {
        val activate = !lockdown.active
        setLoading(true)
        findViewById<MaterialButton>(R.id.lockdownToggle).isEnabled = false
        executor.execute {
            val r = if (activate) LockdownManager.activate(this) else LockdownManager.deactivate(this)
            handler.post {
                setLoading(false)
                renderLockdown()
                Toast.makeText(
                    this,
                    when {
                        r.noShizuku -> getString(R.string.status_no_permission)
                        activate -> getString(R.string.lockdown_on_result, r.changed, r.failed)
                        else -> getString(R.string.lockdown_off_result, r.changed, r.failed)
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------ Private DNS

    private fun setupDns() {
        val group = findViewById<RadioGroup>(R.id.dnsGroup)
        fun add(id: Int, text: String) = group.addView(RadioButton(this).apply {
            this.id = id
            this.text = text
        })
        add(ID_OFF, getString(R.string.dns_off))
        add(ID_AUTO, getString(R.string.dns_auto))
        PrivateDns.PRESETS.forEachIndexed { i, p ->
            add(
                ID_PRESET_BASE + i,
                getString(if (p.blocksTrackers) R.string.dns_preset_blocking else R.string.dns_preset_plain, p.name, p.host)
            )
        }
        add(ID_CUSTOM, getString(R.string.dns_custom))
        group.setOnCheckedChangeListener { _, id ->
            findViewById<View>(R.id.dnsCustomLayout).visibility = if (id == ID_CUSTOM) View.VISIBLE else View.GONE
        }
        findViewById<MaterialButton>(R.id.dnsApply).setOnClickListener { applyDns() }
        findViewById<MaterialButton>(R.id.dnsRestore).setOnClickListener { restoreDns() }
        findViewById<MaterialButton>(R.id.dnsApply).isEnabled = canSettings
    }

    private fun readDns(): PrivateDns.State? {
        if (!canSettings) return null
        val mode = PrivateDns.clean(sh("settings", "get", "global", "private_dns_mode"))
        val host = PrivateDns.clean(sh("settings", "get", "global", "private_dns_specifier"))
        return PrivateDns.State(mode, host)
    }

    private fun loadDns() {
        executor.execute {
            val st = readDns()
            handler.post { renderDns(st) }
        }
    }

    private fun renderDns(st: PrivateDns.State?) {
        dnsState = st
        val status = findViewById<TextView>(R.id.dnsStatus)
        status.text = when {
            st == null -> getString(R.string.system_needs_shizuku)
            st.mode == PrivateDns.MODE_HOSTNAME -> getString(R.string.dns_status_host, st.host ?: "?")
            st.mode == PrivateDns.MODE_OFF -> getString(R.string.dns_status_off)
            else -> getString(R.string.dns_status_auto)
        }
        val group = findViewById<RadioGroup>(R.id.dnsGroup)
        if (st != null) {
            val preset = PrivateDns.PRESETS.indexOfFirst { it.host.equals(st.host, ignoreCase = true) }
            group.check(
                when {
                    st.mode == PrivateDns.MODE_OFF -> ID_OFF
                    st.mode == PrivateDns.MODE_HOSTNAME && preset >= 0 -> ID_PRESET_BASE + preset
                    st.mode == PrivateDns.MODE_HOSTNAME -> ID_CUSTOM.also {
                        findViewById<EditText>(R.id.dnsCustom).setText(st.host.orEmpty())
                    }
                    else -> ID_AUTO
                }
            )
        }
        findViewById<View>(R.id.dnsRestore).visibility =
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_DNS_SAVED, false)) View.VISIBLE else View.GONE
    }

    private fun applyDns() {
        val id = findViewById<RadioGroup>(R.id.dnsGroup).checkedRadioButtonId
        val (mode, host) = when {
            id == ID_OFF -> PrivateDns.MODE_OFF to null
            id == ID_AUTO -> PrivateDns.MODE_AUTO to null
            id == ID_CUSTOM -> PrivateDns.MODE_HOSTNAME to findViewById<EditText>(R.id.dnsCustom).text.toString().trim()
            id >= ID_PRESET_BASE -> PrivateDns.MODE_HOSTNAME to PrivateDns.PRESETS[id - ID_PRESET_BASE].host
            else -> return
        }
        if (host != null && !PrivateDns.isValidHost(host)) {
            Toast.makeText(this, R.string.dns_invalid_host, Toast.LENGTH_LONG).show()
            return
        }
        // Vorherigen Zustand EINMAL merken, damit "Zuruecksetzen" ihn wiederherstellt
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_DNS_SAVED, false)) {
            prefs.edit()
                .putBoolean(KEY_DNS_SAVED, true)
                .putString(KEY_DNS_PREV_MODE, dnsState?.mode)
                .putString(KEY_DNS_PREV_HOST, dnsState?.host)
                .apply()
        }
        writeDns(mode, host, restoring = false)
    }

    private fun restoreDns() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val mode = prefs.getString(KEY_DNS_PREV_MODE, null) ?: PrivateDns.MODE_AUTO
        val host = prefs.getString(KEY_DNS_PREV_HOST, null)
        writeDns(mode, host, restoring = true)
    }

    private fun writeDns(mode: String, host: String?, restoring: Boolean) {
        setLoading(true)
        executor.execute {
            if (host != null) backend.exec("settings", "put", "global", "private_dns_specifier", host)
            backend.exec("settings", "put", "global", "private_dns_mode", mode)
            val st = readDns()
            val ok = st?.mode == mode && (mode != PrivateDns.MODE_HOSTNAME || st.host == host)
            if (ok && restoring) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
            }
            handler.post {
                setLoading(false)
                renderDns(st)
                Toast.makeText(this, if (ok) R.string.dns_applied else R.string.dns_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ Maechtige Rechte

    private fun loadPower() {
        setLoading(true)
        executor.execute {
            val sections = readPower()
            handler.post {
                setLoading(false)
                renderPower(sections)
            }
        }
    }

    private fun readPower(): List<Pair<String, List<PowerRow>>> {
        val out = mutableListOf<Pair<String, List<PowerRow>>>()
        if (canSettings) {
            val acc = sh("settings", "get", "secure", "enabled_accessibility_services")
            out += getString(R.string.power_accessibility) to PowerfulAccess.parseComponents(acc).map {
                PowerRow(it.packageName, it.className, Action.REMOVE_ACCESSIBILITY, component = it)
            }
        }
        if (shizukuOn) {
            val nl = sh("settings", "get", "secure", "enabled_notification_listeners")
            out += getString(R.string.power_listeners) to PowerfulAccess.parseComponents(nl).map {
                PowerRow(it.packageName, it.className, Action.REMOVE_LISTENER, component = it)
            }
            val default = PrivateDns.clean(sh("settings", "get", "secure", "default_input_method"))
            val imes = sh("ime", "list", "-s").orEmpty().lineSequence().map { it.trim() }
                .filter { it.contains('/') }.toList()
            out += getString(R.string.power_ime) to imes.map { id ->
                val pkg = id.substringBefore('/')
                if (id == default) PowerRow(pkg, getString(R.string.power_ime_default), Action.NONE, imeId = id)
                else PowerRow(pkg, id.substringAfter('/'), Action.DISABLE_IME, imeId = id)
            }
            val roles = PowerfulAccess.ROLES.flatMap { role ->
                PowerfulAccess.parseRoleHolders(sh("cmd", "role", "get-role-holders", role)).map { pkg ->
                    PowerRow(pkg, roleName(role), Action.OPEN_SETTINGS, settingsAction = Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                }
            }
            out += getString(R.string.power_roles) to roles
            val vpn = PrivateDns.clean(sh("settings", "get", "secure", "always_on_vpn_app"))
            if (vpn != null) {
                out += getString(R.string.power_vpn) to listOf(
                    PowerRow(vpn, getString(R.string.power_vpn_detail), Action.OPEN_SETTINGS, settingsAction = Settings.ACTION_VPN_SETTINGS)
                )
            }
        }
        // Geraeteadministratoren: lokal lesbar (ohne Shizuku); entfernen nur in den Einstellungen
        val admins = runCatching { getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty() }.getOrDefault(emptyList())
        out += getString(R.string.power_admins) to admins.map {
            PowerRow(it.packageName, it.className, Action.OPEN_SETTINGS, settingsAction = Settings.ACTION_SECURITY_SETTINGS)
        }
        return out
    }

    private fun roleName(role: String): String = getString(
        when (role) {
            "android.app.role.SMS" -> R.string.power_role_sms
            "android.app.role.DIALER" -> R.string.power_role_dialer
            "android.app.role.ASSISTANT" -> R.string.power_role_assistant
            else -> R.string.power_role_browser
        }
    )

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)

    private fun renderPower(sections: List<Pair<String, List<PowerRow>>>) {
        val list = findViewById<LinearLayout>(R.id.powerList)
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for ((title, rows) in sections) {
            list.addView(TextView(this).apply {
                text = getString(R.string.power_section, title, rows.size)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            if (rows.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = getString(R.string.power_none)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                })
            }
            for (row in rows) {
                val v = inflater.inflate(R.layout.item_power_row, list, false)
                v.findViewById<ImageView>(R.id.powerIcon).setImageDrawable(
                    runCatching { packageManager.getApplicationIcon(row.pkg) }.getOrNull()
                )
                v.findViewById<TextView>(R.id.powerLabel).text = appLabel(row.pkg)
                v.findViewById<TextView>(R.id.powerDetail).text = row.detail
                val btn = v.findViewById<MaterialButton>(R.id.powerAction)
                when (row.action) {
                    Action.NONE -> btn.visibility = View.GONE
                    Action.OPEN_SETTINGS -> {
                        btn.setText(R.string.power_open_settings)
                        btn.setOnClickListener { openSettings(row.settingsAction) }
                    }
                    else -> {
                        btn.setText(R.string.power_revoke)
                        btn.isEnabled = row.pkg != packageName &&
                            (shizukuOn || (row.action == Action.REMOVE_ACCESSIBILITY && canSettings))
                        btn.setOnClickListener { confirmRevoke(title, row) }
                    }
                }
                list.addView(v)
            }
        }
    }

    private fun openSettings(action: String?) {
        try {
            startActivity(Intent(action ?: Settings.ACTION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun confirmRevoke(section: String, row: PowerRow) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.power_confirm_title, appLabel(row.pkg)))
            .setMessage(getString(R.string.power_confirm_msg, section))
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.power_revoke) { _, _ -> revoke(row) }
            .show()
    }

    /** Entziehen und zuruecklesen: Erfolg nur, wenn der Eintrag danach wirklich fehlt. */
    private fun revoke(row: PowerRow) {
        setLoading(true)
        executor.execute {
            val ok = when (row.action) {
                Action.REMOVE_ACCESSIBILITY -> {
                    val c = row.component!!
                    val raw = sh("settings", "get", "secure", "enabled_accessibility_services")
                    val rest = PowerfulAccess.without(raw, c)
                    if (rest.isEmpty()) backend.exec("settings", "delete", "secure", "enabled_accessibility_services")
                    else backend.exec("settings", "put", "secure", "enabled_accessibility_services", rest)
                    c !in PowerfulAccess.parseComponents(sh("settings", "get", "secure", "enabled_accessibility_services"))
                }
                Action.REMOVE_LISTENER -> {
                    val c = row.component!!
                    backend.exec("cmd", "notification", "disallow_listener", c.flat)
                    c !in PowerfulAccess.parseComponents(sh("settings", "get", "secure", "enabled_notification_listeners"))
                }
                Action.DISABLE_IME -> {
                    backend.exec("ime", "disable", row.imeId!!)
                    sh("ime", "list", "-s").orEmpty().lineSequence().none { it.trim() == row.imeId }
                }
                else -> false
            }
            val sections = readPower()
            handler.post {
                setLoading(false)
                renderPower(sections)
                Toast.makeText(this, if (ok) R.string.power_revoked else R.string.power_revoke_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
