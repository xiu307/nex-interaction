package ai.nex.interaction

import ai.conv.core.config.AgroaConfig

object KeyCenter {
    // Shengwang App Credentials
    val APP_ID: String = AgroaConfig.APP_ID
    val APP_CERTIFICATE: String = AgroaConfig.APP_CERTIFICATE

    // LLM configuration
    val LLM_API_KEY: String = AgroaConfig.LLM_API_KEY
    val LLM_URL: String = AgroaConfig.LLM_URL
    val LLM_MODEL: String = AgroaConfig.LLM_MODEL
    val LLM_VENDOR: String = AgroaConfig.LLM_VENDOR
    val LLM_PARRAMS: String = AgroaConfig.LLM_PARRAMS
    val LLM_SYSTEM_MESSAGES: String = AgroaConfig.LLM_SYSTEM_MESSAGES
    val LLM_MAX_HISTORY: String = AgroaConfig.LLM_MAX_HISTORY

    // ASR (env → BuildConfig)
    val ASR_LANG: String = AgroaConfig.ASR_LANG
    val ASR_VENDOR: String = AgroaConfig.ASR_VENDOR
    val ASR_PARAMS: String = AgroaConfig.ASR_PARAMS

    // TTS configuration from env
    val TTS_VENDOR: String = AgroaConfig.TTS_VENDOR
    val TTS_PARAMS: String = AgroaConfig.TTS_PARAMS
    val TTS_BYTEDANCE_APP_ID: String = AgroaConfig.TTS_BYTEDANCE_APP_ID
    val TTS_BYTEDANCE_TOKEN: String = AgroaConfig.TTS_BYTEDANCE_TOKEN

    /** SAL：与场景工程 buildSalSampleUrls 一致，由 env 配置个性化声纹与预注册 faceId→PCM */
    val SAL_ENABLE_PERSONALIZED: Boolean = AgroaConfig.SAL_ENABLE_PERSONALIZED
    val SAL_PERSONALIZED_PCM_URL: String = AgroaConfig.SAL_PERSONALIZED_PCM_URL
    val SAL_BIOMETRIC_SAMPLE_URLS: String = AgroaConfig.SAL_BIOMETRIC_SAMPLE_URLS

    /** 运行时 STS 地址继续由 app 自身 BuildConfig 持有，不放进 SDK。 */
    val OSS_STS_TOKEN_URL: String = BuildConfig.OSS_STS_TOKEN_URL
}
