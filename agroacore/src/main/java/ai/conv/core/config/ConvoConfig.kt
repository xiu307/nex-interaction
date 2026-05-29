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
    /** 声网 AppId（与旧环境相同；换后台仅改 join 网关 URL，不换 AppId） */
    const val APP_ID: String = "e9e7cafd870849b292c731d4bab44306"
    const val APP_CERTIFICATE: String = "58bccff9667c4d6f863b938a30c95d40"

    /** 吉利业务服务（LLM / TTS / 拒识 / 注册回调），与 join 网关域名不同 */
    const val GEELY_PRIVATE_IP = "47.96.173.253"

    /** 吉利业务 HTTP 端口（端口修改.md：8080 → 8081） */
    const val GEELY_SERVICE_PORT = 8081

    /** 旧内网 Agent 网关（:9090）；新多人方案请保持 false，走 [GEELY_MULTI_BASE_URL] */
    const val USE_PRIVATE_ENV: Boolean = false

    /**
     * 吉利多人对话 REST（PDF 20260528）：api-test + [SERVICE_NAMESPACE]。
     * join/leave/add_sal_speakers 走此网关；勿与 [PRIVATE_BASE_URL]（旧 :9090）混用。
     */
    const val USE_GEELY_MULTI_API: Boolean = true

    const val GEELY_MULTI_BASE_URL: String =
        "https://api-test.agora.io/hzacsdev01t-ctel/api/conversational-ai-agent/v2/projects"

    /** join / leave / add_sal_speakers 等 REST 必填 Header（PDF: jili-test） */
    const val SERVICE_NAMESPACE: String = "jili-test"

    const val INTERRUPT_CHECK_TIMEOUT_MS: Int = 3500

    const val REGISTER_GATE_TIMEOUT_SECONDS: Double = 30.0

    const val SAL_MAX_SESSION_COUNT: Int = 20

    /** 多人场景：LLM 不带上下文，由吉利侧维护 */
    const val LLM_MAX_HISTORY_GEELY_MULTI: String = "1"

    /** 是否为眼镜场景,默认 false非眼镜业务场景,可通过 ConvoManagerConfig 动态修改 */
    @JvmField
    var IS_GLASS_SCENARIO: Boolean = false

    const val LLM_API_KEY: String = "wugjEjLpoM4ygLCcsg0bmwubtUwEN7yn"
    const val LLM_URL: String = "http://${GEELY_PRIVATE_IP}:${GEELY_SERVICE_PORT}/chat/completions"
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
        """{"base_url": "http://${GEELY_PRIVATE_IP}:${GEELY_SERVICE_PORT}/v1", "api_key": "wugjEjLpoM4ygLCcsg0bmwubtUwEN7yn", "model": "gpt-4o-mini-tts", "voice": "coral", "instructions": "", "speed": 1.0}"""
    const val TTS_BYTEDANCE_APP_ID: String = ""
    const val TTS_BYTEDANCE_TOKEN: String = ""
    @JvmField
    var SAL_LAB_PCM_URL_SPEAKER1: String =
        "https://voiceprint-labtest.agoralab.co/lab_qn_m1.pcm"
    @JvmField
    var SAL_LAB_PCM_URL_SPEAKER2 =
        "https://voiceprint-labtest.agoralab.co/lab_qn_f1.pcm"

    const val INTERRUPT_CHECK_URL: String =
        "http://${GEELY_PRIVATE_IP}:${GEELY_SERVICE_PORT}/v1/audio/interrupt_check"
    const val PRE_REG_CALLBACK_URL: String =
        "http://${GEELY_PRIVATE_IP}:${GEELY_SERVICE_PORT}/v1/voice_print/register_status"

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

    /** Agent REST（join / leave / sal 管理）根路径，不含 trailing slash。 */
    fun agentRestBaseUrl(): String = when {
        USE_PRIVATE_ENV -> PRIVATE_BASE_URL
        USE_GEELY_MULTI_API -> GEELY_MULTI_BASE_URL
        else -> PUBLIC_BASE_URL
    }

    fun effectiveLlmMaxHistory(): String =
        if (USE_GEELY_MULTI_API) LLM_MAX_HISTORY_GEELY_MULTI else LLM_MAX_HISTORY

    /** 阿里云 OSS 上传凭证；勿提交真实值，本地或 CI 注入 */
    const val STT_UPLOADER_KEY: String = ""
    const val STT_UPLOADER_SECRET: String = ""


}
