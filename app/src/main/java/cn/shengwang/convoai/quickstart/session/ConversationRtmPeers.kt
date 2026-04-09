package cn.shengwang.convoai.quickstart.session

/**
 * 对话场景 RTM 对端与相关日志标签（与 Face 上行 / SpeakerBind 一致）。
 */
object ConversationRtmPeers {
    /** 对端 RTM 用户 ID（与 [cn.shengwang.convoai.quickstart.biometric.FaceRtmStreamPublisher] 默认一致）。 */
    const val GEELY_RTM_SERVER_USER_ID = "geely_rtm_server"

    /** `adb logcat -s SPEAKER_BIND` */
    const val LOG_TAG_SPEAKER_BIND = "SPEAKER_BIND"
}
