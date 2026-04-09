package cn.shengwang.convoai.quickstart.convoai

import io.agora.convoai.convoaiApi.IConversationalAIAPIEventHandler
import io.agora.convoai.convoaiApi.InterruptEvent
import io.agora.convoai.convoaiApi.MessageError
import io.agora.convoai.convoaiApi.MessageReceipt
import io.agora.convoai.convoaiApi.Metric
import io.agora.convoai.convoaiApi.ModuleError
import io.agora.convoai.convoaiApi.StateChangeEvent
import io.agora.convoai.convoaiApi.Transcript
import io.agora.convoai.convoaiApi.VoiceprintStateChangeEvent

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
