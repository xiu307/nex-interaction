package ai.conv.internal.config

/**
 * 对话 SDK 内置配置源。
 *
 * 当前演示工程将配置直接内嵌在 SDK 源码中，业务侧应统一通过本对象读取，
 * 不再依赖 `app` 模块的 `BuildConfig` 字段或外部 `env.properties`。
 *
 * 生产环境不要把真实凭证硬编码在客户端，请改为由后端签发 Token 和下发
 * 必要配置。
 */
object ConvoConfig {
    const val APP_ID: String = "e9e7cafd870849b292c731d4bab44306"
    const val APP_CERTIFICATE: String = "58bccff9667c4d6f863b938a30c95d40"

    const val LLM_API_KEY: String = "wugjEjLpoM4ygLCcsg0bmwubtUwEN7yn"
    const val LLM_URL: String = "http://42.121.218.208:8080/chat/completions"
    const val LLM_MODEL: String = "qwen-plus"
    const val LLM_VENDOR: String = "custom"
    val LLM_PARRAMS: String = """{"model":"deepseek-chat", "max_token":1024}"""
    val LLM_SYSTEM_MESSAGES: String = """[{"role":"system","content":"You are a helpful assistant."}]"""
    const val LLM_MAX_HISTORY: String = "21"

    const val ASR_LANG: String = "zh-CN"
    const val ASR_VENDOR: String = "openai"
    val ASR_PARAMS: String =
        """{"base_url": "ws://42.121.218.208:8080/realtime?intent=transcription", "api_key": "wugjEjLpoM4ygLCcsg0bmwubtUwEN7yn", "access_key": "", "app_key": "", "resource_id": ""}"""

    const val TTS_VENDOR: String = "openai"
    val TTS_PARAMS: String =
        """{"base_url": "http://42.121.218.208:8080/v1", "api_key": "wugjEjLpoM4ygLCcsg0bmwubtUwEN7yn", "model": "gpt-4o-mini-tts", "voice": "coral", "instructions": "", "speed": 1.0}"""
    const val TTS_BYTEDANCE_APP_ID: String = ""
    const val TTS_BYTEDANCE_TOKEN: String = ""

    const val SAL_ENABLE_PERSONALIZED: Boolean = false
    const val SAL_PERSONALIZED_PCM_URL: String = ""
    const val SAL_BIOMETRIC_SAMPLE_URLS: String = ""
}
