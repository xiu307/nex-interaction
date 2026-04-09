package ai.nex.interaction.ui

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.nex.interaction.AgentApp
import ai.nex.interaction.KeyCenter
import ai.nex.interaction.audio.CustomAudioInputManager
import ai.nex.interaction.video.ConversationExternalVideoPublishController
import ai.nex.interaction.video.ExternalVideoCaptureManager
import ai.nex.interaction.vendor.convoai.AgentState
import ai.nex.interaction.vendor.convoai.ConversationalAIAPIConfig
import ai.nex.interaction.vendor.convoai.ConversationalAIAPIImpl
import ai.nex.interaction.vendor.convoai.IConversationalAIAPI
import ai.nex.interaction.vendor.convoai.ModuleError
import ai.nex.interaction.vendor.convoai.StateChangeEvent
import ai.nex.interaction.vendor.convoai.Transcript
import ai.nex.interaction.vendor.convoai.TranscriptType
import io.agora.rtc2.Constants
import io.agora.rtc2.Constants.ERR_OK
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.IRtcEngineEventHandler.RtcStats
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.video.AgoraVideoFrame
import io.agora.rtm.RtmClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import ai.nex.interaction.convoai.ConversationConvoAiApiEventBridge
import ai.nex.interaction.convoai.DefaultConversationConvoAiEventSink
import ai.nex.interaction.biometric.FaceRtmStreamPublisher
import ai.nex.interaction.biometric.RobotFaceSpeakerBindCoordinator
import ai.nex.interaction.rtc.ConversationRtcEngineEventHandler
import ai.nex.interaction.rtc.ConversationRtcEventSink
import ai.nex.interaction.rtc.joinConversationChannelWithOptions
import ai.nex.interaction.rtc.buildConversationRtcEngineConfig
import ai.nex.interaction.rtc.loadConversationRtcAiExtensions
import ai.nex.interaction.rtm.ConversationRtmEventListener
import ai.nex.interaction.rtm.ConversationRtmEventSink
import ai.nex.interaction.rtm.ConversationRtmLogin
import ai.nex.interaction.rtm.RtmLoginState
import ai.nex.interaction.rtm.createConversationRtmConfig
import ai.nex.interaction.session.AgentSessionState
import ai.nex.interaction.session.ConversationAgentRestCoordinator
import ai.nex.interaction.session.ConversationUserTokenLoader
import ai.nex.interaction.session.ConnectionSessionState
import ai.nex.interaction.session.ConversationSessionIdentity
import ai.nex.interaction.tools.appendDebugStatusLine
import ai.nex.interaction.transcript.upsertTranscript

/**
 * ViewModel for managing conversation-related business logic
 */
class AgentChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "ConversationViewModel"

        val userId: Int = ConversationSessionIdentity.userId
        val agentUid: Int = ConversationSessionIdentity.agentUid

        fun generateRandomChannelName(): String =
            ConversationSessionIdentity.generateRandomChannelName()
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
        val isAudioInputEnabled: Boolean = false,
        // Connection state
        val connectionState: ConnectionState = ConnectionState.Idle
    )

    private val _uiState = MutableStateFlow(ConversationUiState())
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    // Transcript list - separate from UI state
    private val _transcriptList = MutableStateFlow<List<Transcript>>(emptyList())
    val transcriptList: StateFlow<List<Transcript>> = _transcriptList.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState?> = _agentState.asStateFlow()

    // Debug log list - for displaying logs in UI
    private val _debugLogList = MutableStateFlow<List<String>>(emptyList())
    val debugLogList: StateFlow<List<String>> = _debugLogList.asStateFlow()

    private val connection = ConnectionSessionState()
    private val agentSession = AgentSessionState()

    private var conversationalAIAPI: IConversationalAIAPI? = null
    private var audioInputManager: CustomAudioInputManager? = null
    private var externalVideoCaptureManager: ExternalVideoCaptureManager? = null

    private val externalVideoPublish by lazy {
        ConversationExternalVideoPublishController(
            rtcEngine = { rtcEngine },
            audioInputManager = { audioInputManager },
            externalVideoCapture = { externalVideoCaptureManager },
            connection = { connection },
            onStatusLog = { addStatusLog(it) },
        )
    }

    // RTC and RTM instances
    private var rtcEngine: RtcEngineEx? = null
    private var rtmClient: RtmClient? = null
    private val rtmLoginState = RtmLoginState()

    private val robotFaceSpeakerBind = RobotFaceSpeakerBindCoordinator()

    private val rtcEventSink = object : ConversationRtcEventSink {
        override suspend fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            connection.rtcJoined = true
            audioInputManager?.setPublished(true)
            externalVideoPublish.onRtcJoinChannelSuccess()
            addStatusLog("Rtc onJoinChannelSuccess, channel:${channel} uid:$uid")
            Log.d(TAG, "RTC joined channel: $channel, uid: $uid")
            checkJoinAndLoginComplete()
        }

        override suspend fun onLeaveChannel(stats: RtcStats?) {
            stopExternalAudioCapture()
            externalVideoPublish.resetVideoPipelineOnLeaveOrError()
            addStatusLog("Rtc onLeaveChannel")
        }

        override suspend fun onUserJoined(uid: Int, elapsed: Int) {
            addStatusLog("Rtc onUserJoined, uid:$uid")
            if (uid == agentUid) {
                Log.d(TAG, "Agent joined the channel, uid: $uid")
            } else {
                Log.d(TAG, "User joined the channel, uid: $uid")
            }
        }

        override suspend fun onUserOffline(uid: Int, reason: Int) {
            addStatusLog("Rtc onUserOffline, uid:$uid")
            if (uid == agentUid) {
                Log.d(TAG, "Agent left the channel, uid: $uid, reason: $reason")
            } else {
                Log.d(TAG, "User left the channel, uid: $uid, reason: $reason")
            }
        }

        override suspend fun onRtcEngineError(err: Int) {
            stopExternalAudioCapture()
            externalVideoPublish.resetVideoPipelineOnLeaveOrError()
            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.Error
            )
            addStatusLog("Rtc onError: $err")
            Log.e(TAG, "RTC error: $err")
        }
    }

    private val rtcEventHandler = ConversationRtcEngineEventHandler(
        scope = viewModelScope,
        logTag = TAG,
        channelNameProvider = { connection.channelName },
        sink = rtcEventSink,
    )

    // RTM event listener
    private val rtmEventSink = object : ConversationRtmEventSink {
        override fun onRtmLinkConnected() {
            Log.d(TAG, "Rtm connected successfully")
            rtmLoginState.isRtmLogin = true
            addStatusLog("Rtm connected successfully")
        }

        override fun onRtmLinkFailed() {
            Log.d(TAG, "RTM connection failed, need to re-login")
            rtmLoginState.isRtmLogin = false
            rtmLoginState.isLoggingIn = false
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.Error
                )
                addStatusLog("Rtm connected failed")
                connection.unifiedToken = null
            }
        }
    }

    private val rtmEventListener = ConversationRtmEventListener(TAG, rtmEventSink)

    private val convoAiEventSink = object : DefaultConversationConvoAiEventSink() {
        override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
            _agentState.value = event.state
        }

        override fun onAgentError(agentUserId: String, error: ModuleError) {
            addStatusLog("Agent error: type=${error.type.value}, code=${error.code}, msg=${error.message}")
        }

        override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
            addTranscript(transcript)
            if (transcript.type == TranscriptType.USER) {
                robotFaceSpeakerBind.maybeSendOnUserTranscript(
                    transcript = transcript,
                    connectionConnected = _uiState.value.connectionState == ConnectionState.Connected,
                    rtmClient = rtmClient,
                    clientId = rtmReportClientId(),
                )
            }
        }
    }

    private val conversationalAIAPIEventHandler = ConversationConvoAiApiEventBridge(convoAiEventSink)

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
        val config = buildConversationRtcEngineConfig(
            context = AgentApp.instance(),
            appId = KeyCenter.APP_ID,
            eventHandler = rtcEventHandler,
        )
        try {
            rtcEngine = (RtcEngine.create(config) as RtcEngineEx).apply {
                enableVideo()
                loadConversationRtcAiExtensions()
            }
            audioInputManager = CustomAudioInputManager(
                rtcEngine = rtcEngine!!,
                onAudioInputInterrupted = {
                    _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
                    addStatusLog("Audio input stopped unexpectedly")
                }
            )
            externalVideoCaptureManager = ExternalVideoCaptureManager(rtcEngine!!)
            Log.d(TAG, "initRtcEngine success")
            Log.d(TAG, "current sdk version: ${RtcEngine.getSdkVersion()}")
            addStatusLog("RtcEngine init successfully")
        } catch (e: Exception) {
            Log.e(TAG, "initRtcEngine error: $e")
            addStatusLog("RtcEngine init failed")
        }
    }

    /**
     * Init RTM client
     */
    private fun initRtmClient() {
        if (rtmClient != null) {
            return
        }

        val rtmConfig = createConversationRtmConfig(KeyCenter.APP_ID, userId.toString())
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

    private fun loginRtm(rtmToken: String, completion: (Exception?) -> Unit) {
        ConversationRtmLogin.loginAfterLogout(
            client = rtmClient,
            rtmToken = rtmToken,
            state = rtmLoginState,
            logTag = TAG,
            completion = completion,
            statusLog = { addStatusLog(it) },
        )
    }

    private fun logoutRtm() {
        ConversationRtmLogin.logout(rtmClient, rtmLoginState, TAG)
    }

    /**
     * Join RTC channel
     */
    private fun joinRtcChannel(rtcToken: String, channelName: String, uid: Int): Boolean {
        Log.d(TAG, "joinChannel channelName: $channelName, localUid: $uid")
        val audioManager = audioInputManager
        if (audioManager == null) {
            addStatusLog("External audio manager is not initialized")
            _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Error)
            return false
        }

        val customAudioTrackId = audioManager.ensureCustomAudioTrack()
        if (customAudioTrackId < 0) {
            addStatusLog("Create custom audio track failed ret: $customAudioTrackId")
            _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Error)
            return false
        }

        externalVideoPublish.prepareForNewRtcJoin()
        val ret = joinConversationChannelWithOptions(
            rtcEngine = rtcEngine,
            rtcToken = rtcToken,
            channelName = channelName,
            uid = uid,
            customAudioTrackId = customAudioTrackId,
            publishCustomVideoTrack = false,
        )
        Log.d(TAG, "Joining RTC channel: $channelName, uid: $uid")
        if (ret == ERR_OK) {
            Log.d(TAG, "Join RTC room success")
            return true
        } else {
            stopExternalAudioCapture()
            Log.e(TAG, "Join RTC room failed, ret: $ret")
            addStatusLog("Rtc joinChannel failed ret: $ret")
            _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Error)
            return false
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
        if (connection.rtcAndRtmReady) {
            startAgent()
        }
    }

    /**
     * Start agent (called automatically after RTC and RTM are connected)
     */
    fun startAgent() {
        viewModelScope.launch {
            if (agentSession.agentId != null) {
                Log.d(TAG, "Agent already started, agentId: ${agentSession.agentId}")
                addStatusLog("Agent already started, agentId=${agentSession.agentId}")
                return@launch
            }

            val startResult = ConversationAgentRestCoordinator.startRemoteAgent(
                channelName = connection.channelName,
                agentRtcUid = agentUid.toString(),
                remoteRtcUid = userId.toString(),
            )
            startResult.fold(
                onSuccess = { outcome ->
                    agentSession.agentId = outcome.agentId
                    agentSession.authToken = outcome.channelScopedToken
                    addStatusLog("Generate channel token & start agent OK")
                    if (!startAudioInputInternal()) {
                        addStatusLog("Enable audio input failed")
                    }
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Connected
                    )
                    addStatusLog("Agent start successfully, agentId=${outcome.agentId}")
                    Log.d(TAG, "Agent started successfully, agentId: ${outcome.agentId}")
                    Log.i(TAG, "join 请求体已含 SAL；sample_urls 见 logcat 标签 SAL 或 AgentStarter（需 Debug 包且含最新代码）")
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        connectionState = ConnectionState.Error
                    )
                    addStatusLog("Agent start failed")
                    Log.e(TAG, "startRemoteAgent failed: ${exception.message}", exception)
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
        return ConversationUserTokenLoader.fetchAndStoreUnifiedUserToken(
            connection = connection,
            userId = userId.toString(),
        ).fold(
            onSuccess = { token ->
                addStatusLog("Generate user token successfully")
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

    /** RTM 登录成功：标记状态、订阅频道消息，并在 RTC 也已就绪时启动 Agent。 */
    private fun onRtmLoginSucceeded(channelName: String) {
        connection.rtmLoggedIn = true
        conversationalAIAPI?.subscribeMessage(channelName) { errorInfo ->
            if (errorInfo != null) {
                Log.e(TAG, "Subscribe message error: ${errorInfo}")
            }
        }
        checkJoinAndLoginComplete()
    }

    /** RTM 登录失败：停止采集、离开 RTC 并回到 Error，避免半连接状态。 */
    private fun rollbackAfterRtmLoginFailed(exception: Exception) {
        stopExternalAudioCapture()
        leaveRtcChannel()
        connection.markRtcLeft()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.Error
        )
        Log.e(TAG, "RTM login failed: ${exception.message}", exception)
    }

    /**
     * Join RTC channel and login RTM
     * @param channelName Channel name to join
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun joinChannelAndLogin(channelName: String) {
        viewModelScope.launch {
            connection.beginJoinAttempt(channelName)

            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.Connecting
            )

            // Get token if not available, otherwise use existing token
            val token = connection.unifiedToken ?: generateUserToken() ?: return@launch

            // Join RTC channel with the unified token
            if (!joinRtcChannel(token, channelName, userId)) {
                return@launch
            }

            // Login RTM with the same unified token
            loginRtm(token) { exception ->
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

    fun startAudioInput(): Boolean {
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            addStatusLog("Audio input is only available after agent connected")
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun toggleAudioInput(): Boolean {
        return if (_uiState.value.isAudioInputEnabled) {
            stopAudioInput()
        } else {
            startAudioInput()
        }
    }

    /**
     * Add a new transcript to the list
     */
    fun addTranscript(transcript: Transcript) {
        viewModelScope.launch {
            _transcriptList.value = _transcriptList.value.upsertTranscript(transcript)
        }
    }

    /**
     * Add a status message to debug log list
     * This is used to track ViewModel state changes that are shown via SnackbarHelper
     */
    private fun addStatusLog(message: String) {
        if (message.isEmpty()) return
        viewModelScope.launch {
            _debugLogList.value = _debugLogList.value.appendDebugStatusLine(message)
        }
    }

    /**
     * 与 join 请求体 `remoteRtcUid` / `llm.params.lables.userName` 一致；
     * [ROBOT_FACE_SPEAKER_BIND]、[ROBOT_FACE_INFO_UP] 顶层 `clientId` 及 facedet [FaceDetectorConfig.deviceId] 均用此值。
     */
    private fun rtmReportClientId(): String = userId.toString()

    /**
     * 与 Android 对话页一致：已连接且**未**推 RTC 自定义视频时，启动 facedet → RTM `ROBOT_FACE_INFO_UP`。
     * 推自定义视频时会与 CameraX 抢前置相机，须停止上行（在 [setExternalVideoPublishingEnabled] 内处理）。
     */
    fun refreshRobotFaceRtmUplink(activity: AppCompatActivity) {
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            FaceRtmStreamPublisher.stopAll()
            return
        }
        if (externalVideoPublish.isCustomVideoPublishing) {
            FaceRtmStreamPublisher.stopAll()
            return
        }
        val rc = rtmClient ?: return
        if (connection.channelName.isEmpty()) return
        FaceRtmStreamPublisher.start(
            activity = activity,
            rtmClient = rc,
            clientId = rtmReportClientId(),
            recordId = connection.channelName,
        )
    }

    /** 挂断：停止 Agent、取消 RTM 订阅、离开 RTC 并清理会话状态。 */
    fun hangup() {
        viewModelScope.launch {
            try {
                stopFaceUplinkAndExternalAudio()
                conversationalAIAPI?.unsubscribeMessage(connection.channelName) { errorInfo ->
                    if (errorInfo != null) {
                        Log.e(TAG, "Unsubscribe message error: ${errorInfo}")
                    }
                }

                // Stop agent if it was started
                if (agentSession.agentId != null) {
                    val stoppedAgentId = agentSession.agentId!!
                    val stopResult = ConversationAgentRestCoordinator.stopRemoteAgentIfStarted(
                        agentId = stoppedAgentId,
                        authToken = agentSession.authToken,
                    )
                    stopResult.fold(
                        onSuccess = {
                            Log.d(TAG, "Agent stopped successfully, agentId=$stoppedAgentId")
                            addStatusLog("Agent stopped successfully, agentId=$stoppedAgentId")
                        },
                        onFailure = { exception ->
                            Log.e(TAG, "Failed to stop agent: ${exception.message}", exception)
                            addStatusLog("Agent stop failed, agentId=$stoppedAgentId: ${exception.message}")
                        }
                    )
                }

                leaveRtcChannel()
                connection.markRtcLeft()
                externalVideoPublish.resetVideoPipelineOnLeaveOrError()
                agentSession.clearAgentRestFields()
                resetConversationUiAfterHangup()
                robotFaceSpeakerBind.clearDedupeState()
                Log.d(TAG, "Hangup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during hangup: ${e.message}", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        robotFaceSpeakerBind.clearDedupeState()
        stopFaceUplinkAndExternalAudio()
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
        audioInputManager?.release()
        audioInputManager = null
        externalVideoCaptureManager?.release()
        externalVideoCaptureManager = null
        rtcEngine = null
        rtmClient = null
    }

    private fun startAudioInputInternal(): Boolean {
        val audioManager = audioInputManager ?: return false
        if (!audioManager.start()) {
            _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
            return false
        }
        rtcEngine?.adjustRecordingSignalVolume(if (_uiState.value.isMuted) 0 else 100)
        _uiState.value = _uiState.value.copy(isAudioInputEnabled = true)
        return true
    }

    private fun stopAudioInputInternal() {
        audioInputManager?.stop()
        _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
    }

    private fun stopExternalAudioCapture() {
        audioInputManager?.stopAndUnpublish()
        _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
    }

    /** 停止人脸 RTM 上行与自定义音频采集（挂断与 ViewModel 销毁共用）。 */
    private fun stopFaceUplinkAndExternalAudio() {
        FaceRtmStreamPublisher.stopAll()
        stopExternalAudioCapture()
    }

    private fun resetConversationUiAfterHangup() {
        _uiState.value = _uiState.value.copy(
            isAudioInputEnabled = false,
            connectionState = ConnectionState.Idle
        )
        _transcriptList.value = emptyList()
        _agentState.value = AgentState.IDLE
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
    fun setExternalVideoPublishingEnabled(enabled: Boolean): Boolean =
        externalVideoPublish.setPublishingEnabled(enabled)

    /**
     * 推送一帧业务侧已经组装好的外部视频帧。
     *
     * 调用前需要先确保 RTC 已加入频道，并通过 [setExternalVideoPublishingEnabled]
     * 打开自定义视频发布；否则该帧会被直接丢弃。
     *
     * @param frame 业务侧构造完成的 Agora 视频帧对象
     * @return SDK 推帧结果，`0` 表示成功，负数表示失败
     */
    fun pushExternalVideoFrame(frame: AgoraVideoFrame): Int =
        externalVideoPublish.pushExternalVideoFrame(frame)

    /**
     * 推送一帧业务侧原始 PCM 数据到 RTC 自定义音频轨。
     *
     * 适用于业务侧接入外部麦克风、采集卡或其他音频设备时，直接把已采集
     * 的 16k/单声道/16bit PCM 数据送入 RTC。
     *
     * @param data 原始 PCM 字节数组
     * @param timestampMs 音频帧时间戳，单位毫秒
     * @return SDK 推帧结果，`0` 表示成功，负数表示失败
     */
    fun pushExternalPcmAudioFrame(
        data: ByteArray,
        timestampMs: Long = System.currentTimeMillis()
    ): Int {
        return audioInputManager?.pushExternalPcmData(data, timestampMs) ?: -1
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
    ): Int = externalVideoPublish.pushExternalNv21Frame(
        data = data,
        width = width,
        height = height,
        rotation = rotation,
        timestampMs = timestampMs
    )
}
