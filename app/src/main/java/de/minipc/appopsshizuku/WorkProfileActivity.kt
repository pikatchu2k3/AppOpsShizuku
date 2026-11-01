package de.minipc.appopsshizuku

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import de.minipc.appopsshizuku.appops.DesignSettings
import de.minipc.appopsshizuku.appops.WorkProfile
import de.minipc.appopsshizuku.shizuku.NoopBackend
import de.minipc.appopsshizuku.shizuku.ShellBackend
import de.minipc.appopsshizuku.shizuku.ShizukuBackend
import de.minipc.appopsshizuku.ui.ToggleGuard
import java.util.concurrent.Executors

/**
 * Arbeitsprofil: ein zweites Profil, in das Apps verschoben werden koennen (sehen dann weder Kontakte
 * noch Dateien des Hauptprofils). Diese App kann selbst eins anlegen (eigener Profilbesitzer) und
 * Apps auch in ein bereits vorhandenes (z. B. von Shelter) verschieben.
 */
class WorkProfileActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var backend: ShellBackend
    private lateinit var mgr: WorkProfileManager
    private var profile: WorkProfile.User? = null
    private var ownerIsUs = false
    private var inProfile: Set<String> = emptySet()
    private data class Row(val pkg: String, val label: String, val icon: Drawable?, val inProfile: Boolean)
    private var rows: List<Row> = emptyList()
    private val adapter = Adapter()

    private val shizukuOn: Boolean get() = backend !is NoopBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DesignSettings.applyDynamicColorsIfEnabled(this)
        setContentView(R.layout.activity_work_profile)
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
        mgr = WorkProfileManager(backend, packageName)
        findViewById<RecyclerView>(R.id.workprofileList).apply {
            layoutManager = LinearLayoutManager(this@WorkProfileActivity)
            adapter = this@WorkProfileActivity.adapter
        }
        findViewById<MaterialButton>(R.id.workprofileCreate).setOnClickListener { promptCreate() }
        findViewById<MaterialButton>(R.id.workprofileRemove).setOnClickListener { confirmRemove() }
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
        if (!shizukuOn) {
            findViewById<TextView>(R.id.workprofileStatus).text = getString(R.string.workprofile_no_shizuku)
            findViewById<MaterialButton>(R.id.workprofileCreate).isEnabled = false
            return
        }
        setLoading(true)
        executor.execute {
            val p = mgr.managedProfile()
            val owner = p?.let { mgr.profileOwner(it.id) }
            val apps = p?.let { mgr.appsInProfile(it.id) } ?: emptySet()
            val pm = packageManager
            val launcher = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).map { it.activityInfo.packageName }.filter { it != packageName }.distinct()
            val built = launcher.mapNotNull { pkg ->
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                Row(pkg, ai.loadLabel(pm).toString(), runCatching { ai.loadIcon(pm) }.getOrNull(), pkg in apps)
            }.sortedBy { it.label.lowercase() }
            handler.post {
                setLoading(false)
                profile = p
                ownerIsUs = owner == packageName
                inProfile = apps
                rows = built
                adapter.notifyDataSetChanged()
                render()
            }
        }
    }

    private fun render() {
        val status = findViewById<TextView>(R.id.workprofileStatus)
        val create = findViewById<MaterialButton>(R.id.workprofileCreate)
        val remove = findViewById<MaterialButton>(R.id.workprofileRemove)
        val p = profile
        if (p == null) {
            status.text = getString(R.string.workprofile_none)
            create.visibility = View.VISIBLE
            remove.visibility = View.GONE
        } else {
            status.text = getString(
                if (ownerIsUs) R.string.workprofile_exists_ours else R.string.workprofile_exists_foreign,
                p.id
            )
            create.visibility = View.GONE
            remove.visibility = View.VISIBLE
        }
    }

    private fun promptCreate() {
        val input = TextInputEditText(this).apply { setText(getString(R.string.workprofile_default_name)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.workprofile_create)
            .setMessage(R.string.workprofile_create_msg)
            .setView(input)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.workprofile_create) { _, _ ->
                create(input.text?.toString()?.trim().orEmpty().ifEmpty { getString(R.string.workprofile_default_name) })
            }
            .show()
    }

    private fun create(name: String) {
        setLoading(true)
        executor.execute {
            val res = mgr.createProfile(name)
            handler.post {
                setLoading(false)
                Toast.makeText(
                    this,
                    when {
                        res.userId == null -> getString(R.string.workprofile_create_failed)
                        !res.owner -> getString(R.string.workprofile_create_no_owner)
                        else -> getString(R.string.workprofile_create_ok)
                    },
                    Toast.LENGTH_LONG
                ).show()
                load()
            }
        }
    }

    private fun confirmRemove() {
        val id = profile?.id ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.workprofile_remove)
            .setMessage(R.string.workprofile_remove_msg)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.workprofile_remove) { _, _ ->
                setLoading(true)
                executor.execute {
                    val ok = mgr.removeProfile(id)
                    handler.post {
                        setLoading(false)
                        Toast.makeText(this, if (ok) R.string.workprofile_removed else R.string.workprofile_remove_failed, Toast.LENGTH_LONG).show()
                        load()
                    }
                }
            }
            .show()
    }

    private fun toggle(position: Int, into: Boolean) {
        val p = profile
        val row = rows.getOrNull(position) ?: return
        if (p == null) { adapter.notifyItemChanged(position); return }
        executor.execute {
            val ok = mgr.moveApp(p.id, row.pkg, into)
            handler.post {
                if (ok) {
                    val idx = rows.indexOfFirst { it.pkg == row.pkg }
                    if (idx >= 0) {
                        rows = rows.toMutableList().also { it[idx] = it[idx].copy(inProfile = into) }
                        adapter.notifyItemChanged(idx)
                    }
                } else {
                    Toast.makeText(this, R.string.workprofile_move_failed, Toast.LENGTH_LONG).show()
                    adapter.notifyItemChanged(position)
                }
            }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rowIcon)
            val label: TextView = v.findViewById(R.id.rowLabel)
            val detail: TextView = v.findViewById(R.id.rowDetail)
            val sw: MaterialSwitch = v.findViewById(R.id.rowSwitch)
            val guard = ToggleGuard()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_perm_app, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val row = rows[position]
            h.icon.setImageDrawable(row.icon)
            h.label.text = row.label
            h.detail.text = getString(if (row.inProfile) R.string.workprofile_in else R.string.workprofile_out)
            h.sw.isEnabled = shizukuOn && profile != null
            h.sw.setOnCheckedChangeListener(null)
            h.guard.silently { h.sw.isChecked = row.inProfile }
            h.sw.setOnCheckedChangeListener { _, checked ->
                h.guard.ifUser {
                    val pos = h.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) toggle(pos, checked)
                }
            }
        }
    }
}
