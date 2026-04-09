package ai.nex.interaction.convoai

import android.util.Log
import ai.nex.interaction.vendor.convoai.InterruptEvent
import ai.nex.interaction.vendor.convoai.MessageError
import ai.nex.interaction.vendor.convoai.MessageReceipt
import ai.nex.interaction.vendor.convoai.Metric
import ai.nex.interaction.vendor.convoai.ModuleError
import ai.nex.interaction.vendor.convoai.StateChangeEvent
import ai.nex.interaction.vendor.convoai.Transcript
import ai.nex.interaction.vendor.convoai.VoiceprintStateChangeEvent

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
