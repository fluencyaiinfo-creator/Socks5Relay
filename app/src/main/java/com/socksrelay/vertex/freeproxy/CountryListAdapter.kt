package com.socksrelay.vertex.freeproxy

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.socksrelay.vertex.R

class CountryListAdapter(
    private var groups: List<FreeProxyRepository.CountryGroup>,
    private val onClick: (FreeProxyRepository.CountryGroup) -> Unit
) : RecyclerView.Adapter<CountryListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val flag: TextView = view.findViewById(R.id.flagText)
        val name: TextView = view.findViewById(R.id.countryNameText)
        val count: TextView = view.findViewById(R.id.countText)
    }

    fun submitList(newGroups: List<FreeProxyRepository.CountryGroup>) {
        groups = newGroups
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_country, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val group = groups[position]
        holder.flag.text = CountryFlags.flagEmoji(group.countryCode)
        holder.name.text = group.countryName
        holder.count.text = group.proxies.size.toString()
        holder.itemView.setOnClickListener { onClick(group) }
    }

    override fun getItemCount() = groups.size
}
