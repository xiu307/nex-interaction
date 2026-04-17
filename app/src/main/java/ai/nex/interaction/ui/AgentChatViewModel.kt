package ai.nex.interaction.ui

import android.Manifest
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.nex.interaction.AgentApp
import ai.nex.interaction.KeyCenter
import ai.conv.ConvoManager
import ai.conv.ConvoManagerConfig
import ai.nex.interaction.video.ConversationExternalVideoPublishController
import ai.conv.internal.convoai.AgentState
import ai.conv.internal.convoai.ModuleError
import ai.conv.internal.convoai.StateChangeEvent
import ai.conv.internal.convoai.Transcript
import ai.conv.internal.convoai.TranscriptType
import io.agora.rtc2.Constants
import io.agora.rtc2.Constants.ERR_OK
import io.agora.rtc2.IRtcEngineEventHandler.RtcStats
import io.agora.rtc2.video.AgoraVideoFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import ai.conv.internal.convoai.IConversationalAIAPIEventHandler
import ai.conv.internal.convoai.InterruptEvent
import ai.conv.internal.convoai.MessageError
import ai.conv.internal.convoai.MessageReceipt
import ai.conv.internal.convoai.Metric
import ai.conv.internal.convoai.VoiceprintStateChangeEvent
import ai.nex.interaction.biometric.FaceRtmStreamPublisher
import ai.nex.interaction.biometric.RobotFaceSpeakerBindCoordinator
import ai.nex.interaction.ui.widget.DebugOverlayView
import androidx.camera.view.PreviewView
import ai.conv.internal.rtc.ConversationRtcEngineEventHandler
import ai.conv.internal.rtc.ConversationRtcEventSink
import ai.conv.internal.rtc.joinConversationChannelWithOptions
import ai.conv.internal.rtc.joinConversationChannelExWithOptions
import ai.conv.internal.rtc.leaveConversationChannelEx
import ai.conv.internal.rtm.ConversationRtmEventSink
import ai.conv.internal.rtm.ConversationRtmLogin
import ai.nex.interaction.api.AgentStarter
import ai.nex.interaction.api.TokenGenerator
import ai.nex.interaction.biometric.BiometricSalRegistry
import ai.nex.interaction.session.AgentSessionState
import ai.nex.interaction.session.ConversationAgentRestCoordinator
import ai.nex.interaction.session.ConversationUserTokenLoader
import ai.nex.interaction.session.ConnectionSessionState
import ai.nex.interaction.session.ConversationSessionIdentity
import ai.nex.interaction.tools.appendDebugStatusLine
import ai.nex.interaction.transcript.upsertTranscript
import org.json.JSONObject
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator

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

    /** 最近一次经 RTM 发往服务端的人脸/人体上行 JSON（`ROBOT_FACE_INFO_UP`），供悬浮窗查看。 */
    private val _lastFaceRtmUplinkPayload = MutableStateFlow("")
    val lastFaceRtmUplinkPayload: StateFlow<String> = _lastFaceRtmUplinkPayload.asStateFlow()

    fun onFaceRtmUplinkPayload(json: String) {
        _lastFaceRtmUplinkPayload.value = json
    }

    fun clearFaceRtmUplinkPayloadPreview() {
        _lastFaceRtmUplinkPayload.value = ""
    }

    private val connection = ConnectionSessionState()
    private val agentSession = AgentSessionState()
    private var currentAgentUid: Int = ConversationSessionIdentity.agentUid
    private val joinedExUids = linkedSetOf<Int>()
    private var sessionUserIdsSnapshot: List<Int> = listOf(userId)
    private var joinedRemoteRtcUids: List<String> = listOf(userId.toString())
    private var joinInFlight: Boolean = false

    private lateinit var manager: ConvoManager
    private val managerOrNull: ConvoManager?
        get() = if (::manager.isInitialized) manager else null

    private val externalVideoPublish by lazy {
        ConversationExternalVideoPublishController(
            rtcEngine = { managerOrNull?.rtcEngine },
            audioInputManager = { managerOrNull?.audioInputManager },
            externalVideoCapture = { managerOrNull?.videoInputManager },
            connection = { connection },
            onStatusLog = { addStatusLog(it) },
        )
    }

    private val robotFaceSpeakerBind = RobotFaceSpeakerBindCoordinator()

    private val rtcEventSink = object : ConversationRtcEventSink {
        override suspend fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            connection.rtcJoined = true
            managerOrNull?.audioInputManager?.setPublished(true)
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
            if (uid == currentAgentUid) {
                Log.d(TAG, "Agent joined the channel, uid: $uid")
            } else {
                Log.d(TAG, "User joined the channel, uid: $uid")
            }
        }

        override suspend fun onUserOffline(uid: Int, reason: Int) {
            addStatusLog("Rtc onUserOffline, uid:$uid")
            if (uid == currentAgentUid) {
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

    // RTM event listener
    private val rtmEventSink = object : ConversationRtmEventSink {
        override fun onRtmLinkConnected() {
            Log.d(TAG, "Rtm connected successfully")
            managerOrNull?.rtmLoginState?.isRtmLogin = true
            addStatusLog("Rtm connected successfully")
        }

        override fun onRtmLinkFailed() {
            Log.d(TAG, "RTM connection failed, need to re-login")
            managerOrNull?.rtmLoginState?.isRtmLogin = false
            managerOrNull?.rtmLoginState?.isLoggingIn = false
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    connectionState = ConnectionState.Error
                )
                addStatusLog("Rtm connected failed")
                connection.unifiedToken.clear()
            }
        }
    }

    private val conversationalAIAPIEventHandler = object : IConversationalAIAPIEventHandler {
        override fun onAgentStateChanged(agentUserId: String, event: StateChangeEvent) {
            _agentState.value = event.state
        }

        override fun onAgentInterrupted(agentUserId: String, event: InterruptEvent) {}

        override fun onAgentMetrics(agentUserId: String, metric: Metric) {}

        override fun onAgentError(agentUserId: String, error: ModuleError) {
            addStatusLog("Agent error: type=${error.type.value}, code=${error.code}, msg=${error.message}")
        }

        override fun onMessageError(agentUserId: String, error: MessageError) {}

        override fun onMessageReceiptUpdated(agentUserId: String, receipt: MessageReceipt) {}

        override fun onAgentVoiceprintStateChanged(agentUserId: String, event: VoiceprintStateChangeEvent) {}

        override fun onTranscriptUpdated(agentUserId: String, transcript: Transcript) {
            addTranscript(transcript)
            if (transcript.type == TranscriptType.USER) {
                val rtm = managerOrNull?.rtmClient ?: return
                robotFaceSpeakerBind.maybeSendOnUserTranscript(
                    transcript = transcript,
                    connectionConnected = _uiState.value.connectionState == ConnectionState.Connected,
                    rtmClient = rtm,
                    clientId = rtmReportClientId(),
                )
            }
        }

        override fun onDebugLog(log: String) {
            Log.d("conversationalAIAPI", log)
        }
    }

    init {
        Log.d(TAG, "Initializing ConvoManager...")
        try {
            manager = ConvoManager(
                context = AgentApp.instance(),
                appId = KeyCenter.APP_ID,
                userId = userId.toString(),
                scope = viewModelScope,
                config = ConvoManagerConfig(
                    enableConvoAiLog = true,
                    onAudioInputInterrupted = {
                        _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
                        addStatusLog("Audio input stopped unexpectedly")
                    }
                ),
                rtcEventSink = rtcEventSink,
                rtmEventSink = rtmEventSink,
                convoAiEventHandler = conversationalAIAPIEventHandler,
                logTag = TAG,
                channelNameProvider = { connection.channelName }
            )
            Log.d(TAG, "ConvoManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ConvoManager: ${e.message}", e)
            _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Error)
        }
    }


    private fun loginRtm(rtmToken: String, completion: (Exception?) -> Unit) {
        val m = managerOrNull ?: run {
            completion(IllegalStateException("ConvoManager not initialized"))
            return
        }
        ConversationRtmLogin.loginAfterLogout(
            client = m.rtmClient,
            rtmToken = rtmToken,
            state = m.rtmLoginState,
            logTag = TAG,
            completion = completion,
            statusLog = { addStatusLog(it) },
        )
    }

    private fun logoutRtm() {
        val m = managerOrNull ?: return
        ConversationRtmLogin.logout(m.rtmClient, m.rtmLoginState, TAG)
    }

    /**
     * Join RTC channel
     */
    private fun joinRtcChannel(rtcToken: String, channelName: String, uid: Int): Boolean {
        Log.d(TAG, "joinChannel channelName: $channelName, localUid: $uid")
        val m = managerOrNull ?: run {
            addStatusLog("ConvoManager is not initialized")
            _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Error)
            return false
        }

        val customAudioTrackId = m.audioInputManager.ensureCustomAudioTrack()
        if (customAudioTrackId < 0) {
            addStatusLog("Create custom audio track failed ret: $customAudioTrackId")
            _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Error)
            return false
        }

        externalVideoPublish.prepareForNewRtcJoin()
        val ret = joinConversationChannelWithOptions(
            rtcEngine = m.rtcEngine,
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

    private fun joinRtcChannelEx(rtcToken: String, channelName: String, uid: Int): Boolean {
        Log.d(TAG, "joinChannelEx channelName: $channelName, localUid: $uid")
        val m = managerOrNull ?: run {
            addStatusLog("ConvoManager is not initialized")
            return false
        }
        val customAudioTrackId = m.audioInputManager.ensureCustomAudioTrackEx(uid)
        if (customAudioTrackId < 0) {
            addStatusLog("Create custom audio track failed ret: $customAudioTrackId")
            return false
        }
        // 当前设备会以多个本地 uid 进同一频道；主连接看到 ex uid 会被当作“远端用户”。
        // 这里提前屏蔽这些 ex uid 的订阅，避免把本机自己发布的音频再次拉回本机造成回音/自听。
        m.rtcEngine.muteRemoteAudioStream(uid, true)
        m.rtcEngine.muteRemoteVideoStream(uid, true)
        val ret = joinConversationChannelExWithOptions(
            rtcEngine = m.rtcEngine,
            rtcToken = rtcToken,
            channelName = channelName,
            uid = uid,
            customAudioTrackId = customAudioTrackId,
        )
        Log.d(TAG, "Joining RTC channelEx: $channelName, uid: $uid")
        if (ret == ERR_OK) {
            Log.d(TAG, "Join RTC room ex success")
            return true
        } else {
            Log.e(TAG, "Join RTC room ex failed, ret: $ret")
            return false
        }
    }

    /**
     * Leave RTC channel
     */
    private fun leaveRtcChannel() {
        Log.d(TAG, "leaveChannel")
        val m = managerOrNull
        val channel = connection.channelName
        if (m != null && channel.isNotEmpty()) {
            for (uid in joinedExUids) {
                leaveConversationChannelEx(
                    rtcEngine = m.rtcEngine,
                    channelName = channel,
                    uid = uid,
                )
            }
        }
        joinedExUids.clear()
        val fallbackUid = sessionUserIdsSnapshot.firstOrNull() ?: userId
        sessionUserIdsSnapshot = listOf(fallbackUid)
        joinedRemoteRtcUids = listOf(fallbackUid.toString())
        managerOrNull?.rtcEngine?.leaveChannel()
    }

    /**
     * Mute local audio
     */
    private fun muteLocalAudio(mute: Boolean) {
        Log.d(TAG, "muteLocalAudio $mute")
        managerOrNull?.rtcEngine?.adjustRecordingSignalVolume(if (mute) 0 else 100)
    }

    /**
     * Check if both RTC and RTM are connected, then start agent
     */
    private fun checkJoinAndLoginComplete() {
        if (connection.rtcAndRtmReady) {
            joinInFlight = false
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
            val sessionUserIds = sessionUserIdsSnapshot.ifEmpty { listOf(userId) }
            currentAgentUid = ConversationSessionIdentity.generateAgentUid(sessionUserIds.toSet())
            val startResult = ConversationAgentRestCoordinator.startRemoteAgent(
                channelName = connection.channelName,
                agentRtcUid = currentAgentUid.toString(),
                remoteRtcUids = joinedRemoteRtcUids
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
    private suspend fun generateUserToken(userId: String): String? {
        return ConversationUserTokenLoader.fetchAndStoreUnifiedUserToken(
            connection = connection,
            userId = userId,
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
        val m = managerOrNull ?: return
        connection.rtmLoggedIn = true
        m.conversationalAIAPI.subscribeMessage(channelName) { errorInfo ->
            if (errorInfo != null) {
                Log.e(TAG, "Subscribe message error: ${errorInfo}")
            }
        }
        checkJoinAndLoginComplete()
    }

    /** RTM 登录失败：停止采集、离开 RTC 并回到 Error，避免半连接状态。 */
    private fun rollbackAfterRtmLoginFailed(exception: Exception) {
        joinInFlight = false
        stopExternalAudioCapture()
        leaveRtcChannel()
        connection.markRtcLeft()
        _uiState.value = _uiState.value.copy(
            connectionState = ConnectionState.Error
        )
        Log.e(TAG, "RTM login failed: ${exception.message}", exception)
    }

    /** RTC join 阶段（含 ex uid）失败后统一回滚，避免半连接状态。 */
    private fun rollbackAfterRtcJoinPhaseFailed(message: String) {
        joinInFlight = false
        stopExternalAudioCapture()
        leaveRtcChannel()
        connection.markRtcLeft()
        _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Error)
        addStatusLog(message)
        Log.e(TAG, message)
    }

    fun getRegisterSALNum(): Int {
        var num = 1
        val rawBiometric = KeyCenter.SAL_BIOMETRIC_SAMPLE_URLS
        val biometricJson = try {
            if (rawBiometric.isNotEmpty()) JSONObject(rawBiometric) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
        val registryComplete = BiometricSalRegistry.getCompleteSalFaceIdToPcmUrls()
        Log.i(TAG, "SAL: getCompleteSalFaceIdToPcmUrls size=${registryComplete.size} keys=${registryComplete.keys}")

        if (KeyCenter.SAL_ENABLE_PERSONALIZED) {
            KeyCenter.SAL_PERSONALIZED_PCM_URL.takeIf { it.isNotEmpty() }?.let { num++ }
        }
        val keyIt = biometricJson.keys()
        while (keyIt.hasNext()) {
            val key = keyIt.next()
            val v = biometricJson.optString(key, "")
            if (key.isNotEmpty() && v.isNotEmpty()) num++
        }
        // 本地注册页完成的 faceId→PCM（PCM 需 http(s)，face URL 仅需非空）
        for ((faceId, pcmUrl) in registryComplete) {
            if (faceId.isNotEmpty() && pcmUrl.isNotEmpty()) {
                num++
            }
        }
        return num
    }

    /**
     * Join RTC channel and login RTM
     * @param channelName Channel name to join
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun joinChannelAndLogin(channelName: String) {
        if (joinInFlight || _uiState.value.connectionState != ConnectionState.Idle) {
            addStatusLog("Join ignored: current state=${_uiState.value.connectionState}")
            return
        }
        viewModelScope.launch {
            joinInFlight = true
            connection.beginJoinAttempt(channelName)

            _uiState.value = _uiState.value.copy(
                connectionState = ConnectionState.Connecting
            )

            val sessionUserIds = buildRtcSessionUserIdList()
            sessionUserIdsSnapshot = sessionUserIds
            val primaryRtcUid = sessionUserIds.first()

            // RTC 主连接按 faceId 映射后的 userId；RTM 登录仍用固定 userId（ConvoManager 登录标识）。
            val primaryRtcToken =
                connection.unifiedToken[primaryRtcUid.toString()] ?: generateUserToken(primaryRtcUid.toString())
            if (primaryRtcToken == null) {
                joinInFlight = false
                return@launch
            }
            val rtmToken = connection.unifiedToken[userId.toString()] ?: generateUserToken(userId.toString())
            if (rtmToken == null) {
                joinInFlight = false
                return@launch
            }

            // Join RTC channel with primary mapped uid
            if (!joinRtcChannel(primaryRtcToken, channelName, primaryRtcUid)) {
                joinInFlight = false
                return@launch
            }
            currentAgentUid = ConversationSessionIdentity.generateAgentUid(sessionUserIds.toSet())
            joinedExUids.clear()
            val successfulRemoteUids = mutableListOf(primaryRtcUid.toString())
            for (exUid in sessionUserIds.drop(1)) {
                val exToken = connection.unifiedToken[exUid.toString()] ?: generateUserToken(exUid.toString())
                if (exToken == null) {
                    rollbackAfterRtcJoinPhaseFailed("Generate ex uid token failed, uid=$exUid")
                    return@launch
                }
                if (!joinRtcChannelEx(exToken, channelName, exUid)) {
                    rollbackAfterRtcJoinPhaseFailed("Join RTC ex failed, uid=$exUid")
                    return@launch
                }
                joinedExUids.add(exUid)
                successfulRemoteUids.add(exUid.toString())
            }
            joinedRemoteRtcUids = successfulRemoteUids

            // Login RTM with the same unified token
            loginRtm(rtmToken) { exception ->
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
     * RTC / remote_rtc_uids / labels.userName 统一 userId 体系：
     * - 必含当前登录 userId；
     * - 追加本地注册得到的 userId（可转为 Int 且不重复）。
     */
    private fun buildRtcSessionUserIdList(): List<Int> {
        val mapped = BiometricSalRegistry.getRegisteredUserIds().mapNotNull { it.toIntOrNull() }.distinct()
        val out = linkedSetOf<Int>()
        if (mapped.isNotEmpty()) {
            out.addAll(mapped)
        } else {
            out.add(userId)
        }
        return out.toList()
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
    fun refreshRobotFaceRtmUplink(
        activity: AppCompatActivity,
        rtmFacePreviewView: PreviewView? = null,
        rtmFaceDebugOverlay: DebugOverlayView? = null,
    ) {
        if (_uiState.value.connectionState != ConnectionState.Connected) {
            FaceRtmStreamPublisher.stopAll()
            return
        }
        if (externalVideoPublish.isCustomVideoPublishing) {
            FaceRtmStreamPublisher.stopAll()
            return
        }
        val m = managerOrNull ?: return
        if (connection.channelName.isEmpty()) return
        FaceRtmStreamPublisher.start(
            activity = activity,
            rtmClient = m.rtmClient,
            clientId = rtmReportClientId(),
            recordId = connection.channelName,
            previewView = rtmFacePreviewView,
            debugOverlay = rtmFaceDebugOverlay,
        )
    }

    /** 挂断：停止 Agent、取消 RTM 订阅、离开 RTC 并清理会话状态。 */
    fun hangup() {
        viewModelScope.launch {
            try {
                joinInFlight = false
                val m = managerOrNull ?: return@launch
                stopFaceUplinkAndExternalAudio()
                m.conversationalAIAPI.unsubscribeMessage(connection.channelName) { errorInfo ->
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
        joinInFlight = false
        robotFaceSpeakerBind.clearDedupeState()
        stopFaceUplinkAndExternalAudio()
        leaveRtcChannel()
        logoutRtm()
        managerOrNull?.destroy()
    }

    private fun startAudioInputInternal(): Boolean {
        val m = managerOrNull ?: return false
        if (!m.audioInputManager.start()) {
            _uiState.value = _uiState.value.copy(isAudioInputEnabled = false)
            return false
        }
        m.rtcEngine.adjustRecordingSignalVolume(if (_uiState.value.isMuted) 0 else 100)
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

    /** 停止人脸 RTM 上行与自定义音频采集（挂断与 ViewModel 销毁共用）。 */
    private fun stopFaceUplinkAndExternalAudio() {
        FaceRtmStreamPublisher.stopAll()
        clearFaceRtmUplinkPayloadPreview()
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
        return managerOrNull?.audioInputManager?.pushExternalPcmData(data, timestampMs) ?: -1
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
