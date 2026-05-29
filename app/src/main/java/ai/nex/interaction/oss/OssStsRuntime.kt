package ai.nex.interaction.oss

import ai.nex.interaction.BuildConfig

/** 与宿主工程一致：仅当 [BuildConfig.OSS_STS_TOKEN_URL] 非空时才应发起 STS/OSS 上传。 */
object OssStsRuntime {
    fun hasStsEndpoint(): Boolean = BuildConfig.OSS_STS_TOKEN_URL.isNotBlank()
}
