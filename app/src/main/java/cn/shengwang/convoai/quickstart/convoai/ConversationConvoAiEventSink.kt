package cn.shengwang.convoai.quickstart.convoai

import io.agora.convoai.convoaiApi.InterruptEvent
import io.agora.convoai.convoaiApi.MessageError
import io.agora.convoai.convoaiApi.MessageReceipt
import io.agora.convoai.convoaiApi.Metric
import io.agora.convoai.convoaiApi.ModuleError
import io.agora.convoai.convoaiApi.StateChangeEvent
import io.agora.convoai.convoaiApi.Transcript
import io.agora.convoai.convoaiApi.VoiceprintStateChangeEvent

/**
 * 与 [io.agora.convoai.convoaiApi.IConversationalAIAPIEventHandler] 一一对应，由 UI/ViewModel 实现业务。
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
