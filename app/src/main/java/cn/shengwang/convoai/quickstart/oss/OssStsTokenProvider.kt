package cn.shengwang.convoai.quickstart.oss

fun interface OssStsTokenProvider {
    suspend fun getStsCredentials(): OssStsCredentials?
}
