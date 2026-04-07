package cn.shengwang.convoai.quickstart.oss

import android.util.Log
import com.alibaba.sdk.android.oss.ClientException
import com.alibaba.sdk.android.oss.common.auth.OSSFederationCredentialProvider
import com.alibaba.sdk.android.oss.common.auth.OSSFederationToken
import kotlinx.coroutines.runBlocking

/**
 * 使用 HTTP STS，并让 OSS SDK 按过期时间自动刷新（见 [OSSFederationCredentialProvider.getValidFederationToken]）。
 *
 * 若仍用 [com.alibaba.sdk.android.oss.common.auth.OSSStsTokenCredentialProvider] 且不带真实 expiration，
 * SDK 会认为凭证永不过期，STS 在云端过期后上传会失败（时间类报错或与 RequestTimeTooSkewed 并存）。
 */
class OssHttpStsFederationCredentialProvider(
    private val httpProvider: OssHttpStsTokenProvider = OssHttpStsTokenProvider(),
) : OSSFederationCredentialProvider() {

    override fun getFederationToken(): OSSFederationToken {
        val creds = try {
            runBlocking { httpProvider.getStsCredentials() }
        } catch (e: Exception) {
            Log.e(TAG, "STS fetch failed: ${e.message}")
            throw ClientException("STS fetch failed: ${e.message}", e)
        } ?: throw ClientException("STS credentials null; check OSS_STS_TOKEN_URL and HTTP response")

        val expSec = creds.expirationEpochSeconds
            ?: (System.currentTimeMillis() / 1000 + DEFAULT_FALLBACK_VALIDITY_SEC)
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "STS ok, expEpochSec=$expSec (fromResponse=${creds.expirationEpochSeconds != null})")
        }
        return OSSFederationToken(
            creds.accessKeyId,
            creds.accessKeySecret,
            creds.securityToken,
            expSec,
        )
    }

    companion object {
        private const val TAG = "OssStsFederation"
        /** 响应未带 expiration 时：假定约 1 小时内有效，便于 SDK 在过期前 ~5 分钟重拉 STS */
        private const val DEFAULT_FALLBACK_VALIDITY_SEC = 3500L
    }
}
