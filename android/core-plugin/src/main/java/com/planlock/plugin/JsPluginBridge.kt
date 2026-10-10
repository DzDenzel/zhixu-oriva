package com.planlock.plugin

import android.view.Gravity
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.*

/** Implementations read the host's accepted cached projections; they must never fetch a network. */
interface PluginQueryProvider {
    fun queryToday(date: String): String
    fun queryPlan(id: Long): String
    fun queryReport(period: String): String
}

object EmptyPluginQueryProvider : PluginQueryProvider {
    override fun queryToday(date: String): String = error("Host projection unavailable")
    override fun queryPlan(id: Long): String = error("Host projection unavailable")
    override fun queryReport(period: String): String = error("Host projection unavailable")
}

/** JSON is the only value crossing the JS/host boundary. No Context or reflection is exposed. */
class JsPluginBridge(
    private val declared: Set<PluginCapability>,
    private val capabilities: PluginCapabilities,
    private val queries: PluginQueryProvider,
) {
    private var calls = 0

    fun postMessage(raw: String): String {
        if (++calls > MAX_CALLS) return failure("CALL_LIMIT", "Host call limit exceeded")
        if (raw.length > MAX_JSON_CHARS) return failure("INVALID_REQUEST", "Request is too large")
        val request = try { Json.parseToJsonElement(raw).jsonObject }
        catch (_: IllegalArgumentException) { return failure("INVALID_REQUEST", "Request must be a JSON object") }
        val op = request["op"] as? JsonPrimitive
        if (op == null || !op.isString) return failure("INVALID_REQUEST", "Operation is required")
        val needed = OPERATIONS[op.content] ?: return failure("UNKNOWN_OPERATION", "Unsupported host operation")
        if (needed !in declared) return failure("CAPABILITY_NOT_DECLARED", "Capability $needed is not declared")
        val args = request["args"] ?: JsonObject(emptyMap())
        if (args !is JsonObject) return failure("INVALID_REQUEST", "Arguments must be an object")
        return try {
            when (op.content) {
                "query.today" -> {
                    val dateValue = args["date"] as? JsonPrimitive
                    require(args["date"] == null || (dateValue != null && dateValue.isString && dateValue.content.length <= 10))
                    val date = dateValue?.content.orEmpty()
                    if (date.isNotEmpty()) {
                        require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
                        require(runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
                            isLenient = false; timeZone = TimeZone.getTimeZone("UTC")
                        }.parse(date) }.getOrNull() != null)
                    }
                    hostSnapshot { queries.queryToday(date) }
                }
                "query.plan" -> {
                    val id = args["id"] as? JsonPrimitive
                    require(id != null && !id.isString)
                    val number = id.longOrNull ?: throw IllegalArgumentException("Numeric id required")
                    require(number != 0L && number in -9_007_199_254_740_991L..9_007_199_254_740_991L)
                    hostSnapshot { queries.queryPlan(number) }
                }
                "query.report" -> {
                    val period = requiredString(args, "period", 64)
                    require(period in setOf("day", "week", "month"))
                    hostSnapshot { queries.queryReport(period) }
                }
                "home" -> capabilities.home()?.let { it.requestHome(); success() } ?: unavailable()
                "haptic" -> capabilities.haptic()?.let { it.perform(); success() } ?: unavailable()
                "feedback" -> {
                    val code = requiredString(args, "code", 128)
                    capabilities.feedback()?.let { it.emit(code); success() } ?: unavailable()
                }
                "overlay" -> {
                    val text = requiredString(args, "text", 500)
                    val durationValue = args["displayMillis"] as? JsonPrimitive
                    require(args["displayMillis"] == null || (durationValue != null && !durationValue.isString))
                    val duration = if (durationValue == null) 2_000L else durationValue.longOrNull
                        ?: throw IllegalArgumentException("Numeric duration required")
                    require(duration in 200L..10_000L)
                    capabilities.overlay()?.let { overlay ->
                        overlay.show(PluginViewFactory { context ->
                            TextView(context).apply {
                                this.text = text
                                textSize = 20f
                                gravity = Gravity.CENTER
                                setTextColor(0xffffffff.toInt())
                                setBackgroundColor(0xff20242b.toInt())
                                val spacing = (24 * resources.displayMetrics.density).toInt()
                                setPadding(spacing, spacing, spacing, spacing)
                            }
                        }, duration, Runnable {})
                        success()
                    } ?: unavailable()
                }
                else -> failure("UNKNOWN_OPERATION", "Unsupported host operation")
            }
        } catch (_: IllegalArgumentException) {
            failure("INVALID_REQUEST", "Invalid operation arguments")
        } catch (_: Exception) {
            failure("HOST_ERROR", "Host operation failed")
        }
    }

    // JNI uses UTF-8 byte arrays, avoiding Java's modified UTF-8 corruption of supplementary text.
    fun postMessageUtf8(request: ByteArray): ByteArray = postMessage(request.toString(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)

    private fun hostSnapshot(read: () -> String): String = try {
        val snapshot = read()
        require(snapshot.length <= MAX_JSON_CHARS)
        success(Json.parseToJsonElement(snapshot))
    } catch (_: Exception) { failure("HOST_ERROR", "Host projection unavailable") }

    private fun requiredString(args: JsonObject, key: String, maxLength: Int): String {
        val value = args[key] as? JsonPrimitive
        require(value != null && value.isString && value.content.isNotBlank() && value.content.length <= maxLength)
        return value.content
    }

    private fun unavailable() = failure("CAPABILITY_UNAVAILABLE", "Host capability is unavailable")
    private fun success(data: JsonElement = JsonNull) = buildJsonObject { put("ok", true); put("data", data) }.toString()
    private fun failure(code: String, message: String) = buildJsonObject {
        put("ok", false)
        put("code", code); put("error", message)
    }.toString()

    companion object {
        const val MAX_JSON_CHARS = 256 * 1024
        private const val MAX_CALLS = 256
        private val OPERATIONS = mapOf(
            "overlay" to PluginCapability.OVERLAY, "home" to PluginCapability.HOME,
            "haptic" to PluginCapability.HAPTIC, "feedback" to PluginCapability.FEEDBACK,
            "query.today" to PluginCapability.QUERY_TODAY, "query.plan" to PluginCapability.QUERY_PLAN,
            "query.report" to PluginCapability.QUERY_REPORT,
        )
    }
}
