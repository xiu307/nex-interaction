package cn.shengwang.convoai.quickstart.oss

import cn.shengwang.convoai.quickstart.KeyCenter

/** 与宿主工程一致：仅当 [KeyCenter.OSS_STS_TOKEN_URL] 非空时才应发起 STS/OSS 上传。 */
object OssStsRuntime {
    fun hasStsEndpoint(): Boolean = KeyCenter.OSS_STS_TOKEN_URL.isNotBlank()
}
