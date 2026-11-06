package xyz.sherlock.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
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

        // urlUser 来自上游的 url 字段（用户主页），不是 urlProbe 探测接口。
        // ILLEGAL 分支下 urlUser 为空，退回到站点首页。
        val target = r.urlUser.ifEmpty { r.site.urlMain }
        holder.url.text = target

        val (label, color) = when (r.status) {
            QueryStatus.CLAIMED -> "命中" to Color.parseColor("#FF2E7D32")
            QueryStatus.AVAILABLE -> "未找到" to Color.parseColor("#FF9E9E9E")
            QueryStatus.UNKNOWN -> (r.context ?: "未知") to Color.parseColor("#FFEF6C00")
            QueryStatus.ILLEGAL -> "格式不符" to Color.parseColor("#FF9E9E9E")
            QueryStatus.WAF -> "被拦截" to Color.parseColor("#FFC62828")
        }
        holder.state.text = label
        holder.state.setTextColor(color)

        // 点击整条结果项，唤醒系统浏览器打开该站点的用户主页
        holder.itemView.setOnClickListener { openInBrowser(it.context, target) }
    }

    /**
     * 用 ACTION_VIEW 隐式意图唤起浏览器。
     * 只接受 http/https，避免上游清单里的异常地址被交给任意 App 处理。
     */
    private fun openInBrowser(context: Context, target: String) {
        if (target.isEmpty()) {
            Toast.makeText(context, "该站点没有可跳转的地址", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            Uri.parse(target)
        } catch (e: Exception) {
            Toast.makeText(context, "地址无效，无法打开", Toast.LENGTH_SHORT).show()
            return
        }
        if (uri.scheme != "http" && uri.scheme != "https") {
            Toast.makeText(context, "地址无效，无法打开", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "设备上没有可用的浏览器", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开链接：${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = items.size
}
