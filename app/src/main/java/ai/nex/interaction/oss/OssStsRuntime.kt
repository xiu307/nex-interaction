package ai.nex.interaction.oss

import ai.nex.interaction.KeyCenter

/** 与宿主工程一致：仅当 [KeyCenter.OSS_STS_TOKEN_URL] 非空时才应发起 STS/OSS 上传。 */
object OssStsRuntime {
    fun hasStsEndpoint(): Boolean = KeyCenter.OSS_STS_TOKEN_URL.isNotBlank()
}
