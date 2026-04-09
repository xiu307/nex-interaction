package ai.conv.internal.rtm

import io.agora.rtm.RtmConfig

/**
 * 对话场景 RTM 客户端配置（AppId + 字符串 userId），与 [RtmConfig.Builder] 默认用法一致。
 */
fun createConversationRtmConfig(appId: String, userId: String): RtmConfig =
    RtmConfig.Builder(appId, userId).build()
