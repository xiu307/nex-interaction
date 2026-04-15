package ai.nex.interaction

import ai.conv.internal.config.ConvoConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object TokenGenerator {
    private const val JSON_MEDIA_TYPE = "application/json"
    private const val TOKEN_SERVICE_URL = "https://service.apprtc.cn/toolbox/v2/token/generate"

    private val httpClient = OkHttpClient()

    suspend fun generateTokensAsync(channelName: String, uid: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val requestBody = JSONObject().apply {
                    put("appId", ConvoConfig.APP_ID)
                    put("appCertificate", ConvoConfig.APP_CERTIFICATE)
                    put("channelName", channelName)
                    put("uid", uid)
                    put("types", JSONArray().put(1).put(2))
                    put("expire", 60 * 60 * 24)
                    put("src", "Android")
                    put("ts", System.currentTimeMillis().toString())
                }

                val request = Request.Builder()
                    .url(TOKEN_SERVICE_URL)
                    .addHeader("Content-Type", JSON_MEDIA_TYPE)
                    .post(requestBody.toString().toRequestBody())
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Generate token failed http=${response.code} body=${response.body?.string().orEmpty()}")
                    }
                    val responseJson = JSONObject(response.body?.string().orEmpty())
                    if (responseJson.optInt("code", -1) != 0) {
                        error("Generate token failed response=$responseJson")
                    }
                    responseJson.getJSONObject("data").getString("token")
                }
            }
        }
}
