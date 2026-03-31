package cn.shengwang.convoai.quickstart

import cn.shengwang.convoai.quickstart.BuildConfig

object KeyCenter {
    // Shengwang App Credentials
    val APP_ID: String = BuildConfig.APP_ID
    val APP_CERTIFICATE: String = BuildConfig.APP_CERTIFICATE

    // LLM configuration
    val LLM_API_KEY: String = BuildConfig.LLM_API_KEY
    val LLM_URL: String = BuildConfig.LLM_URL
    val LLM_MODEL: String = BuildConfig.LLM_MODEL

    // TTS configuration from env
    val TTS_BYTEDANCE_APP_ID: String = BuildConfig.TTS_BYTEDANCE_APP_ID
    val TTS_BYTEDANCE_TOKEN: String = BuildConfig.TTS_BYTEDANCE_TOKEN
}
