package de.minipc.appopsshizuku

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import de.minipc.appopsshizuku.appops.AutoRule
import de.minipc.appopsshizuku.appops.AutoRuleStore
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.RuleAction
import de.minipc.appopsshizuku.appops.RuleTrigger
import de.minipc.appopsshizuku.ui.ToggleGuard

/** Liste der Automatik-Regeln. Hinzufuegen/Bearbeiten in [AutoRuleEditActivity]. */
class AutoRulesActivity : AppCompatActivity() {

    private lateinit var store: AutoRuleStore
    private var rules: List<AutoRule> = emptyList()
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_auto_rules)
        de.minipc.appopsshizuku.ui.SpaceScreen.apply(this)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = AutoRuleStore(this)
        findViewById<RecyclerView>(R.id.rulesList).apply {
            layoutManager = LinearLayoutManager(this@AutoRulesActivity)
            adapter = this@AutoRulesActivity.adapter
        }
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            startActivity(Intent(this, AutoRuleEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun reload() {
        rules = store.all().sortedBy { it.name.lowercase() }
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.rulesEmpty).visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun hm(min: Int) = String.format("%02d:%02d", min / 60, min % 60)

    private fun dayText(days: Int): String {
        val names = resources.getStringArray(R.array.weekday_short)
        if (days and 0b1111111 == 0b1111111) return getString(R.string.rule_days_all)
        return (0..6).filter { (days shr it) and 1 == 1 }.joinToString(", ") { names[it] }
    }

    private fun detail(r: AutoRule): String {
        val action = getString(
            if (r.action == RuleAction.LOCKDOWN) R.string.rule_action_lockdown
            else R.string.rule_action_offline
        )
        return when (r.trigger) {
            RuleTrigger.TIME -> "${hm(r.startMin)}–${hm(r.endMin)} · ${dayText(r.days)} · $action"
            RuleTrigger.WIFI -> {
                val w = getString(
                    if (r.whenConnected) R.string.rule_wifi_connected_short else R.string.rule_wifi_disconnected_short,
                    r.ssid
                )
                "$w · $action"
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.ruleName)
            val detail: TextView = v.findViewById(R.id.ruleDetail)
            val enabled: MaterialSwitch = v.findViewById(R.id.ruleEnabled)
            val guard = ToggleGuard()
        }

        override fun getItemCount() = rules.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_auto_rule, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = rules[position]
            h.name.text = r.name
            h.detail.text = detail(r)
            h.enabled.setOnCheckedChangeListener(null)
            h.guard.silently { h.enabled.isChecked = r.enabled }
            h.enabled.setOnCheckedChangeListener { _, checked ->
                h.guard.ifUser {
                    store.put(r.copy(enabled = checked))
                    val app = applicationContext
                    Thread { runCatching { AutoRuleManager.evaluate(app) } }.start()
                    reload()
                }
            }
            h.itemView.setOnClickListener {
                startActivity(
                    Intent(this@AutoRulesActivity, AutoRuleEditActivity::class.java)
                        .putExtra(AutoRuleEditActivity.EXTRA_ID, r.id)
                )
            }
        }
    }
}
