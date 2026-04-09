package ai.nex.interaction.convoai

import android.util.Log
import ai.conv.internal.convoai.InterruptEvent
import ai.conv.internal.convoai.MessageError
import ai.conv.internal.convoai.MessageReceipt
import ai.conv.internal.convoai.Metric
import ai.conv.internal.convoai.ModuleError
import ai.conv.internal.convoai.StateChangeEvent
import ai.conv.internal.convoai.Transcript
import ai.conv.internal.convoai.VoiceprintStateChangeEvent

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
