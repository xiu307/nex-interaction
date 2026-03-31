package cn.shengwang.convoai.quickstart.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.shengwang.convoai.quickstart.AgentApp
import cn.shengwang.convoai.quickstart.KeyCenter
import cn.shengwang.convoai.quickstart.audio.CombinedAudioFrameObserver
import cn.shengwang.convoai.quickstart.audio.PcmFileRecorder
import cn.shengwang.convoai.quickstart.audio.PcmRecordAudioFrameObserver
import cn.shengwang.convoai.quickstart.api.AgentStarter
import cn.shengwang.convoai.quickstart.api.TokenGenerator
import cn.shengwang.convoai.quickstart.video.ExternalVideoCaptureManager
import io.agora.convoai.convoaiApi.AgentState
import io.agora.convoai.convoaiApi.ConversationalAIAPIConfig
import io.agora.convoai.convoaiApi.ConversationalAIAPIImpl
import io.agora.convoai.convoaiApi.IConversationalAIAPI
import io.agora.convoai.convoaiApi.IConversationalAIAPIEventHandler
import io.agora.convoai.convoaiApi.InterruptEvent
import io.agora.convoai.convoaiApi.MessageError
import io.agora.convoai.convoaiApi.MessageReceipt
import io.agora.convoai.convoaiApi.Metric
import io.agora.convoai.convoaiApi.ModuleError
import io.agora.convoai.convoaiApi.StateChangeEvent
import io.agora.convoai.convoaiApi.Transcript
import io.agora.convoai.convoaiApi.VoiceprintStateChangeEvent
import io.agora.rtc2.Constants
import io.agora.rtc2.Constants.CLIENT_ROLE_BROADCASTER
import io.agora.rtc2.Constants.ERR_OK
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.video.AgoraVideoFrame
import io.agora.rtm.ErrorInfo
import io.agora.rtm.LinkStateEvent
import io.agora.rtm.PresenceEvent
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmEventListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.core.content.edit

/**
 * ViewModel for managing conversation-related business logic
 */
class AgentChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "ConversationViewModel"
        private const val USER_PREFS_NAME = "agent_chat_prefs"
        private const val KEY_LOCAL_USER_ID = "local_user_id"
        private const val INVALID_UID = -1
        val userId = getOrCreateLocalUserId()
        val agentUid: Int = generateUniqueUid(userId)

        private fun getOrCreateLocalUserId(): Int {
            val sharedPreferences = AgentApp.instance().getSharedPreferences(
                USER_PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val cachedUserId = sharedPreferences.getInt(KEY_LOCAL_USER_ID, INVALID_UID)
            if (cachedUserId != INVALID_UID) {
                return cachedUserId
            }

            val newUserId = generateRandomUid()
            sharedPreferences.edit {
                putInt(KEY_LOCAL_USER_ID, newUserId)
            }
            return newUserId
        }

        /**
         * Generate a unique UID that doesn't conflict with the given uid
         */
        private fun generateUniqueUid(excludeUid: Int): Int {
            var uid: Int
            do {
                uid = generateRandomUid()
            } while (uid == excludeUid)
            return uid
        }

        private fun generateRandomUid(): Int {
            return (100000..999999).random()
        }

        /**
         * Generate a random channel name
         */
        fun generateRandomChannelName(): String {
            return "channel_kotlin_${generateRandomUid()}"
        }
    }

    /**
     * Connection state enum
     */
    enum class ConnectionState {
        Idle,
        Connecting,
        Connected,
        Error
    }

    // UI State - shared between AgentHomeFragment and VoiceAssistantFragment
    data class ConversationUiState constructor(
        val isMuted: Boolean = false,
        // Connection state
        val connectionState: ConnectionState = ConnectionState.Idle
    )

    data class PcmCaptureUiState(
        val isSaving: Boolean = false,
        val fileName: String? = null
    )

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    private val _pcmCaptureState = MutableStateFlow(PcmCaptureUiState())
    val pcmCaptureState: StateFlow<PcmCaptureUiState> = _pcmCaptureState.asStateFlow()

    // Transcript list - separate from UI state
    private val _transcriptList = MutableStateFlow<List<Transcript>>(emptyList())
    val transcriptList: StateFlow<List<Transcript>> = _transcriptList.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState?> = _agentState.asStateFlow()

    // Debug log list - for displaying logs in UI
    private val _debugLogList = MutableStateFlow<List<String>>(emptyList())
    val debugLogList: StateFlow<List<String>> = _debugLogList.asStateFlow()

    private var unifiedToken: String? = null

    private var conversationalAIAPI: IConversationalAIAPI? = null

    private var channelName: String = ""

    private var rtcJoined = false
    private var rtmLoggedIn = false

    // Agent management
    private var agentId: String? = null
    // Auth token for REST API (app-credentials mode)
    private var authToken: String? = null
    private val pcmFileRecorder = PcmFileRecorder(AgentApp.instance())
    private val pcmRecordAudioFrameObserver = PcmRecordAudioFrameObserver { audioData ->
        pcmFileRecorder.append(audioData).onFailure { exception ->
            viewModelScope.launch {
                stopPcmCaptureInternal(logSavedResult = false)
                addStatusLog("PCM save failed: ${exception.message}")
            }
        }
    }
    private var audioFrameObserverRegistered = false
    private var externalVideoCaptureManager: ExternalVideoCaptureManager? = null
    private var customVideoTrackPublished = false

    // RTC and RTM instances
    private var rtcEngine: RtcEngineEx? = null
    private var rtmClient: RtmClient? = null
    private var isRtmLogin = false
    private var isLoggingIn = false
    private val rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            viewModelScope.launch {
                rtcJoined = true
                externalVideoCaptureManager?.setFramePushEnabled(customVideoTrackPublished)
                addStatusLog("Rtc onJoinChannelSuccess, channel:${channel} uid:$uid")
                Log.d(TAG, "RTC joined channel: $channel, uid: $uid")
                checkJoinAndLoginComplete()
            }
        }

        override fun onLeaveChannel(stats: RtcStats?) {
            super.onLeaveChannel(stats)
            viewModelScope.launch {
                customVideoTrackPublished = false
                externalVideoCaptureManager?.setFramePushEnabled(false)
                addStatusLog("Rtc onLeaveChannel")
            }
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            viewModelScope.launch {
                addStatusLog("Rtc onUserJoined, uid:$uid")
                if (uid == agentUid) {
                    Log.d(TAG, "Agent joined the channel, uid: $uid")
                } else {
                    Log.d(TAG, "User joined the channel, uid: $uid")
                }
            }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            viewModelScope.launch {
                addStatusLog("Rtc onUserOffline, uid:$uid")
                if (uid == agentUid) {
                    Log.d(TAG, "Agent left the channel, uid: $uid, reason: $reason")
                } else {
                    Log.d(TAG, "User left the channel, uid: $uid, reason: $reason")
                }
            }
        }

        override fun onError(err: Int) {
            viewModelScope.launch {
                customVideoTrackPublished = false
                externalVideoCaptureManager?.setFramePushEnabled(false)
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.Error
                )
                addStatusLog("Rtc onError: $err")
                Log.e(TAG, "RTC error: $err")
            }
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            Log.d(TAG, "RTC onTokenPrivilegeWillExpire $channelName")
        }
    }

    // RTM event listener
    private val rtmEventListener = object : RtmEventListener {
        override fun onLinkStateEvent(event: LinkStateEvent?) {
            super.onLinkStateEvent(event)
            event ?: return

            Log.d(TAG, "Rtm link state changed: ${event.currentState}")

            when (event.currentState) {
                RtmConstants.RtmLinkState.CONNECTED -> {
                    Log.d(TAG, "Rtm connected successfully")
                    isRtmLogin = true
                    addStatusLog("Rtm connected successfully")
                }

                RtmConstants.RtmLinkState.FAILED -> {
                    Log.d(TAG, "RTM connection failed, need to re-login")
                    isRtmLogin = false
                    isLoggingIn = false
                    viewModelScope.launch {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionState.Error
                        )
                        addStatusLog("Rtm connected failed")
                        unifiedToken = null
                    }
                }

                else -> {
                    // nothing
                }
            }
        }

        override fun onTokenPrivilegeWillExpire(channelName: String) {
            Log.d(TAG, "RTM onTokenPrivilegeWillExpire $channelName")
        }

        override fun onPresenceEvent(event: PresenceEvent) {
            super.onPresenceEvent(event)
            // Handle presence events if needed
        }
    }

    private val conversationalAIAPIEventHandler = object : IConversationalAIAPIEventHandler {
        override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
            _agentState.value = event.state
        }

        override fun onAgentInterrupted(agentUserId: String, event: InterruptEvent) {
            // Handle interruption

        }

        override fun onAgentMetrics(agentUserId: String, metric: Metric) {
            // Handle metrics
        }

        override fun onAgentError(agentUserId: String, error: ModuleError) {
            addStatusLog("Agent error: type=${error.type.value}, code=${error.code}, msg=${error.message}")
        }

        override fun onMessageError(agentUserId: String, error: MessageError) {
            // Handle message error
        }

        override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
            // Handle transcript updates with typing animation for agent messages
            addTranscript(transcript)
        }

        override fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt) {
            // Handle message receipt
        }

        override fun onAgentVoiceprintStateChanged(agentUserId: String, event: VoiceprintStateChangeEvent) {
            // Update voice print state to notify Activity

        }

        override fun onDebugLog(log: String) {
            // Only log to system log, don't collect for UI display
            // UI will only show ViewModel status messages (statusMessage)
            Log.d("conversationalAIAPI", log)
        }
    }

    init {
        // Create RTC engine and RTM client during initialization
        Log.d(TAG, "Initializing RTC engine and RTM client...")
        // Init RTC engine
        initRtcEngine()
        // Init RTM client
        initRtmClient()
        if (rtcEngine != null && rtmClient != null) {
            conversationalAIAPI = ConversationalAIAPIImpl(
                ConversationalAIAPIConfig(
                    rtcEngine = rtcEngine!!,
                    rtmClient = rtmClient!!,
                    enableLog = true
                )
            )
            registerBusinessAudioFrameObserver()
            conversationalAIAPI?.loadAudioSettings(Constants.AUDIO_SCENARIO_AI_CLIENT)
            conversationalAIAPI?.addHandler(conversationalAIAPIEventHandler)
            Log.d(TAG, "RTC engine and RTM client created successfully")
        } else {
            Log.e(TAG, "Failed to create RTC engine or RTM client")
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.Error
            )
        }
    }

    /**
     * Init RTC engine
     */
    private fun initRtcEngine() {
        if (rtcEngine != null) {
            return
        }
        val config = RtcEngineConfig()
        config.mContext = AgentApp.Companion.instance()
        config.mAppId = KeyCenter.APP_ID
        config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
        config.mAudioScenario = Constants.AUDIO_SCENARIO_DEFAULT
        config.mEventHandler = rtcEventHandler
        try {
            rtcEngine = (RtcEngine.create(config) as RtcEngineEx).apply {
                enableVideo()
                // load extension provider for AI-QoS
                loadExtensionProvider("ai_echo_cancellation_extension")
                loadExtensionProvider("ai_noise_suppression_extension")
            }
            externalVideoCaptureManager = ExternalVideoCaptureManager(rtcEngine!!)
            Log.d(TAG, "initRtcEngine success")
            Log.d(TAG, "current sdk version: ${RtcEngine.getSdkVersion()}")
            addStatusLog("RtcEngine init successfully")
        } catch (e: Exception) {
            Log.e(TAG, "initRtcEngine error: $e")
            addStatusLog("RtcEngine init failed")
        }
    }

    private fun buildChannelMediaOptions(
        publishCustomVideoTrack: Boolean,
        customTrackId: Int? = null
    ): ChannelMediaOptions {
        return ChannelMediaOptions().apply {
            clientRoleType = CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true
            publishCameraTrack = false
            this.publishCustomVideoTrack = publishCustomVideoTrack
            if (customTrackId != null && customTrackId >= 0) {
                this.customVideoTrackId = customTrackId
            }
            autoSubscribeAudio = true
            autoSubscribeVideo = true
            startPreview = false
        }
    }

    /**
     * Init RTM client
     */
    private fun initRtmClient() {
        if (rtmClient != null) {
            return
        }

        val rtmConfig = RtmConfig.Builder(KeyCenter.APP_ID, userId.toString()).build()
        try {
            rtmClient = RtmClient.create(rtmConfig)
            rtmClient?.addEventListener(rtmEventListener)
            Log.d(TAG, "RTM initRtmClient successfully")
            addStatusLog("RtmClient init successfully")
        } catch (e: Exception) {
            Log.e(TAG, "RTM initRtmClient error: ${e.message}")
            e.printStackTrace()
            addStatusLog("RtmClient init failed")
        }
    }

    /**
     * Login RTM
     */
    private fun loginRtm(rtmToken: String, completion: (Exception?) -> Unit) {
        Log.d(TAG, "Starting RTM login")

        if (isLoggingIn) {
            completion.invoke(Exception("Login already in progress"))
            Log.d(TAG, "Login already in progress")
            return
        }

        if (isRtmLogin) {
            completion.invoke(null) // Already logged in
            Log.d(TAG, "Already logged in")
            return
        }

        val client = this.rtmClient ?: run {
            completion.invoke(Exception("RTM client not initialized"))
            Log.d(TAG, "RTM client not initialized")
            return
        }

        isLoggingIn = true
        Log.d(TAG, "Performing logout to ensure clean environment before login")

        // Force logout first (synchronous flag update)
        isRtmLogin = false
        client.logout(object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) {
                Log.d(TAG, "Logout completed, starting login")
                performRtmLogin(client, rtmToken, completion)
            }

            override fun onFailure(errorInfo: ErrorInfo?) {
                Log.d(TAG, "Logout failed but continuing with login: ${errorInfo?.errorReason}")
                performRtmLogin(client, rtmToken, completion)
            }
        })
    }

    private fun performRtmLogin(client: RtmClient, rtmToken: String, completion: (Exception?) -> Unit) {
        client.login(rtmToken, object : ResultCallback<Void> {
            override fun onSuccess(p0: Void?) {
                isRtmLogin = true
                isLoggingIn = false
                Log.d(TAG, "RTM login successful")
                completion.invoke(null)
                addStatusLog("Rtm login successful")
            }

            override fun onFailure(errorInfo: ErrorInfo?) {
                isRtmLogin = false
                isLoggingIn = false
                Log.e(TAG, "RTM token login failed: ${errorInfo?.errorReason}")
                completion.invoke(Exception("${errorInfo?.errorCode}"))
                addStatusLog("Rtm login failed, code: ${errorInfo?.errorCode}")
            }
        })
    }

    /**
     * Logout RTM
     */
    private fun logoutRtm() {
        Log.d(TAG, "RTM start logout")
        rtmClient?.logout(object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) {
                isRtmLogin = false
                Log.d(TAG, "RTM logout successful")
            }

            override fun onFailure(errorInfo: ErrorInfo?) {
                Log.e(TAG, "RTM logout failed: ${errorInfo?.errorCode}")
                // Still mark as logged out since we attempted logout
                isRtmLogin = false
            }
        })
    }

    /**
     * Join RTC channel
     */
    private fun joinRtcChannel(rtcToken: String, channelName: String, uid: Int) {
        Log.d(TAG, "joinChannel channelName: $channelName, localUid: $uid")
        // join rtc channel
        customVideoTrackPublished = false
        val channelOptions = buildChannelMediaOptions(
            publishCustomVideoTrack = false
        )
        externalVideoCaptureManager?.setFramePushEnabled(false)
        val ret = rtcEngine?.joinChannel(rtcToken, channelName, uid, channelOptions)
        Log.d(TAG, "Joining RTC channel: $channelName, uid: $uid")
        if (ret == ERR_OK) {
            Log.d(TAG, "Join RTC room success")
        } else {
            Log.e(TAG, "Join RTC room failed, ret: $ret")
            addStatusLog("Rtc joinChannel failed ret: $ret")
        }
    }

    /**
     * Leave RTC channel
     */
    private fun leaveRtcChannel() {
        Log.d(TAG, "leaveChannel")
        rtcEngine?.leaveChannel()
    }

    /**
     * Mute local audio
     */
    private fun muteLocalAudio(mute: Boolean) {
        Log.d(TAG, "muteLocalAudio $mute")
        rtcEngine?.adjustRecordingSignalVolume(if (mute) 0 else 100)
    }

    /**
     * Check if both RTC and RTM are connected, then start agent
     */
    private fun checkJoinAndLoginComplete() {
        if (rtcJoined && rtmLoggedIn) {
            startAgent()
        }
    }

    /**
     * Start agent (called automatically after RTC and RTM are connected)
     */
    fun startAgent() {
        viewModelScope.launch {
            if (agentId != null) {
                Log.d(TAG, "Agent already started, agentId: $agentId")
                return@launch
            }

            // Generate token for agent (always required)
            val tokenResult = TokenGenerator.generateTokensAsync(
                channelName = channelName,
                uid = agentUid.toString()
            )

            val agentToken = tokenResult.fold(
                onSuccess = { token ->
                    addStatusLog("Generate agent token successfully")
                    token
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Error
                    )
                    addStatusLog("Generate agent token failed")
                    Log.e(TAG, "Failed to generate agent token: ${exception.message}", exception)
                    return@launch
                }
            )

            // Generate auth token for REST API (requires APP_CERTIFICATE)
            val authTokenResult = TokenGenerator.generateTokensAsync(
                channelName = channelName,
                uid = agentUid.toString()
            )

            val restAuthToken = authTokenResult.fold(
                onSuccess = { token ->
                    authToken = token
                    addStatusLog("Generate auth token successfully")
                    token
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Error
                    )
                    addStatusLog("Generate auth token failed")
                    Log.e(TAG, "Failed to generate auth token: ${exception.message}", exception)
                    return@launch
                }
            )

            val startAgentResult = AgentStarter.startAgentAsync(
                channelName = channelName,
                agentRtcUid = agentUid.toString(),
                agentToken = agentToken,
                authToken = restAuthToken,
                remoteRtcUid = userId.toString()
            )
            startAgentResult.fold(
                onSuccess = { agentId ->
                    this@AgentChatViewModel.agentId = agentId
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Connected
                    )
                    addStatusLog("Agent start successfully")
                    Log.d(TAG, "Agent started successfully, agentId: $agentId")
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Error
                    )
                    addStatusLog("Agent start failed")
                    Log.e(TAG, "Failed to start agent: ${exception.message}", exception)
                }
            )
        }
    }

    /**
     * Generate unified token for RTC and RTM
     *
     * @return Token string on success, null on failure
     */
    private suspend fun generateUserToken(): String? {
        // Get unified token for both RTC and RTM
        val tokenResult = TokenGenerator.generateTokensAsync(
            channelName = "",
            uid = userId.toString(),
        )

        return tokenResult.fold(
            onSuccess = { token ->
                addStatusLog("Generate user token successfully")
                unifiedToken = token
                token
            },
            onFailure = { exception ->
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.Error
                )
                addStatusLog("Generate user token failed")
                Log.e(TAG, "Failed to get token: ${exception.message}", exception)
                null
            }
        )
    }

    /**
     * Join RTC channel and login RTM
     * @param channelName Channel name to join
     */
    fun joinChannelAndLogin(channelName: String) {
        viewModelScope.launch {
            this@AgentChatViewModel.channelName = channelName
            rtcJoined = false
            rtmLoggedIn = false

            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.Connecting
            )

            // Get token if not available, otherwise use existing token
            val token = unifiedToken ?: generateUserToken() ?: return@launch

            // Join RTC channel with the unified token
            joinRtcChannel(token, channelName, userId)

            // Login RTM with the same unified token
            loginRtm(token) { exception ->
                viewModelScope.launch {
                    if (exception == null) {
                        rtmLoggedIn = true
                        conversationalAIAPI?.subscribeMessage(channelName) { errorInfo ->
                            if (errorInfo != null) {
                                Log.e(TAG, "Subscribe message error: ${errorInfo}")
                            }
                        }
                        checkJoinAndLoginComplete()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            connectionState = ConnectionState.Error
                        )
                        Log.e(TAG, "RTM login failed: ${exception.message}", exception)
                    }
                }
            }

        }
    }

    /**
     * Toggle microphone mute state
     */
    fun toggleMute() {
        val newMuteState = !_uiState.value.isMuted
        _uiState.value = _uiState.value.copy(
            isMuted = newMuteState
        )
        muteLocalAudio(newMuteState)
        Log.d(TAG, "Microphone muted: $newMuteState")
    }

    /**
     * Add a new transcript to the list
     */
    fun addTranscript(transcript: Transcript) {
        viewModelScope.launch {
            val currentList = _transcriptList.value.toMutableList()
            // Update existing transcript if same turnId, otherwise add new
            val existingIndex =
                currentList.indexOfFirst { it.turnId == transcript.turnId && it.type == transcript.type }
            if (existingIndex >= 0) {
                currentList[existingIndex] = transcript
            } else {
                currentList.add(transcript)
            }
            _transcriptList.value = currentList
        }
    }

    /**
     * Add a status message to debug log list
     * This is used to track ViewModel state changes that are shown via SnackbarHelper
     */
    private fun addStatusLog(message: String) {
        if (message.isEmpty()) return
        viewModelScope.launch {
            val currentLogs = _debugLogList.value.toMutableList()
            currentLogs.add(message)
            // Keep only last 100 logs to avoid memory issues
            if (currentLogs.size > 20) {
                currentLogs.removeAt(0)
            }
            _debugLogList.value = currentLogs
        }
    }

    /**
     * Hang up and cleanup connections
     */
    fun hangup() {
        viewModelScope.launch {
            try {
                stopPcmCaptureInternal()
                conversationalAIAPI?.unsubscribeMessage(channelName) { errorInfo ->
                    if (errorInfo != null) {
                        Log.e(TAG, "Unsubscribe message error: ${errorInfo}")
                    }
                }

                // Stop agent if it was started
                if (agentId != null) {
                    val stopResult = AgentStarter.stopAgentAsync(
                        agentId = agentId!!,
                        authToken = authToken ?: ""
                    )
                    stopResult.fold(
                        onSuccess = {
                            Log.d(TAG, "Agent stopped successfully")
                            addStatusLog("Agent stopped successfully")
                        },
                        onFailure = { exception ->
                            Log.e(TAG, "Failed to stop agent: ${exception.message}", exception)
                        }
                    )
                    agentId = null
                }

                leaveRtcChannel()
                rtcJoined = false
                customVideoTrackPublished = false
                authToken = null
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.Idle
                )
                _transcriptList.value = emptyList()
                _agentState.value = AgentState.IDLE
                Log.d(TAG, "Hangup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during hangup: ${e.message}", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPcmCaptureInternal()
        leaveRtcChannel()
        logoutRtm()
        conversationalAIAPI?.removeHandler(conversationalAIAPIEventHandler)
        conversationalAIAPI?.destroy()
        conversationalAIAPI = null

        // Cleanup RTM client
        rtmClient?.let { client ->
            try {
                client.removeEventListener(rtmEventListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing RTM event listener: ${e.message}")
            }
        }

        // Note: RtcEngine.destroy() should be called carefully as it's a global operation
        // Consider managing RTC engine lifecycle at Application level
        externalVideoCaptureManager?.release()
        externalVideoCaptureManager = null
        rtcEngine = null
        rtmClient = null
    }

    private fun registerBusinessAudioFrameObserver() {
        if (audioFrameObserverRegistered) {
            return
        }
        val transcriptAudioFrameObserver = conversationalAIAPI?.getAudioFrameObserver() ?: return
        val observer = CombinedAudioFrameObserver(
            listOf(transcriptAudioFrameObserver, pcmRecordAudioFrameObserver)
        )
        val registerResult = rtcEngine?.registerAudioFrameObserver(observer)
        if (registerResult == ERR_OK) {
            val recordResult = rtcEngine?.setRecordingAudioFrameParameters(
                PcmRecordAudioFrameObserver.SAMPLE_RATE,
                PcmRecordAudioFrameObserver.CHANNELS,
                PcmRecordAudioFrameObserver.MODE,
                PcmRecordAudioFrameObserver.SAMPLES_PER_CALL
            )
            audioFrameObserverRegistered = true
            addStatusLog("AudioFrameObserver registered successfully")
            if (recordResult != ERR_OK) {
                addStatusLog("Recording audio frame params failed ret: $recordResult")
            }
        } else {
            addStatusLog("AudioFrameObserver register failed ret: $registerResult")
        }
    }

    /**
     * 切换 PCM 原始音频保存状态。
     *
     * 当前未保存时会启动 PCM 采集并落盘；如果已经在保存，则停止采集并
     * 关闭当前文件。该能力仅在 Agent 已连接后可用。
     */
    fun togglePcmCapture() {
        if (_pcmCaptureState.value.isSaving) {
            stopPcmCaptureInternal()
        } else {
            startPcmCaptureInternal()
        }
    }

    private fun startPcmCaptureInternal() {
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            addStatusLog("PCM capture is only available after agent connected")
            return
        }
        pcmFileRecorder.start().fold(
            onSuccess = { file ->
                _pcmCaptureState.value = PcmCaptureUiState(
                    isSaving = true,
                    fileName = file.name
                )
                addStatusLog("PCM saving started: ${file.name}")
            },
            onFailure = { exception ->
                addStatusLog("PCM saving start failed: ${exception.message}")
            }
        )
    }

    private fun stopPcmCaptureInternal(logSavedResult: Boolean = true) {
        val wasSaving = _pcmCaptureState.value.isSaving || pcmFileRecorder.isSaving()
        if (!wasSaving) {
            return
        }
        val currentFileName = _pcmCaptureState.value.fileName
        pcmFileRecorder.stop().fold(
            onSuccess = { file ->
                _pcmCaptureState.value = PcmCaptureUiState()
                if (logSavedResult) {
                    val savedPath = file?.absolutePath
                    if (savedPath != null) {
                        addStatusLog("PCM saved: $savedPath")
                    } else if (currentFileName != null) {
                        addStatusLog("PCM saving stopped: $currentFileName")
                    }
                }
            },
            onFailure = { exception ->
                _pcmCaptureState.value = PcmCaptureUiState()
                addStatusLog("PCM saving stop failed: ${exception.message}")
            }
        )
    }

    /**
     * 注册业务侧 PCM 数据回调。
     *
     * 回调数据来自当前录制音频帧观察器，适合用于原始音频保存、转发
     * 或接入自定义音频分析逻辑；传入 `null` 可取消监听。
     *
     * @param listener PCM 数据监听器，参数为每次回调的原始字节数组
     */
    fun setOnPcmDataListener(listener: ((ByteArray) -> Unit)?) {
        pcmRecordAudioFrameObserver.setOnPcmDataListener(listener)
    }

    /**
     * 开启或关闭 RTC 自定义视频发布。
     *
     * 开启时会确保自定义视频轨存在，并通过 `updateChannelMediaOptions`
     * 把该轨道发布到频道；关闭时会立即停止继续推帧，并取消当前自定义
     * 视频发布。该方法通常在业务侧开始/停止外部视频输入前后调用。
     *
     * @param enabled `true` 表示开启自定义视频发布，`false` 表示关闭
     * @return `true` 表示切换成功，`false` 表示切换失败
     */
    fun setExternalVideoPublishingEnabled(enabled: Boolean): Boolean {
        val manager = externalVideoCaptureManager ?: return false

        if (!enabled) {
            manager.setFramePushEnabled(false)
            if (!rtcJoined) {
                customVideoTrackPublished = false
                return true
            }
        } else if (customVideoTrackPublished) {
            manager.setFramePushEnabled(true)
            return true
        } else if (!rtcJoined) {
            addStatusLog("External video publishing requires an active RTC connection")
            return false
        }

        val customTrackId = if (enabled) {
            manager.ensureCustomVideoTrack()
        } else {
            manager.getCustomVideoTrackId()
        }

        if (enabled && customTrackId < 0) {
            addStatusLog("Create custom video track failed ret: $customTrackId")
            return false
        }

        val result = rtcEngine?.updateChannelMediaOptions(
            buildChannelMediaOptions(
                publishCustomVideoTrack = enabled,
                customTrackId = customTrackId.takeIf { it >= 0 }
            )
        ) ?: -1

        return if (result == ERR_OK) {
            customVideoTrackPublished = enabled
            manager.setFramePushEnabled(enabled)
            addStatusLog(
                if (enabled) {
                    "External video publishing enabled"
                } else {
                    "External video publishing disabled"
                }
            )
            true
        } else {
            addStatusLog(
                if (enabled) {
                    "Enable external video publishing failed ret: $result"
                } else {
                    "Disable external video publishing failed ret: $result"
                }
            )
            false
        }
    }

    /**
     * 推送一帧业务侧已经组装好的外部视频帧。
     *
     * 调用前需要先确保 RTC 已加入频道，并通过 [setExternalVideoPublishingEnabled]
     * 打开自定义视频发布；否则该帧会被直接丢弃。
     *
     * @param frame 业务侧构造完成的 Agora 视频帧对象
     * @return SDK 推帧结果，`0` 表示成功，负数表示失败
     */
    fun pushExternalVideoFrame(frame: AgoraVideoFrame): Int {
        return externalVideoCaptureManager?.pushExternalVideoFrame(frame) ?: -1
    }

    /**
     * 直接以 NV21 原始数据推送一帧外部视频。
     *
     * 适用于业务侧拿到摄像头、采集卡或其他外设输出的 NV21 数据后，
     * 不再手动组装 [AgoraVideoFrame]，而是通过该方法快速送入 RTC。
     *
     * @param data NV21 格式的原始视频帧数据
     * @param width 帧宽，单位为像素
     * @param height 帧高，单位为像素
     * @param rotation 画面顺时针旋转角度，默认 `0`
     * @param timestampMs 帧时间戳，单位为毫秒，默认使用当前系统时间
     * @return SDK 推帧结果，`0` 表示成功，负数表示失败
     */
    fun pushExternalNv21Frame(
        data: ByteArray,
        width: Int,
        height: Int,
        rotation: Int = 0,
        timestampMs: Long = System.currentTimeMillis()
    ): Int {
        return externalVideoCaptureManager?.pushExternalNv21Frame(
            data = data,
            width = width,
            height = height,
            rotation = rotation,
            timestampMs = timestampMs
        ) ?: -1
    }
}
