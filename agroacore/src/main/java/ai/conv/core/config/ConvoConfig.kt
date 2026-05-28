package ai.conv.core.config

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

    const val GEELY_PRIVATE_IP = "47.96.173.253"

    const val USE_PRIVATE_ENV: Boolean = false

    /** 是否为眼镜场景,默认 false,可通过 ConvoManagerConfig 动态修改 */
    @JvmField
    var IS_GLASS_SCENARIO: Boolean = false

    const val LLM_API_KEY: String = "wugjEjLpoM4ygLCcsg0bmwubtUwEN7yn"
    const val LLM_URL: String = "http://${GEELY_PRIVATE_IP}:8080/chat/completions"
    const val LLM_MODEL: String = "qwen-plus"
    const val LLM_VENDOR: String = "custom"
    val LLM_PARRAMS: String = """{"model":"deepseek-chat", "max_token":1024}"""
    val LLM_SYSTEM_MESSAGES: String = """[{"role":"system","content":"You are a helpful assistant."}]"""
    const val LLM_MAX_HISTORY: String = "21"

    const val ASR_LANG: String = "zh-CN"
    const val ASR_VENDOR: String = "fengming"
    val ASR_PARAMS: String = ""

    const val TTS_VENDOR: String = "openai"
    val TTS_PARAMS: String =
        """{"base_url": "http://${GEELY_PRIVATE_IP}:8080/v1", "api_key": "wugjEjLpoM4ygLCcsg0bmwubtUwEN7yn", "model": "gpt-4o-mini-tts", "voice": "coral", "instructions": "", "speed": 1.0}"""
    const val TTS_BYTEDANCE_APP_ID: String = ""
    const val TTS_BYTEDANCE_TOKEN: String = ""
    @JvmField
    var SAL_LAB_PCM_URL_SPEAKER1: String =
        "https://voiceprint-labtest.agoralab.co/lab_qn_m1.pcm"
    @JvmField
    var SAL_LAB_PCM_URL_SPEAKER2 =
        "https://voiceprint-labtest.agoralab.co/lab_qn_f1.pcm"

    const val INTERRUPT_CHECK_URL: String = "http://${GEELY_PRIVATE_IP}:8080/v1/audio/interrupt_check"
    const val PRE_REG_CALLBACK_URL: String = "http://${GEELY_PRIVATE_IP}:8080/v1/voice_print/register_status"

    const val PRIVATE_BVC_URL: String = "wss://convoai-krildorrjz.cn-hangzhou.fcapp.run/vp/v1/bvcanceling"
    //注意Speaker IDs要与rtc_user_id保持一致
    @JvmField
    var SAL_LAB_SPEAKER1_ID = "shengwang_speaker1_wxy"
    @JvmField
    var SAL_LAB_SPEAKER2_ID = "shengwang_speaker2_lzc"

    const val BVC_URL: String = "wss://convoai-krildorrjz.cn-hangzhou.fcapp.run/vp/v1/bvcanceling"
    const val PRIVATE_DOMAIN_LIST = "ap.1405669.agora.local"
    const val PRIVATE_BASE_URL = "http://${GEELY_PRIVATE_IP}:9090/api/conversational-ai-agent/v2/projects"
    const val PUBLIC_BASE_URL = "https://api.agora.io/cn/api/conversational-ai-agent/v2/projects"

    /** 阿里云 OSS 上传凭证；勿提交真实值，本地或 CI 注入 */
    const val STT_UPLOADER_KEY: String = ""
    const val STT_UPLOADER_SECRET: String = ""


}
