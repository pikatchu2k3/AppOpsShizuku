package de.minipc.appopsshizuku.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import de.minipc.appopsshizuku.R

/** Typsichere Listen-Items der Komponenten-Ansicht. */
sealed class ComponentListItem {
    data class SectionHeader(val title: String) : ComponentListItem()
    /** Zeile mit „Alle aktivieren / deaktivieren" direkt unter einem SectionHeader. */
    data class SectionActions(
        val sectionId: String,
        val components: List<String>,
        val onToggleAll: (List<String>, Boolean) -> Unit
    ) : ComponentListItem()
    data class ComponentItem(
        val componentName: String,
        val label: String,
        val subtitle: String,
        val enabled: Boolean,
        val isActivity: Boolean,
        val hasBootReceiver: Boolean,
        val isExported: Boolean,
        val onToggle: (String, Boolean) -> Unit,
        val onStart: (String) -> Unit
    ) : ComponentListItem()
}

class ComponentsAdapter : ListAdapter<ComponentListItem, RecyclerView.ViewHolder>(ComponentDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_COMPONENT = 1
        private const val VIEW_TYPE_ACTIONS = 2
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.componentHeader)
    }

    class ComponentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.componentLabel)
        val subtitle: TextView = view.findViewById(R.id.componentSubtitle)
        val autostartChip: Chip = view.findViewById(R.id.componentAutostartChip)
        val startButton: ImageButton = view.findViewById(R.id.componentStartButton)
        val switch: SwitchMaterial = view.findViewById(R.id.componentSwitch)
    }

    class SectionActionsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val enableAllButton: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.sectionEnableAllButton)
        val disableAllButton: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.sectionDisableAllButton)
    }

    override fun getItemViewType(position: Int): Int =
        when (getItem(position)) {
            is ComponentListItem.SectionHeader -> VIEW_TYPE_HEADER
            is ComponentListItem.ComponentItem -> VIEW_TYPE_COMPONENT
            is ComponentListItem.SectionActions -> VIEW_TYPE_ACTIONS
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_component_header, parent, false))
            VIEW_TYPE_ACTIONS -> SectionActionsViewHolder(inflater.inflate(R.layout.item_section_actions, parent, false))
            else -> ComponentViewHolder(inflater.inflate(R.layout.item_component, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ComponentListItem.SectionHeader -> {
                (holder as HeaderViewHolder).title.text = item.title
            }
            is ComponentListItem.SectionActions -> {
                val h = holder as SectionActionsViewHolder
                // Ohne Re-Trigger binden (RecyclerView-Recycling!)
                h.enableAllButton.setOnClickListener(null)
                h.disableAllButton.setOnClickListener(null)
                h.enableAllButton.setOnClickListener { item.onToggleAll(item.components, true) }
                h.disableAllButton.setOnClickListener { item.onToggleAll(item.components, false) }
            }
            is ComponentListItem.ComponentItem -> {
                val h = holder as ComponentViewHolder
                h.label.text = item.label
                h.subtitle.text = item.subtitle
                h.subtitle.visibility = if (item.subtitle.isEmpty()) View.GONE else View.VISIBLE
                h.autostartChip.visibility = if (item.hasBootReceiver) View.VISIBLE else View.GONE
                h.startButton.visibility = if (item.isActivity) View.VISIBLE else View.GONE

                // Switch ohne Re-Trigger binden (RecyclerView-Recycling!)
                h.switch.setOnCheckedChangeListener(null)
                h.switch.isChecked = item.enabled
                h.switch.setOnCheckedChangeListener { _, checked ->
                    if (checked != item.enabled) item.onToggle(item.componentName, checked)
                }
                h.startButton.setOnClickListener { item.onStart(item.componentName) }
            }
        }
    }

    class ComponentDiffCallback : DiffUtil.ItemCallback<ComponentListItem>() {
        override fun areItemsTheSame(oldItem: ComponentListItem, newItem: ComponentListItem): Boolean =
            when {
                oldItem is ComponentListItem.ComponentItem && newItem is ComponentListItem.ComponentItem ->
                    oldItem.componentName == newItem.componentName
                oldItem is ComponentListItem.SectionHeader && newItem is ComponentListItem.SectionHeader ->
                    oldItem.title == newItem.title
                oldItem is ComponentListItem.SectionActions && newItem is ComponentListItem.SectionActions ->
                    oldItem.sectionId == newItem.sectionId
                else -> false
            }

        // Datenvergleich ohne Lambdas (sonst immer ungleich -> permanentes Rebind)
        override fun areContentsTheSame(oldItem: ComponentListItem, newItem: ComponentListItem): Boolean =
            when {
                oldItem is ComponentListItem.ComponentItem && newItem is ComponentListItem.ComponentItem ->
                    oldItem.componentName == newItem.componentName &&
                        oldItem.label == newItem.label &&
                        oldItem.subtitle == newItem.subtitle &&
                        oldItem.enabled == newItem.enabled &&
                        oldItem.isActivity == newItem.isActivity &&
                        oldItem.hasBootReceiver == newItem.hasBootReceiver &&
                        oldItem.isExported == newItem.isExported
                oldItem is ComponentListItem.SectionHeader && newItem is ComponentListItem.SectionHeader ->
                    oldItem.title == newItem.title
                oldItem is ComponentListItem.SectionActions && newItem is ComponentListItem.SectionActions ->
                    oldItem.sectionId == newItem.sectionId &&
                        oldItem.components == newItem.components
                else -> false
            }
    }
}
