package ai.conv.core.net

import ai.conv.BuildConfig
import android.util.Log
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.TimeUnit

class HttpLogger : Interceptor {
    companion object {
        private val SENSITIVE_HEADERS = setOf(
            "auth",
            "token",
            "cert",
            "secret",
            "appId",
            "app_id"
        )

        private val SENSITIVE_PARAMS = setOf(
            "auth",
            "token",
            "password",
            "cert",
            "secret",
            "phone",
            "appId",
            "app_id"
        )

        private val EXCLUDE_PATHS = setOf(
            "/heartbeat",
            "/ping",
        )

        private val EXCLUDE_CONTENT_TYPES = setOf(
            "multipart/form-data",
            "application/octet-stream",
            "image/*",
            "file",
            "audio/*",
            "video/*"
        )

        private val SENSITIVE_PATH_KEYWORDS = setOf(
            "upload",
            "file",
            "media"
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val requestId = UUID.randomUUID().toString().substring(0, 8)

        val shouldSkipCompletely = shouldSkipLoggingCompletely(request)
        val logResultOnly = shouldLogResultOnly(request)

        if (!shouldSkipCompletely && !logResultOnly) {
            Log.d("[$requestId]-Request", buildLogContent(request))
        } else if (logResultOnly) {
            Log.d("[$requestId]-Request", "Large file upload request: ${request.method} ${request.url}")
        }

        val startNs = System.nanoTime()
        val response = chain.proceed(request)

        if (!shouldSkipCompletely) {
            logResponse(response, startNs, url, requestId)
        }

        return response
    }

    private fun buildLogContent(request: Request): String {
        val logContent = StringBuilder()
        logContent.append("curl -X ${request.method}")

        val headers = mutableListOf<Pair<String, String>>()
        request.body?.contentType()?.let { contentType ->
            headers.add("Content-Type" to contentType.toString())
        }
        request.headers.forEach { (name, value) ->
            if (name.lowercase() != "content-type") {
                headers.add(name to value)
            }
        }

        if (headers.isNotEmpty()) {
            logContent.append(" -H \"")
            headers.forEachIndexed { index, (name, value) ->
                if (index > 0) {
                    logContent.append(";")
                }
                val safeValue =
                    if (!BuildConfig.DEBUG && SENSITIVE_HEADERS.any { name.lowercase().contains(it) }) "***"
                    else value
                logContent.append("$name:$safeValue")
            }
            logContent.append("\"")
        }

        request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            val charset = body.contentType()?.charset() ?: Charset.defaultCharset()
            val bodyString = buffer.readString(charset)
            logContent.append(" -d '${formatJsonString(bodyString)}'")
        }

        logContent.append(" \"${buildUrlString(request.url)}\"")
        return logContent.toString()
    }

    private fun formatJsonString(input: String): String {
        return try {
            when {
                input.trim().startsWith("{") -> JSONObject(input).toString(4)
                input.trim().startsWith("[") -> JSONArray(input).toString(4)
                else -> input
            }
        } catch (_: Exception) {
            input
        }
    }

    private fun buildUrlString(url: HttpUrl): String {
        return buildString {
            append(url.scheme).append("://").append(url.host)
            if (url.port != 80 && url.port != 443) {
                append(":").append(url.port)
            }
            append(url.encodedPath)
            if (url.queryParameterNames.isNotEmpty()) {
                append("?")
                url.queryParameterNames.forEachIndexed { index, name ->
                    if (index > 0) append("&")
                    append("$name=${url.queryParameter(name)}")
                }
            }
        }
    }

    private fun shouldSkipLoggingCompletely(request: Request): Boolean {
        return EXCLUDE_PATHS.any { path -> request.url.encodedPath.contains(path) }
    }

    private fun shouldLogResultOnly(request: Request): Boolean {
        val path = request.url.encodedPath.lowercase()
        if (SENSITIVE_PATH_KEYWORDS.any { keyword -> path.contains(keyword) }) {
            return true
        }

        request.body?.contentType()?.let { contentType ->
            val contentTypeString = contentType.toString()
            if (EXCLUDE_CONTENT_TYPES.any { type ->
                    if (type.endsWith("/*")) {
                        contentTypeString.startsWith(type.removeSuffix("/*"))
                    } else {
                        contentTypeString == type
                    }
                }) {
                return true
            }
        }

        return false
    }

    private fun logResponse(response: Response, startNs: Long, url: HttpUrl, requestId: String) {
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        val responseBody = response.body
        val contentLength = responseBody?.contentLength()
        val bodySize = if (contentLength != -1L) "$contentLength-byte" else "unknown-length"

        val logContent = buildString {
            append("${response.code} ${response.message} for ${buildUrlString(url)}")
            append(" (${tookMs}ms")
            if (response.networkResponse != null && response.networkResponse != response) {
                append(", $bodySize body")
            }
            append(")")

            response.headers.forEach { (name, value) ->
                append("\n$name: $value")
            }

            responseBody.let { body ->
                val contentType = body?.contentType()
                if (contentType?.type == "application" &&
                    (contentType.subtype.contains("json") || contentType.subtype.contains("xml"))
                ) {
                    val source = body!!.source()
                    source.request(Long.MAX_VALUE)
                    val buffer = source.buffer
                    val charset = contentType.charset() ?: Charset.defaultCharset()
                    if (contentLength != 0L) {
                        append("\n\n")
                        var bodyString = buffer.clone().readString(charset)
                        if (!BuildConfig.DEBUG) {
                            SENSITIVE_PARAMS.forEach { param ->
                                bodyString = bodyString.replace(
                                    Regex(""""([^"]*$param[^"]*)"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE),
                                    """"$1":"***""""
                                )
                            }
                        }
                        append(formatJsonString(bodyString))
                    }
                }
            }
        }

        Log.d("[$requestId]-Response", logContent)
    }
}
