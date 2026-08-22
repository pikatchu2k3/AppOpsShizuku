package de.minipc.appopsshizuku.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.minipc.appopsshizuku.R

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val firstInstallTime: Long,
    val lastUpdateTime: Long
)

class AppListAdapter(
    private val onItemClick: (AppItem) -> Unit
) : ListAdapter<AppItem, AppListAdapter.ViewHolder>(AppDiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.itemIcon)
        val label: TextView = view.findViewById(R.id.itemLabel)
        val packageName: TextView = view.findViewById(R.id.itemPackage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.icon.setImageDrawable(item.icon)
        holder.label.text = item.label
        holder.packageName.text = item.packageName
        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<AppItem>() {
        override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
            oldItem.packageName == newItem.packageName

        // Nur Datenfelder vergleichen: Das Drawable ist per Referenz immer
        // verschieden -> volles Rebind bei jedem Resume. Icon wird separat
        // in onBindViewHolder gesetzt.
        override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean =
            oldItem.packageName == newItem.packageName &&
                oldItem.label == newItem.label &&
                oldItem.lastUpdateTime == newItem.lastUpdateTime
    }
}
