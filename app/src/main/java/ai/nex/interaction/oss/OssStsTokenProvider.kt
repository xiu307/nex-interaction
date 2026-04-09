package ai.nex.interaction.oss

fun interface OssStsTokenProvider {
    suspend fun getStsCredentials(): OssStsCredentials?
}
