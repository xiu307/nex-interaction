package ai.nex.interaction

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.conv.ConvoManager
import ai.conv.ConvoManagerConfig
import ai.conv.internal.convoai.AgentState
import ai.conv.internal.convoai.IConversationalAIAPIEventHandler
import ai.conv.internal.convoai.InterruptEvent
import ai.conv.internal.convoai.MessageError
import ai.conv.internal.convoai.MessageReceipt
import ai.conv.internal.convoai.Metric
import ai.conv.internal.convoai.ModuleError
import ai.conv.internal.convoai.StateChangeEvent
import ai.conv.internal.convoai.Transcript
import ai.conv.internal.convoai.TranscriptStatus
import ai.conv.internal.convoai.TranscriptType
import ai.conv.internal.convoai.VoiceprintStateChangeEvent
import ai.conv.internal.config.ConvoConfig
import ai.conv.internal.rtc.ConversationRtcEventSink
import ai.conv.internal.rtc.joinConversationChannelWithOptions
import ai.conv.internal.rtm.ConversationRtmEventSink
import ai.conv.internal.rtm.ConversationRtmLogin
import io.agora.rtc2.Constants.ERR_OK
import io.agora.rtc2.IRtcEngineEventHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class VoiceChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VoiceChatViewModel"
        private const val MAX_LOG_LINES = 60
    }

    enum class ConnectionState {
        Idle,
        Connecting,
        Connected,
        Error,
    }

    data class ConversationUiState(
        val isMuted: Boolean = false,
        val isAudioInputEnabled: Boolean = false,
        val connectionState: ConnectionState = ConnectionState.Idle,
        val channelName: String = "",
    )

    private class ConnectionSessionState {
        var channelName: String = ""
        var rtcJoined: Boolean = false
        var rtmLoggedIn: Boolean = false

        val rtcAndRtmReady: Boolean
            get() = rtcJoined && rtmLoggedIn

        fun beginJoinAttempt(channelName: String) {
            this.channelName = channelName
            rtcJoined = false
            rtmLoggedIn = false
        }

        fun clear() {
            channelName = ""
            rtcJoined = false
            rtmLoggedIn = false
        }
    }

    private class AgentSessionState {
        var agentId: String? = null
        var authToken: String? = null

        fun clear() {
            agentId = null
            authToken = null
        }
    }

    val localUserId: Int = generateRandomUid()
    val agentUid: Int = generateUniqueUid(localUserId)

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private val _agentState = MutableStateFlow(stringOf(R.string.demo_agent_idle))
    val agentState: StateFlow<String> = _agentState.asStateFlow()

    private val _transcriptList = MutableStateFlow<List<String>>(emptyList())
    val transcriptList: StateFlow<List<String>> = _transcriptList.asStateFlow()

    private val _debugLogList = MutableStateFlow<List<String>>(emptyList())
    val debugLogList: StateFlow<List<String>> = _debugLogList.asStateFlow()

    private val connection = ConnectionSessionState()
    private val agentSession = AgentSessionState()

    private lateinit var manager: ConvoManager
    private val managerOrNull: ConvoManager?
        get() = if (::manager.isInitialized) manager else null

    private var transcriptItems: List<Transcript> = emptyList()

    @Volatile
    private var isStartingAgent = false

    private val rtcEventSink = object : ConversationRtcEventSink {
        override suspend fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            connection.rtcJoined = true
            managerOrNull?.audioInputManager?.setPublished(true)
            addStatusLog("Rtc onJoinChannelSuccess, channel:$channel uid:$uid")
            checkJoinAndLoginComplete()
        }

        override suspend fun onLeaveChannel(stats: IRtcEngineEventHandler.RtcStats?) {
            stopExternalAudioCapture()
            connection.rtcJoined = false
            addStatusLog("Rtc onLeaveChannel")
        }

        override suspend fun onUserJoined(uid: Int, elapsed: Int) {
            addStatusLog("Rtc onUserJoined, uid:$uid")
        }

        override suspend fun onUserOffline(uid: Int, reason: Int) {
            addStatusLog("Rtc onUserOffline, uid:$uid reason:$reason")
        }

        override suspend fun onRtcEngineError(err: Int) {
            addStatusLog("Rtc onError: $err")
            disconnectLocally(ConnectionState.Error, stopRemoteAgent = true)
        }
    }

    private val rtmEventSink = object : ConversationRtmEventSink {
        override fun onRtmLinkConnected() {
            addStatusLog("Rtm connected successfully")
        }

        override fun onRtmLinkFailed() {
            addStatusLog("Rtm connected failed")
            viewModelScope.launch {
                disconnectLocally(ConnectionState.Error, stopRemoteAgent = true)
            }
        }
    }

    private val conversationalAIAPIEventHandler = object : IConversationalAIAPIEventHandler {
        override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
            _agentState.value = agentStateLabel(event.state)
        }

        override fun onAgentInterrupted(agentUserId: String, event: InterruptEvent) = Unit

        override fun onAgentMetrics(agentUserId: String, metric: Metric) = Unit

        override fun onAgentError(agentUserId: String, error: ModuleError) {
            addStatusLog("Agent error: type=${error.type.value}, code=${error.code}, msg=${error.message}")
        }

        override fun onMessageError(agentUserId: String, error: MessageError) {
            addStatusLog("Message error: ${error.message}")
        }

        override fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt) = Unit

        override fun onAgentVoiceprintStateChanged(agentUserId: String, event: VoiceprintStateChangeEvent) = Unit

        override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
            transcriptItems = transcriptItems.upsertTranscript(transcript)
            _transcriptList.value = transcriptItems.map(::formatTranscript)
        }

        override fun onDebugLog(log: String) {
            addStatusLog(log)
        }
    }

    fun startConversation() {
        if (_uiState.value.connectionState == ConnectionState.Connecting ||
            _uiState.value.connectionState == ConnectionState.Connected
        ) {
            return
        }

        val currentManager = ensureManager() ?: return

        viewModelScope.launch {
            val channelName = generateRandomChannelName()
            prepareConversation(channelName)

            val userToken = generateUserToken() ?: return@launch
            if (!joinRtcChannel(currentManager, userToken, channelName, localUserId)) {
                return@launch
            }

            loginRtm(currentManager, userToken) { exception ->
                viewModelScope.launch {
                    if (exception == null) {
                        onRtmLoginSucceeded(channelName)
                    } else {
                        rollbackAfterRtmLoginFailed(exception)
                    }
                }
            }
        }
    }

    fun toggleMute() {
        val newMuteState = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(isMuted = newMuteState)
        managerOrNull?.rtcEngine?.adjustRecordingSignalVolume(if (newMuteState) 0 else 100)
        addStatusLog(if (newMuteState) "Microphone muted" else "Microphone unmuted")
    }

    fun startAudioInput(): Boolean {
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            addStatusLog("Audio input is only available after agent connected")
            return false
        }
        if (agentSession.agentId == null) {
            addStatusLog("Audio input is only available after agent started")
            return false
        }
        val started = startAudioInputInternal()
        if (!started) {
            addStatusLog("Enable audio input failed")
        }
        return started
    }

    fun stopAudioInput(): Boolean {
        if (!_uiState.value.isAudioInputEnabled) {
            return true
        }
        stopAudioInputInternal()
        return true
    }

    fun toggleAudioInput(): Boolean {
        return if (_uiState.value.isAudioInputEnabled) {
            stopAudioInput()
        } else {
            startAudioInput()
        }
    }

    fun hangup() {
        viewModelScope.launch {
            disconnectLocally(ConnectionState.Idle, stopRemoteAgent = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        val currentManager = managerOrNull ?: return
        stopExternalAudioCapture()
        if (connection.channelName.isNotBlank()) {
            currentManager.conversationalAIAPI.unsubscribeMessage(connection.channelName) { }
        }
        currentManager.rtcEngine.leaveChannel()
        ConversationRtmLogin.logout(currentManager.rtmClient, currentManager.rtmLoginState, TAG)
        currentManager.destroy()
    }

    private fun ensureManager(): ConvoManager? {
        managerOrNull?.let { return it }
        return runCatching {
            ConvoManager(
                context = getApplication<Application>().applicationContext,
                appId = ConvoConfig.APP_ID,
                userId = localUserId.toString(),
                scope = viewModelScope,
                config = ConvoManagerConfig(
                    enableConvoAiLog = true,
                    loadRtcAiExtensions = false,
                    onAudioInputInterrupted = {
                        _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
                        addStatusLog("Audio input stopped unexpectedly")
                    },
                ),
                rtcEventSink = rtcEventSink,
                rtmEventSink = rtmEventSink,
                convoAiEventHandler = conversationalAIAPIEventHandler,
                logTag = TAG,
                channelNameProvider = { connection.channelName },
            )
        }.onSuccess {
            manager = it
            addStatusLog("ConvoManager initialized")
        }.onFailure { exception ->
            setConversationError("Initialize ConvoManager failed: ${exception.message}", exception)
        }.getOrNull()
    }

    private fun prepareConversation(channelName: String) {
        connection.beginJoinAttempt(channelName)
        agentSession.clear()
        transcriptItems = emptyList()
        isStartingAgent = false
        _uiState.value = ConversationUiState(
            connectionState = ConnectionState.Connecting,
            channelName = channelName,
        )
        _agentState.value = stringOf(R.string.demo_agent_status_starting)
        _transcriptList.value = emptyList()
        addStatusLog("Starting conversation channel=$channelName user=$localUserId agent=$agentUid")
    }

    private suspend fun generateUserToken(): String? {
        return TokenGenerator.generateTokensAsync(
            channelName = "",
            uid = localUserId.toString(),
        ).fold(
            onSuccess = { token ->
                addStatusLog("Generate user token successfully")
                token
            },
            onFailure = { exception ->
                setConversationError("Generate user token failed: ${exception.message}", exception)
                null
            },
        )
    }

    private fun joinRtcChannel(
        manager: ConvoManager,
        rtcToken: String,
        channelName: String,
        uid: Int,
    ): Boolean {
        val customAudioTrackId = manager.audioInputManager.ensureCustomAudioTrack()
        if (customAudioTrackId < 0) {
            setConversationError("Create custom audio track failed ret:$customAudioTrackId")
            return false
        }

        val ret = joinConversationChannelWithOptions(
            rtcEngine = manager.rtcEngine,
            rtcToken = rtcToken,
            channelName = channelName,
            uid = uid,
            customAudioTrackId = customAudioTrackId,
        )
        if (ret != ERR_OK) {
            stopExternalAudioCapture()
            setConversationError("Rtc joinChannel failed ret:$ret")
            return false
        }

        addStatusLog("Rtc joinChannel requested")
        return true
    }

    private fun loginRtm(
        manager: ConvoManager,
        rtmToken: String,
        completion: (Exception?) -> Unit,
    ) {
        ConversationRtmLogin.loginAfterLogout(
            client = manager.rtmClient,
            rtmToken = rtmToken,
            state = manager.rtmLoginState,
            logTag = TAG,
            completion = completion,
            statusLog = { addStatusLog(it) },
        )
    }

    private fun checkJoinAndLoginComplete() {
        if (connection.rtcAndRtmReady) {
            if (_uiState.value.connectionState != ConnectionState.Connected) {
                _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Connected)
                addStatusLog("Rtc and Rtm are ready")
            }
            startAgent()
        }
    }

    private fun onRtmLoginSucceeded(channelName: String) {
        val currentManager = managerOrNull ?: return
        connection.rtmLoggedIn = true
        currentManager.conversationalAIAPI.subscribeMessage(channelName) { error ->
            if (error != null) {
                addStatusLog("Subscribe message error: ${error.errorMessage}")
            }
        }
        addStatusLog("Rtm login successfully")
        checkJoinAndLoginComplete()
    }

    private fun rollbackAfterRtmLoginFailed(exception: Exception) {
        stopExternalAudioCapture()
        managerOrNull?.rtcEngine?.leaveChannel()
        connection.clear()
        setConversationError("RTM login failed: ${exception.message}", exception)
    }

    private fun startAgent() {
        if (agentSession.agentId != null || isStartingAgent) {
            return
        }
        if (connection.channelName.isBlank()) {
            setConversationError("Channel name is empty, cannot start agent")
            return
        }

        isStartingAgent = true
        _agentState.value = stringOf(R.string.demo_agent_status_starting)

        viewModelScope.launch {
            val channelToken = TokenGenerator.generateTokensAsync(
                channelName = connection.channelName,
                uid = agentUid.toString(),
            ).getOrElse { exception ->
                isStartingAgent = false
                addStatusLog("Generate agent token failed: ${exception.message}")
                disconnectLocally(ConnectionState.Error, stopRemoteAgent = false)
                return@launch
            }

            val startResult = AgentStarter.startAgentAsync(
                channelName = connection.channelName,
                agentRtcUid = agentUid.toString(),
                agentToken = channelToken,
                authToken = channelToken,
                remoteRtcUid = localUserId.toString(),
            )
            startResult.fold(
                onSuccess = { agentId ->
                    agentSession.agentId = agentId
                    agentSession.authToken = channelToken
                    _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Connected)
                    _agentState.value = stringOf(R.string.demo_agent_status_running)
                    addStatusLog("Agent start successfully, agentId=$agentId")
                    if (!startAudioInputInternal()) {
                        addStatusLog("Enable audio input failed")
                    }
                },
                onFailure = { exception ->
                    addStatusLog("Agent start failed: ${exception.message}")
                    disconnectLocally(ConnectionState.Error, stopRemoteAgent = false)
                },
            )
            isStartingAgent = false
        }
    }

    private suspend fun disconnectLocally(
        targetState: ConnectionState,
        stopRemoteAgent: Boolean,
    ) {
        val currentManager = managerOrNull
        if (currentManager != null && connection.channelName.isNotBlank()) {
            currentManager.conversationalAIAPI.unsubscribeMessage(connection.channelName) { }
        }
        if (stopRemoteAgent) {
            stopRemoteAgentIfNeeded()
        }
        stopExternalAudioCapture()
        currentManager?.rtcEngine?.leaveChannel()
        if (currentManager != null) {
            ConversationRtmLogin.logout(currentManager.rtmClient, currentManager.rtmLoginState, TAG)
        }

        connection.clear()
        agentSession.clear()
        transcriptItems = emptyList()
        isStartingAgent = false
        _transcriptList.value = emptyList()
        _agentState.value = if (targetState == ConnectionState.Error) {
            stringOf(R.string.demo_agent_status_error)
        } else {
            stringOf(R.string.demo_agent_idle)
        }
        _uiState.value = ConversationUiState(connectionState = targetState)
    }

    private suspend fun stopRemoteAgentIfNeeded() {
        val agentId = agentSession.agentId ?: return
        AgentStarter.stopAgentAsync(
            agentId = agentId,
            authToken = agentSession.authToken.orEmpty(),
        ).onFailure { exception ->
            addStatusLog("Agent stop failed, agentId=$agentId: ${exception.message}")
        }.onSuccess {
            addStatusLog("Agent stopped successfully, agentId=$agentId")
        }
    }

    private fun startAudioInputInternal(): Boolean {
        val currentManager = managerOrNull ?: return false
        if (!currentManager.audioInputManager.start()) {
            _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
            return false
        }
        currentManager.rtcEngine.adjustRecordingSignalVolume(if (_uiState.value.isMuted) 0 else 100)
        _uiState.value = _uiState.value.copy(isAudioInputEnabled = true)
        return true
    }

    private fun stopAudioInputInternal() {
        managerOrNull?.audioInputManager?.stop()
        _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
    }

    private fun stopExternalAudioCapture() {
        managerOrNull?.audioInputManager?.stopAndUnpublish()
        _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
    }

    private fun setConversationError(message: String, exception: Throwable? = null) {
        addStatusLog(message)
        _agentState.value = stringOf(R.string.demo_agent_status_error)
        _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Error)
        if (exception != null) {
            Log.e(TAG, message, exception)
        } else {
            Log.e(TAG, message)
        }
    }

    private fun addStatusLog(message: String) {
        if (message.isBlank()) return
        val line = "[${System.currentTimeMillis()}] $message"
        _debugLogList.value = (_debugLogList.value + line).takeLast(MAX_LOG_LINES)
        Log.d(TAG, message)
    }

    private fun formatTranscript(transcript: Transcript): String {
        val speaker = if (transcript.type == TranscriptType.USER) {
            stringOf(R.string.demo_speaker_user)
        } else {
            stringOf(R.string.demo_speaker_agent)
        }
        val suffix = if (transcript.status == TranscriptStatus.IN_PROGRESS) " ..." else ""
        return "$speaker\n${transcript.text}$suffix"
    }

    private fun agentStateLabel(state: AgentState): String = when (state) {
        AgentState.IDLE -> stringOf(R.string.demo_agent_idle)
        AgentState.SILENT -> stringOf(R.string.demo_agent_silent)
        AgentState.LISTENING -> stringOf(R.string.demo_agent_listening)
        AgentState.THINKING -> stringOf(R.string.demo_agent_thinking)
        AgentState.SPEAKING -> stringOf(R.string.demo_agent_speaking)
        AgentState.UNKNOWN -> stringOf(R.string.demo_agent_unknown)
    }

    private fun stringOf(@StringRes resId: Int): String {
        return getApplication<Application>().getString(resId)
    }

    private fun List<Transcript>.upsertTranscript(transcript: Transcript): List<Transcript> {
        val mutable = toMutableList()
        val index = mutable.indexOfFirst {
            it.turnId == transcript.turnId && it.type == transcript.type
        }
        if (index >= 0) {
            mutable[index] = transcript
        } else {
            mutable.add(transcript)
        }
        return mutable
    }

    private fun generateUniqueUid(excludeUid: Int): Int {
        var candidate: Int
        do {
            candidate = generateRandomUid()
        } while (candidate == excludeUid)
        return candidate
    }

    private fun generateRandomChannelName(): String = "channel_kotlin_${generateRandomUid()}"

    private fun generateRandomUid(): Int = Random.nextInt(100000, 999999)
}
