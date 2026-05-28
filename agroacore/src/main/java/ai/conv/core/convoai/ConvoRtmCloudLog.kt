package ai.conv.core.convoai

import android.util.Log

/**
 * Logcat tag dedicated to **RTM downlink** (云端经 RTM 下行) for quick filtering.
 *
 * Example: `adb logcat -s ConvoRtmCloud`
 *
 * Other ConvoAI debug lines still go through [IConversationalAIAPIEventHandler.onDebugLog]
 * (app 侧当前 tag 为 `conversationalAIAPI`)，本 tag 仅补充一层「只看 RTM 下行」的入口。
 */
object ConvoRtmCloudLog {
    const val TAG = "ConvoRtmCloud"
    
    // Android Logcat 单条日志最大长度约为 4000 字符
    private const val MAX_LOG_LENGTH = 4000

    fun d(message: String) {
        // 如果消息过长，分段打印
        if (message.length > MAX_LOG_LENGTH) {
            var start = 0
            var index = 1
            while (start < message.length) {
                val end = minOf(start + MAX_LOG_LENGTH, message.length)
                val segment = message.substring(start, end)
                Log.d(TAG, "[$index] $segment")
                start = end
                index++
            }
        } else {
            Log.d(TAG, message)
        }
    }
}
