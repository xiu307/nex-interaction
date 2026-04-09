package ai.nex.interaction.convoai

import ai.nex.interaction.vendor.convoai.IConversationalAIAPIEventHandler
import ai.nex.interaction.vendor.convoai.InterruptEvent
import ai.nex.interaction.vendor.convoai.MessageError
import ai.nex.interaction.vendor.convoai.MessageReceipt
import ai.nex.interaction.vendor.convoai.Metric
import ai.nex.interaction.vendor.convoai.ModuleError
import ai.nex.interaction.vendor.convoai.StateChangeEvent
import ai.nex.interaction.vendor.convoai.Transcript
import ai.nex.interaction.vendor.convoai.VoiceprintStateChangeEvent

/**
 * 将 [IConversationalAIAPIEventHandler] 回调转发到 [sink]，便于 ViewModel 只实现 [ConversationConvoAiEventSink]。
 */
class ConversationConvoAiApiEventBridge(
    private val sink: ConversationConvoAiEventSink,
) : IConversationalAIAPIEventHandler {

    override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
        sink.onAgentStateChanged(agentUserId, event)
    }

    override fun onAgentInterrupted(agentUserId: String, event: InterruptEvent) {
        sink.onAgentInterrupted(agentUserId, event)
    }

    override fun onAgentMetrics(agentUserId: String, metric: Metric) {
        sink.onAgentMetrics(agentUserId, metric)
    }

    override fun onAgentError(agentUserId: String, error: ModuleError) {
        sink.onAgentError(agentUserId, error)
    }

    override fun onMessageError(agentUserId: String, error: MessageError) {
        sink.onMessageError(agentUserId, error)
    }

    override fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt) {
        sink.onMessageReceiptUpdated(agentUserId, receipt)
    }

    override fun onAgentVoiceprintStateChanged(agentUserId: String, event: VoiceprintStateChangeEvent) {
        sink.onAgentVoiceprintStateChanged(agentUserId, event)
    }

    override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
        sink.onTranscriptUpdated(agentUserId, transcript)
    }

    override fun onDebugLog(log: String) {
        sink.onDebugLog(log)
    }
}
