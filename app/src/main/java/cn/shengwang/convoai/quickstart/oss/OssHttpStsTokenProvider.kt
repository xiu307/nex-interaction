package cn.shengwang.convoai.quickstart.oss

import android.util.Log
import cn.shengwang.convoai.quickstart.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * HTTP GET [BuildConfig.OSS_STS_TOKEN_URL] 拉取 STS（与 Android `OssHttpStsTokenProvider` 一致）。
 */
class OssHttpStsTokenProvider(
    private val tokenUrl: String = BuildConfig.OSS_STS_TOKEN_URL,
    private val client: OkHttpClient = defaultClient(),
) : OssStsTokenProvider {

    private val gson = Gson()

    override suspend fun getStsCredentials(): OssStsCredentials? = withContext(Dispatchers.IO) {
        val url = tokenUrl.trim()
        if (url.isEmpty()) {
            Log.d("OssHttpSts", "OSS_STS_TOKEN_URL empty; skip STS request")
            return@withContext null
        }
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body.string()
            parseStsBody(body)
        }
    }

    private fun parseStsBody(json: String): OssStsCredentials? {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val data = resolveCredentialObject(root) ?: return null
            val id = getStringInsensitive(data, "accessKeyId", "AccessKeyId") ?: return null
            val secret = getStringInsensitive(data, "accessKeySecret", "AccessKeySecret") ?: return null
            val token = getStringInsensitive(data, "securityToken", "SecurityToken") ?: return null
            val exp = parseExpirationFrom(data) ?: parseExpirationFrom(root)
            OssStsCredentials(id, secret, token, exp)
        } catch (_: Exception) {
            gson.fromJson(json, StsEnvelope::class.java).data
        }
    }

    private fun resolveCredentialObject(root: JsonObject): JsonObject? = when {
        root.has("data") && root.get("data").isJsonObject -> root.getAsJsonObject("data")
        root.has("obj") && root.get("obj").isJsonObject -> root.getAsJsonObject("obj")
        root.has("Credentials") && root.get("Credentials").isJsonObject -> root.getAsJsonObject("Credentials")
        root.has("accessKeyId") || root.has("AccessKeyId") -> root
        else -> null
    }

    private fun getStringInsensitive(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            if (!obj.has(k)) continue
            val e = obj.get(k) ?: continue
            if (e.isJsonNull || !e.isJsonPrimitive) continue
            val p = e.asJsonPrimitive
            if (p.isString) return p.asString
        }
        return null
    }

    private fun parseExpirationFrom(obj: JsonObject): Long? {
        val keys = listOf("expiration", "Expiration", "expiredTime", "ExpiredTime")
        for (k in keys) {
            if (!obj.has(k)) continue
            parseExpirationElement(obj.get(k))?.let { return it }
        }
        return null
    }

    private fun parseExpirationElement(el: JsonElement?): Long? {
        if (el == null || el.isJsonNull) return null
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> {
                    val n = el.asLong
                    if (n > 1_000_000_000_000L) n / 1000 else n
                }
                el.isJsonPrimitive && el.asJsonPrimitive.isString ->
                    OssStsCredentials.expirationFromIso8601(el.asString)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class StsEnvelope(
        @SerializedName("data") val data: OssStsCredentials? = null,
    )

    companion object {
        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
