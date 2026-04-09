package ai.nex.interaction

import ai.nex.interaction.BuildConfig

object KeyCenter {
    // Shengwang App Credentials
    val APP_ID: String = BuildConfig.APP_ID
    val APP_CERTIFICATE: String = BuildConfig.APP_CERTIFICATE

    // LLM configuration
    val LLM_API_KEY: String = BuildConfig.LLM_API_KEY
    val LLM_URL: String = BuildConfig.LLM_URL
    val LLM_MODEL: String = BuildConfig.LLM_MODEL
    val LLM_VENDOR: String = BuildConfig.LLM_VENDOR
    val LLM_PARRAMS: String = BuildConfig.LLM_PARRAMS
    val LLM_SYSTEM_MESSAGES: String = BuildConfig.LLM_SYSTEM_MESSAGES
    val LLM_MAX_HISTORY: String = BuildConfig.LLM_MAX_HISTORY

    // ASR (env → BuildConfig)
    val ASR_LANG: String = BuildConfig.ASR_LANG
    val ASR_VENDOR: String = BuildConfig.ASR_VENDOR
    val ASR_PARAMS: String = BuildConfig.ASR_PARAMS

    // TTS configuration from env
    val TTS_VENDOR: String = BuildConfig.TTS_VENDOR
    val TTS_PARAMS: String = BuildConfig.TTS_PARAMS
    val TTS_BYTEDANCE_APP_ID: String = BuildConfig.TTS_BYTEDANCE_APP_ID
    val TTS_BYTEDANCE_TOKEN: String = BuildConfig.TTS_BYTEDANCE_TOKEN

    /** SAL：与场景工程 buildSalSampleUrls 一致，由 env 配置个性化声纹与预注册 faceId→PCM */
    val SAL_ENABLE_PERSONALIZED: Boolean = BuildConfig.SAL_ENABLE_PERSONALIZED
    val SAL_PERSONALIZED_PCM_URL: String = BuildConfig.SAL_PERSONALIZED_PCM_URL
    val SAL_BIOMETRIC_SAMPLE_URLS: String = BuildConfig.SAL_BIOMETRIC_SAMPLE_URLS

    /** 运行时 STS 地址（由 Gradle 注入，解析顺序与 Android common 一致，见 app/build.gradle.kts） */
    val OSS_STS_TOKEN_URL: String = BuildConfig.OSS_STS_TOKEN_URL
}
