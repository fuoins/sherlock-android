package xyz.sherlock.mobile.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 站点规则仓库：解析上游 data.json 快照，支持在线拉取最新 manifest 与误报排除列表。
 * 规则不随 App 发版更新，与上游 sites.py 的设计一致。
 */
object SiteRepository {

    private const val MANIFEST_URL = "https://data.sherlockproject.xyz"
    private const val EXCLUSIONS_URL =
        "https://raw.githubusercontent.com/sherlock-project/sherlock/refs/heads/exclusions/false_positive_exclusions.txt"
    private const val CACHE_FILE = "sites.json"
    private const val EXCLUSIONS_FILE = "exclusions.txt"

    /** 优先使用在线更新后的缓存，否则读内置 assets 快照 */
    fun load(context: Context): List<SiteRule> {
        val text = try {
            val cached = File(context.filesDir, CACHE_FILE)
            if (cached.exists() && cached.length() > 1024) {
                cached.readText()
            } else {
                readAsset(context)
            }
        } catch (e: Exception) {
            readAsset(context)
        }
        return parse(text, readExclusions(context))
    }

    private fun readAsset(context: Context): String =
        context.assets.open("sites.json").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun readExclusions(context: Context): Set<String> = try {
        val f = File(context.filesDir, EXCLUSIONS_FILE)
        if (!f.exists()) emptySet()
        else f.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun str(o: JSONObject, key: String): String? =
        if (o.has(key) && !o.isNull(key)) o.optString(key).takeIf { it.isNotEmpty() } else null

    /** 把上游 data.json 解析成 SiteRule 列表 */
    fun parse(text: String, excluded: Set<String> = emptySet()): List<SiteRule> {
        val root = JSONObject(text)
        val rules = ArrayList<SiteRule>(root.length())
        val names = root.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (name.startsWith("$") || name in excluded) continue
            val o = root.optJSONObject(name) ?: continue
            val url = o.optString("url", "")
            if (url.isEmpty()) continue

            val errorTypes: List<String> = when (val et = o.opt("errorType")) {
                is JSONArray -> (0 until et.length()).map { et.optString(it) }.filter { it.isNotEmpty() }
                is String -> listOf(et)
                else -> listOf("status_code")
            }

            val errorMsg: List<String> = when (val em = o.opt("errorMsg")) {
                is JSONArray -> (0 until em.length()).map { em.optString(it) }.filter { it.isNotEmpty() }
                is String -> listOf(em)
                else -> emptyList()
            }

            val errorCodes = ArrayList<Int>()
            when (val ec = o.opt("errorCode")) {
                is JSONArray -> for (i in 0 until ec.length()) errorCodes.add(ec.optInt(i, -1))
                is Number -> errorCodes.add(ec.toInt())
            }

            val headers = HashMap<String, String>()
            o.optJSONObject("headers")?.let { h ->
                val hk = h.keys()
                while (hk.hasNext()) {
                    val k = hk.next()
                    headers[k] = h.optString(k)
                }
            }

            rules.add(
                SiteRule(
                    name = name,
                    urlMain = o.optString("urlMain", ""),
                    url = url,
                    urlProbe = str(o, "urlProbe"),
                    errorTypes = errorTypes,
                    errorMsg = errorMsg,
                    errorCodes = errorCodes.filter { it >= 0 },
                    errorUrl = str(o, "errorUrl"),
                    regexCheck = str(o, "regexCheck"),
                    requestMethod = str(o, "request_method"),
                    requestPayload = o.optJSONObject("request_payload"),
                    headers = headers,
                    isNsfw = o.optBoolean("isNSFW", false),
                    usernameClaimed = o.optString("username_claimed", ""),
                )
            )
        }
        return rules.sortedBy { it.name.lowercase() }
    }

    /** 在线更新规则，返回更新后的站点数量 */
    suspend fun updateFromNetwork(context: Context): Int = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val count = client.newCall(
            Request.Builder().url(MANIFEST_URL)
                .header("User-Agent", "Sherlock-Mobile/0.1").build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) error("manifest HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val parsed = parse(body).size
            if (parsed < 100) error("manifest 内容异常（仅 $parsed 个站点）")
            File(context.filesDir, CACHE_FILE).writeText(body, Charsets.UTF_8)
            parsed
        }

        // 排除列表拉取失败不影响主流程
        try {
            client.newCall(Request.Builder().url(EXCLUSIONS_URL).build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    File(context.filesDir, EXCLUSIONS_FILE)
                        .writeText(resp.body?.string().orEmpty(), Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        count
    }
}
