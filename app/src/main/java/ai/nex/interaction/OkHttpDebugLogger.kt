package ai.nex.interaction

import android.util.Log
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object OkHttpDebugLogger {
    private const val MAX_RESPONSE_LOG_BYTES = 64L * 1024L

    fun createClient(tag: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                logRequest(tag, request)
                val startNs = System.nanoTime()
                try {
                    val response = chain.proceed(request)
                    logResponse(
                        tag = tag,
                        request = request,
                        code = response.code,
                        message = response.message,
                        tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs),
                        body = response.peekBody(MAX_RESPONSE_LOG_BYTES).string(),
                    )
                    response
                } catch (throwable: Throwable) {
                    Log.e(tag, "<-- HTTP FAILED ${request.method} ${request.url}", throwable)
                    throw throwable
                }
            }
            .build()
    }

    private fun logRequest(tag: String, request: Request) {
        Log.d(tag, "--> ${request.method} ${request.url}")
        logHeaders(tag, request.headers)
        request.body?.let { body ->
            val rawBody = Buffer().apply { body.writeTo(this) }
                .readString(body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8)
            if (rawBody.isNotBlank()) {
                Log.d(tag, rawBody)
            }
        }
        Log.d(tag, "--> END ${request.method}")
    }

    private fun logResponse(
        tag: String,
        request: Request,
        code: Int,
        message: String,
        tookMs: Long,
        body: String,
    ) {
        Log.d(tag, "<-- $code ${message.ifBlank { "" }} ${request.url} (${tookMs}ms)")
        if (body.isNotBlank()) {
            Log.d(tag, body)
        }
        Log.d(tag, "<-- END HTTP")
    }

    private fun logHeaders(tag: String, headers: Headers) {
        headers.forEach { (name, value) ->
            Log.d(tag, "$name: $value")
        }
    }
}
