package xyz.sherlock.mobile.engine

import org.json.JSONObject

/** 与上游 QueryStatus 保持一致 */
enum class QueryStatus {
    CLAIMED,    // 命中：该用户名在该站存在
    AVAILABLE,  // 未命中
    UNKNOWN,    // 请求出错，无法判定
    ILLEGAL,    // 用户名不符合该站规则
    WAF,        // 被 WAF / 反爬拦截
}

/**
 * 上游 data.json 中单个站点的探测规则。
 * 字段含义见上游 resources/data.schema.json。
 */
data class SiteRule(
    val name: String,
    val urlMain: String,
    val url: String,
    val urlProbe: String? = null,
    val errorTypes: List<String> = listOf("status_code"),
    val errorMsg: List<String> = emptyList(),
    val errorCodes: List<Int> = emptyList(),
    val errorUrl: String? = null,
    val regexCheck: String? = null,
    val requestMethod: String? = null,
    val requestPayload: JSONObject? = null,
    val headers: Map<String, String> = emptyMap(),
    val isNsfw: Boolean = false,
    val usernameClaimed: String = "",
)

data class ScanResult(
    val site: SiteRule,
    val status: QueryStatus,
    val urlUser: String,
    val httpStatus: Int? = null,
    val elapsedMs: Long? = null,
    val context: String? = null,
)
