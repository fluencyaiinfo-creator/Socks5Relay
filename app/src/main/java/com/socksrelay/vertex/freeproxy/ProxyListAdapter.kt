package com.socksrelay.vertex.freeproxy

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.socksrelay.vertex.R

class ProxyListAdapter(
    private var proxies: List<FreeProxy>,
    private val isFavorite: (FreeProxy) -> Boolean,
    private val onCopy: (FreeProxy) -> Unit,
    private val onTest: (FreeProxy, resultView: TextView, badgeView: View) -> Unit,
    private val onToggleFavorite: (FreeProxy, favoriteButton: ImageButton) -> Unit
) : RecyclerView.Adapter<ProxyListAdapter.ViewHolder>() {

    fun submitList(newProxies: List<FreeProxy>) {
        proxies = newProxies
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val healthBadge: View = view.findViewById(R.id.healthBadge)
        val protocolBadge: TextView = view.findViewById(R.id.protocolBadge)
        val hostPort: TextView = view.findViewById(R.id.hostPortText)
        val favoriteButton: ImageButton = view.findViewById(R.id.favoriteButton)
        val authIndicator: TextView = view.findViewById(R.id.authIndicatorText)
        val copyButton: View = view.findViewById(R.id.copyButton)
        val testButton: View = view.findViewById(R.id.testButton)
        val testResult: TextView = view.findViewById(R.id.testResultInlineText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_proxy, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val proxy = proxies[position]
        setHealthBadge(holder.healthBadge, ProxyHealthStore.healthOf(proxy))
        holder.protocolBadge.text = proxy.protocol.name
        holder.hostPort.text = "${proxy.host}:${proxy.port}"

        if (!proxy.username.isNullOrEmpty()) {
            holder.authIndicator.visibility = View.VISIBLE
            holder.authIndicator.text = "\uD83D\uDD11 requires username/password"
        } else {
            holder.authIndicator.visibility = View.GONE
        }

        setFavoriteIcon(holder.favoriteButton, isFavorite(proxy))

        // Reset per-row transient state (RecyclerView recycles views).
        holder.testResult.visibility = View.GONE
        holder.testResult.text = ""

        holder.copyButton.setOnClickListener { onCopy(proxy) }
        holder.testButton.setOnClickListener { onTest(proxy, holder.testResult, holder.healthBadge) }
        holder.favoriteButton.setOnClickListener { onToggleFavorite(proxy, holder.favoriteButton) }
    }

    override fun getItemCount() = proxies.size

    companion object {
        fun setFavoriteIcon(button: ImageButton, favorite: Boolean) {
            button.setImageResource(
                if (favorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
            )
        }

        /** Also usable directly from an Activity right after a Test result, to update one row without a full list rebind — see CountryProxiesActivity/FavoritesActivity. */
        fun setHealthBadge(badgeView: View, health: ProxyHealth) {
            val colorRes = when (health) {
                ProxyHealth.GOOD -> R.color.proxy_health_good
                ProxyHealth.BAD -> R.color.proxy_health_bad
                ProxyHealth.UNKNOWN -> R.color.proxy_health_unknown
            }
            badgeView.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(badgeView.context, colorRes)
            )
        }
    }
}
