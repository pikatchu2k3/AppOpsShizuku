package de.minipc.appopsshizuku.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.minipc.appopsshizuku.R
import de.minipc.appopsshizuku.appops.AppOp

/** Typsichere Listen-Items: Kategorie-Header ODER Op-Zeile. */
sealed class OpsListItem {
    data class CategoryHeader(val title: String) : OpsListItem()
    /**
     * [accessText] = fertig formulierte Zeile des Zugriffsprotokolls (null = keine anzeigen),
     * [suggested] = Empfehlung "kann entzogen werden" (Zeile wird hervorgehoben).
     */
    data class OpItem(
        val op: AppOp,
        val currentMode: String?,
        val accessText: CharSequence? = null,
        val suggested: Boolean = false
    ) : OpsListItem()
}

class AppOpsAdapter(
    private val onModeSelected: (AppOp, String) -> Unit
) : ListAdapter<OpsListItem, RecyclerView.ViewHolder>(OpsDiffCallback()) {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_OP = 1
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.categoryHeader)
    }

    class OpHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.opLabel)
        val access: TextView = view.findViewById(R.id.opAccess)
        val spinner: AutoCompleteTextView = view.findViewById(R.id.opModeSpinner)
    }

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is OpsListItem.CategoryHeader -> TYPE_HEADER
            is OpsListItem.OpItem -> TYPE_OP
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_category_header, parent, false))
        } else {
            OpHolder(inflater.inflate(R.layout.item_appop, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is OpsListItem.CategoryHeader -> {
                (holder as HeaderHolder).title.text = item.title
            }
            is OpsListItem.OpItem -> {
                val h = holder as OpHolder
                h.label.text = item.op.label
                h.access.visibility = if (item.accessText.isNullOrEmpty()) View.GONE else View.VISIBLE
                h.access.text = item.accessText
                h.access.setTextColor(
                    com.google.android.material.color.MaterialColors.getColor(
                        h.access,
                        if (item.suggested) com.google.android.material.R.attr.colorTertiary
                        else com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
                // Permission-Ops (Kamera, Mic, Kontakte...): ignore ist wie deny (pm revoke)
                // und wird deshalb nicht als eigene Option angeboten. Ist der aktuelle
                // Zustand trotzdem ignore (z.B. Permission nicht granted), bleibt er sichtbar.
                val baseModes = if (item.op.permissionName != null) {
                    listOf("default", "allow", "deny")
                } else {
                    listOf("default", "allow", "ignore", "deny")
                }
                val current = item.currentMode
                val modes = if (current != null && current !in baseModes) baseModes + current else baseModes

                // Exposed Dropdown (Material): Adapter + Text + Item-Listener,
                // Tastatur unterdruecken (reines Auswahlfeld wie der alte Spinner)
                h.spinner.setAdapter(
                    ArrayAdapter(
                        h.spinner.context,
                        android.R.layout.simple_spinner_dropdown_item,
                        modes
                    )
                )
                h.spinner.inputType = android.text.InputType.TYPE_NULL
                h.spinner.keyListener = null
                h.spinner.setText(current ?: modes[0], false)
                h.spinner.setOnItemClickListener { _, _, pos, _ ->
                    val mode = modes[pos]
                    if (mode != current) onModeSelected(item.op, mode)
                }
            }
        }
    }

    class OpsDiffCallback : DiffUtil.ItemCallback<OpsListItem>() {
        override fun areItemsTheSame(oldItem: OpsListItem, newItem: OpsListItem): Boolean =
            (oldItem is OpsListItem.OpItem && newItem is OpsListItem.OpItem && oldItem.op.opName == newItem.op.opName) ||
                (oldItem is OpsListItem.CategoryHeader && newItem is OpsListItem.CategoryHeader && oldItem.title == newItem.title)

        override fun areContentsTheSame(oldItem: OpsListItem, newItem: OpsListItem): Boolean =
            when {
                oldItem is OpsListItem.CategoryHeader && newItem is OpsListItem.CategoryHeader ->
                    oldItem.title == newItem.title
                // Op-Zeilen IMMER neu binden: der Spinner ist ein interaktives Widget —
                // nach einer Auswahl (z.B. "deny") bleibt er sonst auf dem gewählten Text
                // stehen, auch wenn die Op vom System NICHT übernommen wurde (live 09/2026:
                // TURN_SCREEN_ON -> UI zeigte "deny", System war "default"). Durch das
                // Rebind zeigt der Spinner immer den echten Modus aus opStates.
                oldItem is OpsListItem.OpItem && newItem is OpsListItem.OpItem -> false
                else -> false
            }
    }
}
