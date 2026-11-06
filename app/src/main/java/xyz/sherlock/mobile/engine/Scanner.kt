package xyz.sherlock.mobile.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Sherlock 探测引擎（Kotlin 版）。
 *
 * 对应上游 sherlock_project/sherlock.py 中的 sherlock()：
 * 一次性并发发出全部请求，再按 data.json 里的 errorType 规则逐站判定。
 */
class Scanner(
    private val concurrency: Int = 20,
    private val timeoutSeconds: Int = 20,
) {
    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64; rv:129.0) Gecko/20100101 Firefox/129.0"

        // 与上游 WAFHitMsgs 一致：命中即判定为被拦截，避免把挑战页误报成"账号存在"
        private val WAF_FINGERPRINTS = listOf(
            ".loading-spinner{visibility:hidden}body.no-js .challenge-running{display:none}body.dark",
            "<span id=\"challenge-error-text\">",
            "AwsWafIntegration.forceRefreshToken",
            "perimeterxIdentifiers",
        )
    }

    private fun buildClient(follow: Boolean): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .callTimeout((timeoutSeconds + 5).toLong(), TimeUnit.SECONDS)
        .followRedirects(follow)
        .followSslRedirects(follow)
        .retryOnConnectionFailure(true)
        .build()

    private val followClient = buildClient(true)
    private val noRedirectClient = followClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun scan(
        username: String,
        rules: List<SiteRule>,
        includeNsfw: Boolean = false,
        onProgress: suspend (done: Int, total: Int, result: ScanResult) -> Unit,
    ): List<ScanResult> = withContext(Dispatchers.IO) {
        val targets = rules.filter { includeNsfw || !it.isNsfw }
        val total = targets.size
        val semaphore = Semaphore(concurrency)

        supervisorScope {
            val jobs = targets.map { rule ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            probe(username, rule)
                        } catch (e: Exception) {
                            ScanResult(
                                site = rule,
                                status = QueryStatus.UNKNOWN,
                                urlUser = interpolate(rule.url, username),
                                context = "${e.javaClass.simpleName}: ${e.message ?: ""}",
                            )
                        }
                    }
                }
            }

            val out = ArrayList<ScanResult>(total)
            jobs.forEachIndexed { index, job ->
                val r = job.await()
                out.add(r)
                onProgress(index + 1, total, r)
            }
            out
        }
    }

    private fun probe(username: String, rule: SiteRule): ScanResult {
        val urlUser = interpolate(rule.url, username.replace(" ", "%20"))

        // regexCheck：用户名不合法就不发请求，对应上游 ILLEGAL 分支
        val regex = rule.regexCheck
        if (!regex.isNullOrEmpty()) {
            val legal = try {
                Regex(regex).containsMatchIn(username)
            } catch (e: Exception) {
                true
            }
            if (!legal) {
                return ScanResult(rule, QueryStatus.ILLEGAL, "", context = "用户名格式不符合该站规则")
            }
        }

        val types = rule.errorTypes.ifEmpty { listOf("status_code") }
        val explicit = rule.requestMethod?.uppercase()
        // 上游：仅 status_code 检测时用 HEAD 省流量，其余需要正文用 GET
        val fallback = if (types.size == 1 && types[0] == "status_code" && rule.requestPayload == null) {
            "HEAD"
        } else {
            "GET"
        }
        var method = explicit ?: fallback
        // OkHttp 不允许 GET 带 body，带 payload 的站点统一走 POST
        if (rule.requestPayload != null && method == "GET") method = "POST"

        val probeUrl = interpolate(rule.urlProbe ?: urlUser, username)
        val allowRedirects = "response_url" !in types
        val client = if (allowRedirects) followClient else noRedirectClient

        val builder = Request.Builder().url(probeUrl).header("User-Agent", USER_AGENT)
        rule.headers.forEach { (k, v) -> builder.header(k, v) }

        val jsonType = "application/json; charset=utf-8".toMediaType()
        val payload: RequestBody? = rule.requestPayload?.let {
            interpolateJson(it, username).toString().toRequestBody(jsonType)
        }
        val body: RequestBody = payload ?: "{}".toRequestBody(jsonType)

        when (method) {
            "HEAD" -> builder.head()
            "POST" -> builder.post(body)
            "PUT" -> builder.put(body)
            else -> builder.get()
        }

        val start = System.currentTimeMillis()
        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val code = resp.code
                val text = try {
                    resp.body?.string().orEmpty()
                } catch (e: Exception) {
                    ""
                }
                val elapsed = System.currentTimeMillis() - start
                ScanResult(rule, decide(rule, types, code, text), urlUser, code, elapsed)
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            val ctx = when (e) {
                is SocketTimeoutException -> "请求超时"
                is UnknownHostException -> "DNS / 连接失败"
                is IOException -> "网络错误 (${e.javaClass.simpleName})"
                else -> "${e.javaClass.simpleName}: ${e.message ?: ""}"
            }
            ScanResult(rule, QueryStatus.UNKNOWN, urlUser, null, elapsed, ctx)
        }
    }

    /** 三种 errorType 的判定，顺序与上游一致 */
    private fun decide(rule: SiteRule, types: List<String>, code: Int, text: String): QueryStatus {
        if (WAF_FINGERPRINTS.any { text.contains(it) }) return QueryStatus.WAF

        var status = QueryStatus.UNKNOWN

        // message：正文里没有"用户不存在"文案，就认为账号存在
        if ("message" in types) {
            status = if (rule.errorMsg.none { text.contains(it) }) {
                QueryStatus.CLAIMED
            } else {
                QueryStatus.AVAILABLE
            }
        }

        // status_code：2xx 认为存在，errorCode 命中或非 2xx 认为不存在
        if ("status_code" in types && status != QueryStatus.AVAILABLE) {
            status = QueryStatus.CLAIMED
            if (code in rule.errorCodes || code >= 300 || code < 200) {
                status = QueryStatus.AVAILABLE
            }
        }

        // response_url：已关闭重定向，2xx 即存在
        if ("response_url" in types && status != QueryStatus.AVAILABLE) {
            status = if (code in 200..299) QueryStatus.CLAIMED else QueryStatus.AVAILABLE
        }

        if (status == QueryStatus.UNKNOWN) {
            status = if (code in 200..299) QueryStatus.CLAIMED else QueryStatus.AVAILABLE
        }
        return status
    }

    private fun interpolate(s: String, username: String): String = s.replace("{}", username)

    /** 递归替换 request_payload 中的 {} 占位符（上游 payload 存在嵌套结构） */
    private fun interpolateJson(obj: JSONObject, username: String): JSONObject {
        val out = JSONObject()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            out.put(k, when (v) {
                is String -> v.replace("{}", username)
                is JSONObject -> interpolateJson(v, username)
                is JSONArray -> interpolateArray(v, username)
                else -> v
            })
        }
        return out
    }

    private fun interpolateArray(arr: JSONArray, username: String): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            out.put(when (v) {
                is String -> v.replace("{}", username)
                is JSONObject -> interpolateJson(v, username)
                is JSONArray -> interpolateArray(v, username)
                else -> v
            })
        }
        return out
    }
}
