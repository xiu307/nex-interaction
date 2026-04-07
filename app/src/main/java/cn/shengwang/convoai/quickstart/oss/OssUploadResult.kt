package cn.shengwang.convoai.quickstart.oss

data class OssUploadResult(
    val ok: Boolean,
    val url: String? = null,
    val errorCode: Int? = null,
) {
    companion object {
        const val ERROR_OSS_INIT_ERR = 8
        const val ERROR_OSS_NETWORK_ERR = 9
        const val ERROR_OSS_SERVICE_ERR = 10
    }
}
