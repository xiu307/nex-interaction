package ai.nex.interaction.convoai

import ai.conv.internal.convoai.InterruptEvent
import ai.conv.internal.convoai.MessageError
import ai.conv.internal.convoai.MessageReceipt
import ai.conv.internal.convoai.Metric
import ai.conv.internal.convoai.ModuleError
import ai.conv.internal.convoai.StateChangeEvent
import ai.conv.internal.convoai.Transcript
import ai.conv.internal.convoai.VoiceprintStateChangeEvent

/**
 * 与 [ai.conv.internal.convoai.IConversationalAIAPIEventHandler] 一一对应，由 UI/ViewModel 实现业务。
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
