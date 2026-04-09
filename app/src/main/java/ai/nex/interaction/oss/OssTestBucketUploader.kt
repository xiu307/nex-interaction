package ai.nex.interaction.oss

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.alibaba.sdk.android.oss.ClientException
import com.alibaba.sdk.android.oss.OSSClient
import com.alibaba.sdk.android.oss.ServiceException
import com.alibaba.sdk.android.oss.common.auth.OSSFederationCredentialProvider
import com.alibaba.sdk.android.oss.model.PutObjectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar

/**
 * 测试 OSS bucket（与 Android `OssTestBucketUploader` 一致）。
 */
class OssTestBucketUploader(
    private val context: Context,
    /** 使用 [OssHttpStsFederationCredentialProvider] 可自动按 STS 过期时间刷新；仅注入 [OssHttpStsTokenProvider] 时请在外部包一层 Federation。 */
    private val credentialProvider: OSSFederationCredentialProvider,
) {

    private var ossClient: OSSClient? = null
    private var tryResetClientOnce = false

    suspend fun uploadSpeakerIdPcm(
        pcmBytes: ByteArray,
        deviceId: String,
        faceId: String? = null,
    ): OssUploadResult {
        val key = buildSpeakerIdObjectKey(deviceId, "pcm", faceId)
        return putObjectBytes(pcmBytes, key)
    }

    suspend fun uploadSpeakerIdBytes(
        bytes: ByteArray,
        deviceId: String,
        fileExtension: String,
        faceId: String? = null,
    ): OssUploadResult {
        val key = buildSpeakerIdObjectKey(deviceId, fileExtension, faceId)
        return putObjectBytes(bytes, key)
    }

    suspend fun uploadBitmap(
        bitmap: Bitmap,
        compress: Int = 100,
        uploadType: Int = TYPE_DEFAULT,
    ): OssUploadResult {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, compress, stream)
        val bytes = stream.toByteArray()
        val fileNameOnly = "${System.currentTimeMillis()}.jpg"
        val objectKey = buildObjectKey(uploadType, fileNameOnly)
        return putObjectBytes(bytes, objectKey)
    }

    private suspend fun putObjectBytes(bytes: ByteArray, objectKey: String): OssUploadResult =
        withContext(Dispatchers.IO) {
            val client = initOssClient()
                ?: return@withContext OssUploadResult(
                    ok = false,
                    errorCode = OssUploadResult.ERROR_OSS_INIT_ERR,
                )
            val put = PutObjectRequest(BUCKET_NAME, objectKey, bytes)
            try {
                val putResult = client.putObject(put)
                Log.d(TAG, "OSS putObject status=${putResult.statusCode} key=$objectKey")
                if (putResult.statusCode == HTTP_OK) {
                    tryResetClientOnce = false
                    val url = "$PUBLIC_BASE_URL$objectKey"
                    OssUploadResult(ok = true, url = url)
                } else {
                    OssUploadResult(ok = false, errorCode = OssUploadResult.ERROR_OSS_SERVICE_ERR)
                }
            } catch (e: ClientException) {
                Log.e(TAG, "OSS ClientException: $e")
                OssUploadResult(ok = false, errorCode = OssUploadResult.ERROR_OSS_NETWORK_ERR)
            } catch (e: ServiceException) {
                Log.e(TAG, "OSS ServiceException: ${e.errorCode} ${e.rawMessage}")
                if (!tryResetClientOnce) {
                    tryResetClientOnce = true
                    ossClient = null
                    return@withContext putObjectBytes(bytes, objectKey)
                }
                OssUploadResult(ok = false, errorCode = OssUploadResult.ERROR_OSS_SERVICE_ERR)
            }
        }

    private suspend fun initOssClient(): OSSClient? {
        if (ossClient != null) return ossClient
        ossClient = OSSClient(context.applicationContext, ENDPOINT, credentialProvider)
        Log.d(TAG, "OSS client initialized (test bucket only)")
        return ossClient
    }

    fun resetClient() {
        ossClient = null
        tryResetClientOnce = false
    }

    private fun buildObjectKey(uploadType: Int, fileNameOnly: String): String {
        if (uploadType == TYPE_MULTI_MODE) {
            val cal = Calendar.getInstance()
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return "${PREFIX_MULTI_MODE}$y$m$d/$fileNameOnly"
        }
        return PREFIX_DEFAULT + fileNameOnly
    }

    companion object {
        private const val TAG = "OssTestBucket"

        const val BUCKET_NAME = "ai-sprite-oss-test"

        private const val ENDPOINT = "https://oss-cn-hangzhou.aliyuncs.com/"
        private const val PREFIX_DEFAULT = "ai-agent/poc/images/"
        private const val PREFIX_MULTI_MODE = "admin/"
        private const val HTTP_OK = 200

        const val SPEAKER_ID_OBJECT_PREFIX = "tmp/ai-speech-robot/agora/speaker_id/"

        val PUBLIC_BASE_URL: String
            get() = "https://$BUCKET_NAME.oss-cn-hangzhou.aliyuncs.com/"

        const val TYPE_DEFAULT = 0
        const val TYPE_MULTI_MODE = 1

        @JvmStatic
        @JvmOverloads
        fun buildSpeakerIdObjectKey(
            deviceId: String,
            fileExtension: String,
            faceId: String? = null,
            uploadMillis: Long = System.currentTimeMillis(),
        ): String {
            val safeId = sanitizeDeviceIdForPath(deviceId)
            val ext = normalizeFileExtension(fileExtension)
            val safeFace = faceId?.trim()?.takeIf { it.isNotEmpty() }?.let { sanitizeFaceIdForFilename(it) }
            val fileName = if (safeFace != null) {
                "speaker_id_${safeFace}_$uploadMillis.$ext"
            } else {
                "speaker_id_$uploadMillis.$ext"
            }
            return "${SPEAKER_ID_OBJECT_PREFIX}$safeId/$fileName"
        }

        private fun sanitizeFaceIdForFilename(faceId: String): String {
            return faceId.trim()
                .replace("/", "_")
                .replace("\\", "_")
                .replace(Regex("\\s+"), "_")
                .ifEmpty { "unknown" }
        }

        private fun sanitizeDeviceIdForPath(deviceId: String): String {
            return deviceId.trim()
                .replace("/", "_")
                .replace("\\", "_")
                .ifEmpty { "unknown" }
        }

        private fun normalizeFileExtension(fileExtension: String): String {
            var e = fileExtension.trim().lowercase()
            if (e.startsWith(".")) {
                e = e.substring(1)
            }
            return e.ifEmpty { "pcm" }
        }

        @JvmStatic
        fun createWithHttpSts(context: Context): OssTestBucketUploader {
            return OssTestBucketUploader(
                context.applicationContext,
                OssHttpStsFederationCredentialProvider(OssHttpStsTokenProvider()),
            )
        }
    }
}
