package cn.shengwang.convoai.quickstart.convoai

import android.util.Log
import io.agora.convoai.convoaiApi.InterruptEvent
import io.agora.convoai.convoaiApi.MessageError
import io.agora.convoai.convoaiApi.MessageReceipt
import io.agora.convoai.convoaiApi.Metric
import io.agora.convoai.convoaiApi.ModuleError
import io.agora.convoai.convoaiApi.StateChangeEvent
import io.agora.convoai.convoaiApi.Transcript
import io.agora.convoai.convoaiApi.VoiceprintStateChangeEvent

private const val DEBUG_LOG_TAG = "conversationalAIAPI"

/**
 * [ConversationConvoAiEventSink] 的默认可覆盖实现：未用到的回调为空实现，[onDebugLog] 默认输出到 logcat。
 */
open class DefaultConversationConvoAiEventSink : ConversationConvoAiEventSink {

    override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {}

    override fun onAgentInterrupted(agentUserId: String, event: InterruptEvent) {}

    override fun onAgentMetrics(agentUserId: String, metric: Metric) {}

    override fun onAgentError(agentUserId: String, error: ModuleError) {}

    override fun onMessageError(agentUserId: String, error: MessageError) {}

    override fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt) {}

    override fun onAgentVoiceprintStateChanged(agentUserId: String, event: VoiceprintStateChangeEvent) {}

    override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {}

    override fun onDebugLog(log: String) {
        Log.d(DEBUG_LOG_TAG, log)
    }
}
