package ai.nex.interaction

import ai.conv.internal.config.ConvoConfig

object KeyCenter {
    // Shengwang App Credentials
    val APP_ID: String = ConvoConfig.APP_ID
    val APP_CERTIFICATE: String = ConvoConfig.APP_CERTIFICATE

    // LLM configuration
    val LLM_API_KEY: String = ConvoConfig.LLM_API_KEY
    val LLM_URL: String = ConvoConfig.LLM_URL
    val LLM_MODEL: String = ConvoConfig.LLM_MODEL
    val LLM_VENDOR: String = ConvoConfig.LLM_VENDOR
    val LLM_PARRAMS: String = ConvoConfig.LLM_PARRAMS
    val LLM_SYSTEM_MESSAGES: String = ConvoConfig.LLM_SYSTEM_MESSAGES
    val LLM_MAX_HISTORY: String = ConvoConfig.LLM_MAX_HISTORY

    // ASR (env → BuildConfig)
    val ASR_LANG: String = ConvoConfig.ASR_LANG
    val ASR_VENDOR: String = ConvoConfig.ASR_VENDOR
    val ASR_PARAMS: String = ConvoConfig.ASR_PARAMS

    // TTS configuration from env
    val TTS_VENDOR: String = ConvoConfig.TTS_VENDOR
    val TTS_PARAMS: String = ConvoConfig.TTS_PARAMS
    val TTS_BYTEDANCE_APP_ID: String = ConvoConfig.TTS_BYTEDANCE_APP_ID
    val TTS_BYTEDANCE_TOKEN: String = ConvoConfig.TTS_BYTEDANCE_TOKEN

    /** SAL：与场景工程 buildSalSampleUrls 一致，由 env 配置个性化声纹与预注册 faceId→PCM */
    val SAL_ENABLE_PERSONALIZED: Boolean = ConvoConfig.SAL_ENABLE_PERSONALIZED
    val SAL_PERSONALIZED_PCM_URL: String = ConvoConfig.SAL_PERSONALIZED_PCM_URL
    val SAL_BIOMETRIC_SAMPLE_URLS: String = ConvoConfig.SAL_BIOMETRIC_SAMPLE_URLS

    /** 运行时 STS 地址继续由 app 自身 BuildConfig 持有，不放进 SDK。 */
    val OSS_STS_TOKEN_URL: String = BuildConfig.OSS_STS_TOKEN_URL
}
