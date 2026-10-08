package com.socksrelay.vertex

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class InstalledAppInfo(val packageName: String, val label: String, val icon: Drawable?)

class AppPickerAdapter(
    private val apps: List<InstalledAppInfo>,
    private val isSelected: (String) -> Boolean,
    private val onToggle: (InstalledAppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appNameText)
        val checkbox: CheckBox = view.findViewById(R.id.appCheckbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.icon.setImageDrawable(app.icon)
        holder.name.text = app.label
        holder.checkbox.isChecked = isSelected(app.packageName)
        holder.itemView.setOnClickListener {
            val newState = !holder.checkbox.isChecked
            holder.checkbox.isChecked = newState
            onToggle(app, newState)
        }
    }

    override fun getItemCount() = apps.size
}
