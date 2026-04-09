package ai.nex.interaction.convoai

import ai.nex.interaction.vendor.convoai.InterruptEvent
import ai.nex.interaction.vendor.convoai.MessageError
import ai.nex.interaction.vendor.convoai.MessageReceipt
import ai.nex.interaction.vendor.convoai.Metric
import ai.nex.interaction.vendor.convoai.ModuleError
import ai.nex.interaction.vendor.convoai.StateChangeEvent
import ai.nex.interaction.vendor.convoai.Transcript
import ai.nex.interaction.vendor.convoai.VoiceprintStateChangeEvent

/**
 * 与 [ai.nex.interaction.vendor.convoai.IConversationalAIAPIEventHandler] 一一对应，由 UI/ViewModel 实现业务。
 */
interface ConversationConvoAiEventSink {
    fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent)
    fun onAgentInterrupted(agentUserId: String, event: InterruptEvent)
    fun onAgentMetrics(agentUserId: String, metric: Metric)
    fun onAgentError(agentUserId: String, error: ModuleError)
    fun onMessageError(agentUserId: String, error: MessageError)
    fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt)
    fun onAgentVoiceprintStateChanged(agentUserId: String, event: VoiceprintStateChangeEvent)
    fun onTranscriptUpdated(agentUserId: String, transcript: Transcript)
    fun onDebugLog(log: String)
}
