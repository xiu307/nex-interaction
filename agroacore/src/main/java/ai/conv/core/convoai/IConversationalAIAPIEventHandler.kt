package ai.conv.core.convoai
/**
 * Conversational AI API event handler interface.
 *
 * Implement this interface to receive AI conversation events such as state changes, transcript, errors, and metrics.
 * All callbacks are invoked on the main thread for UI updates.
 *
 * @note Some callbacks (such as onTranscriptUpdated) may be triggered at high frequency for reliability. If your business requires deduplication, please handle it at the business layer.
 */
interface IConversationalAIAPIEventHandler {
    /**
     * Called when the agent state changes (silent, listening, thinking, speaking).
     * @param agentUserId Agent user ID
     * @param event State change event
     */
    fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent)

    /**
     * Called when an interrupt event occurs.
     * @param agentUserId Agent user ID
     * @param event Interrupt event
     */
    fun onAgentInterrupted(agentUserId: String, event: InterruptEvent)

    /**
     * Called when performance metrics are available.
     * @param agentUserId Agent user ID
     * @param metric Performance metrics
     */
    fun onAgentMetrics(agentUserId: String, metric: Metric)

    /**
     * Called when an AI error occurs.
     * @param agentUserId Agent user ID
     * @param error AI error
     */
    fun onAgentError(agentUserId: String, error: ModuleError)

    /**
     *  Called when message error occurs
     *  This method is called when message processing encounters errors,
     *  For example, when the chat message is failed to send, the error message will be returned.
     *  @param agentUserId Agent user ID
     *  @param error Message error containing type, message
     */
    fun onMessageError(agentUserId: String, error: MessageError)

    /**
     * Called when message receipt is updated
     * @param agentUserId Agent User ID
     * @param receipt message receipt info
     */
    fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt)

    /**
     * Called when message receipt is updated
     * @param agentUserId Agent User ID
     * @param event voice print event
     */
    fun onAgentVoiceprintStateChanged(agentUserId: String, event: VoiceprintStateChangeEvent)

    /**
     * Called when Transcript content is updated.
     * @param agentUserId Agent user ID
     * @param transcript Transcript data
     * @note This callback may be triggered at high frequency. If you need to deduplicate, please handle it at the business layer.
     */
    fun onTranscriptUpdated(agentUserId: String, transcript: Transcript)

    /**
     * Called for internal debug logs.
     * @param log Debug log message
     */
    fun onDebugLog(log: String)

    /**
     * Called when receiving Geely RTM message
     * @param message Geely RTM message map
     */
    fun onGeelyRtmMessage(message: Map<String, Any>) {
        // Default implementation - override if needed
    }
}