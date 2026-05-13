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

    fun d(message: String) {
        Log.d(TAG, message)
    }
}
