package xyz.sherlock.mobile.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import xyz.sherlock.mobile.R
import xyz.sherlock.mobile.engine.QueryStatus
import xyz.sherlock.mobile.engine.ScanResult

class ResultAdapter : RecyclerView.Adapter<ResultAdapter.ViewHolder>() {

    private val items = ArrayList<ScanResult>()

    fun add(result: ScanResult) {
        items.add(result)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun all(): List<ScanResult> = items.toList()

    fun claimedCount(): Int = items.count { it.status == QueryStatus.CLAIMED }

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvSiteName)
        val state: TextView = v.findViewById(R.id.tvState)
        val url: TextView = v.findViewById(R.id.tvUrl)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val r = items[position]
        holder.name.text = r.site.name
        holder.url.text = r.urlUser.ifEmpty { r.site.urlMain }

        val (label, color) = when (r.status) {
            QueryStatus.CLAIMED -> "命中" to Color.parseColor("#FF2E7D32")
            QueryStatus.AVAILABLE -> "未找到" to Color.parseColor("#FF9E9E9E")
            QueryStatus.UNKNOWN -> (r.context ?: "未知") to Color.parseColor("#FFEF6C00")
            QueryStatus.ILLEGAL -> "格式不符" to Color.parseColor("#FF9E9E9E")
            QueryStatus.WAF -> "被拦截" to Color.parseColor("#FFC62828")
        }
        holder.state.text = label
        holder.state.setTextColor(color)
    }

    override fun getItemCount(): Int = items.size
}
