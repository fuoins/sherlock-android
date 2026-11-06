package xyz.sherlock.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.sherlock.mobile.engine.QueryStatus
import xyz.sherlock.mobile.engine.ScanResult
import xyz.sherlock.mobile.engine.Scanner
import xyz.sherlock.mobile.engine.SiteRepository
import xyz.sherlock.mobile.ui.ResultAdapter
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var cbShowAll: CheckBox
    private lateinit var cbNsfw: CheckBox
    private lateinit var btnScan: Button
    private lateinit var btnUpdate: Button
    private lateinit var btnShare: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var adapter: ResultAdapter

    private var running = false
    private var lastUsername = ""
    private var lastResults: List<ScanResult> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUsername = findViewById(R.id.etUsername)
        cbShowAll = findViewById(R.id.cbShowAll)
        cbNsfw = findViewById(R.id.cbNsfw)
        btnScan = findViewById(R.id.btnScan)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnShare = findViewById(R.id.btnShare)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        val rv = findViewById<RecyclerView>(R.id.rvResults)
        adapter = ResultAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnScan.setOnClickListener { startScan() }
        btnUpdate.setOnClickListener { updateRules() }
        btnShare.setOnClickListener { shareResults() }

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                SiteRepository.load(this@MainActivity).size
            }
            tvStatus.text = "内置规则 $count 个站点 · 仅 arm64-v8a"
        }
    }

    private fun startScan() {
        if (running) {
            toast("正在扫描中，请稍候")
            return
        }
        val username = etUsername.text.toString().trim()
        if (username.isEmpty()) {
            toast("请输入用户名")
            return
        }

        val showAll = cbShowAll.isChecked
        val includeNsfw = cbNsfw.isChecked
        running = true
        btnScan.isEnabled = false
        adapter.clear()
        progress.progress = 0
        tvStatus.text = "正在加载规则…"

        lifecycleScope.launch {
            try {
                val rules = withContext(Dispatchers.IO) {
                    SiteRepository.load(this@MainActivity)
                }
                lastUsername = username
                tvStatus.text = "开始扫描 ${rules.size} 个站点…"

                val scanner = Scanner(concurrency = 20, timeoutSeconds = 20)
                val results = scanner.scan(username, rules, includeNsfw) { done, total, result ->
                    withContext(Dispatchers.Main) {
                        progress.max = total
                        progress.progress = done
                        if (result.status == QueryStatus.CLAIMED || showAll) {
                            adapter.add(result)
                        }
                        tvStatus.text = "进度 $done / $total · 已命中 ${adapter.claimedCount()}"
                    }
                }

                lastResults = results
                saveResults(username, results)

                withContext(Dispatchers.Main) {
                    val claimed = results.count { it.status == QueryStatus.CLAIMED }
                    val waf = results.count { it.status == QueryStatus.WAF }
                    val unknown = results.count { it.status == QueryStatus.UNKNOWN }
                    tvStatus.text =
                        "完成：命中 $claimed / ${results.size} 个站点 · 被拦截 $waf · 错误 $unknown"
                    running = false
                    btnScan.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("扫描失败：${e.message}")
                    tvStatus.text = "扫描失败：${e.message}"
                    running = false
                    btnScan.isEnabled = true
                }
            }
        }
    }

    private fun updateRules() {
        if (running) {
            toast("扫描中，请稍后再更新")
            return
        }
        btnUpdate.isEnabled = false
        toast("正在更新站点规则…")
        lifecycleScope.launch {
            try {
                val n = SiteRepository.updateFromNetwork(this@MainActivity)
                withContext(Dispatchers.Main) {
                    toast("规则已更新：$n 个站点")
                    tvStatus.text = "规则已更新：$n 个站点 · 仅 arm64-v8a"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("更新失败：${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) { btnUpdate.isEnabled = true }
            }
        }
    }

    private fun shareResults() {
        if (lastResults.isEmpty()) {
            toast("还没有扫描结果")
            return
        }
        val claimed = lastResults.filter { it.status == QueryStatus.CLAIMED }
        val sb = StringBuilder()
        sb.append("Sherlock Mobile 查询结果：").append(lastUsername).append('\n')
        sb.append("命中 ").append(claimed.size).append(" 个站点\n\n")
        claimed.forEach {
            sb.append(it.site.name).append(": ").append(it.urlUser).append('\n')
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            putExtra(Intent.EXTRA_SUBJECT, "Sherlock: $lastUsername")
        }
        startActivity(Intent.createChooser(send, "分享结果"))
    }

    private suspend fun saveResults(username: String, results: List<ScanResult>) =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "results").apply { mkdirs() }
                val safe = username.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val claimed = results.filter { it.status == QueryStatus.CLAIMED }
                val sb = StringBuilder()
                sb.append("Sherlock Mobile 查询结果：").append(username).append('\n')
                sb.append("共扫描 ").append(results.size)
                    .append(" 个站点，命中 ").append(claimed.size).append(" 个\n\n")
                claimed.forEach {
                    sb.append(it.site.name).append(": ").append(it.urlUser).append('\n')
                }
                File(dir, "$safe.txt").writeText(sb.toString(), Charsets.UTF_8)
            } catch (e: Exception) {
                // 文件写入失败不影响扫描结果
            }
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
