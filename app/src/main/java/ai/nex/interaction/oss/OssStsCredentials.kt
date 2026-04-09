package ai.nex.interaction.oss

import java.time.Instant
import java.time.format.DateTimeParseException

/** 阿里云 OSS STS 临时凭证（与 Android common 一致）。 */
data class OssStsCredentials(
    val accessKeyId: String,
    val accessKeySecret: String,
    val securityToken: String,
    /**
     * STS 过期时间（Unix 秒）。若 HTTP 响应未带 expiration，则为 null；
     * [OssHttpStsFederationCredentialProvider] 会用保守默认时长并提前刷新。
     */
    val expirationEpochSeconds: Long? = null,
) {
    companion object {
        fun expirationFromIso8601(value: String?): Long? {
            val s = value?.trim().orEmpty()
            if (s.isEmpty()) return null
            return try {
                Instant.parse(s).epochSecond
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
