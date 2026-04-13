package ai.nex.interaction.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ai.nex.interaction.R
import ai.nex.interaction.biometric.FaceRtmStreamPublisher
import ai.nex.interaction.tools.PermissionHelp
import ai.nex.interaction.ui.widget.DebugOverlayView
import ai.nex.interaction.ui.common.BaseActivity
import ai.nex.interaction.video.CameraVideoInputManager
import ai.conv.internal.convoai.AgentState
import ai.conv.internal.convoai.Transcript
import ai.conv.internal.convoai.TranscriptType
import ai.nex.interaction.databinding.ActivityAgentChatBinding
import ai.nex.interaction.databinding.ItemTranscriptUserBinding
import ai.nex.interaction.databinding.ItemTranscriptAgentBinding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Activity for agent chat interface
 * Layout: log, agent status, transcript, start/control buttons
 */
class AgentChatActivity : BaseActivity<ActivityAgentChatBinding>() {

    private lateinit var viewModel: AgentChatViewModel
    private lateinit var mPermissionHelp: PermissionHelp
    private lateinit var cameraVideoInputManager: CameraVideoInputManager
    private val transcriptAdapter: TranscriptAdapter = TranscriptAdapter()
    private var videoInputPreviewView: PreviewView? = null
    private var isVideoInputStarted = false

    /** RTM 上行悬浮窗：默认仅图标；点图标展开/收起详情 */
    private var isRtmPayloadFloatExpanded = false

    /** 人脸实时预览悬浮窗：与 RTM 同款，默认仅相机图标 */
    private var isFacePreviewFloatExpanded = false

    // Track whether to automatically scroll to bottom
    private var autoScrollToBottom = true
    private var isScrollBottom = false

    override fun getViewBinding(): ActivityAgentChatBinding {
        return ActivityAgentChatBinding.inflate(layoutInflater)
    }

    override fun initData() {
        super.initData()
        viewModel = ViewModelProvider(this)[AgentChatViewModel::class.java]
        mPermissionHelp = PermissionHelp(this)
        cameraVideoInputManager = CameraVideoInputManager(this) { frame ->
            viewModel.pushExternalVideoFrame(frame)
        }

        // Observe UI state changes
        observeUiState()

        // Observe transcript list changes
        observeTranscriptList()

        // Observe debug log changes
        observeDebugLogs()

        FaceRtmStreamPublisher.debugPayloadListener = { json ->
            viewModel.onFaceRtmUplinkPayload(json)
        }

        observeFaceRtmPayloadFloat()

        lifecycleScope.launch {
            viewModel.uiState
                .map { it.connectionState }
                .distinctUntilChanged()
                .collect { conn ->
                    val connected = conn == AgentChatViewModel.ConnectionState.Connected
                    mBinding?.apply {
                        if (!connected) {
                            isRtmPayloadFloatExpanded = false
                            isFacePreviewFloatExpanded = false
                        }
                        cardRtmPayloadFloat.isVisible = connected
                        if (connected) {
                            applyRtmPayloadFloatExpandedState()
                            applyFacePreviewFloatExpandedState()
                            viewModel.refreshRobotFaceRtmUplink(
                                this@AgentChatActivity,
                                mBinding?.faceRtmPreview,
                                mBinding?.faceRtmDebugOverlay,
                            )
                            updateFacePreviewFloatVisibility()
                        } else {
                            FaceRtmStreamPublisher.stopAll()
                            viewModel.clearFaceRtmUplinkPayloadPreview()
                            updateFacePreviewFloatVisibility()
                        }
                    }
                }
        }
    }

    /** 左侧悬浮卡片：随每次上行 JSON 刷新内容；展开时才滚动到底。 */
    private fun observeFaceRtmPayloadFloat() {
        lifecycleScope.launch {
            viewModel.lastFaceRtmUplinkPayload.collect { raw ->
                applyFaceRtmPayloadFloatText(raw)
            }
        }
    }

    private fun toggleRtmPayloadFloatExpanded() {
        isRtmPayloadFloatExpanded = !isRtmPayloadFloatExpanded
        applyRtmPayloadFloatExpandedState()
    }

    private fun applyRtmPayloadFloatExpandedState() {
        mBinding?.apply {
            val expanded = isRtmPayloadFloatExpanded
            tvRtmPayloadFloatTitle.isVisible = expanded
            btnCopyRtmPayloadFloat.isVisible = expanded
            scrollRtmPayloadFloat.isVisible = expanded
            val panelW = if (expanded) {
                resources.getDimensionPixelSize(R.dimen.rtm_float_expanded_width)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            llRtmPayloadFloatRoot.layoutParams = llRtmPayloadFloatRoot.layoutParams.apply {
                width = panelW
            }
            llRtmPayloadFloatHeader.gravity = if (expanded) {
                Gravity.CENTER_VERTICAL or Gravity.START
            } else {
                Gravity.CENTER
            }
            if (expanded) {
                scrollRtmPayloadFloat.post {
                    scrollRtmPayloadFloat.scrollTo(0, 0)
                }
            }
        }
    }

    private fun toggleFacePreviewFloatExpanded() {
        isFacePreviewFloatExpanded = !isFacePreviewFloatExpanded
        applyFacePreviewFloatExpandedState()
    }

    private fun applyFacePreviewFloatExpandedState() {
        mBinding?.apply {
            val expanded = isFacePreviewFloatExpanded
            tvFacePreviewFloatTitle.isVisible = expanded
            flFacePreviewFloatContent.isVisible = expanded
            val panelW = if (expanded) {
                resources.getDimensionPixelSize(R.dimen.rtm_float_expanded_width)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            llFacePreviewFloatRoot.layoutParams = llFacePreviewFloatRoot.layoutParams.apply {
                width = panelW
            }
            llFacePreviewFloatHeader.gravity = if (expanded) {
                Gravity.CENTER_VERTICAL or Gravity.START
            } else {
                Gravity.CENTER
            }
        }
    }

    private fun toggleVideoInput() {
        if (isVideoInputStarted) {
            stopVideoInput()
            return
        }
        if (viewModel.uiState.value.connectionState != AgentChatViewModel.ConnectionState.Connected) {
            Toast.makeText(
                this,
                "Connect to the agent first, then start video input.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!mPermissionHelp.hasCameraPerm()) {
            Toast.makeText(
                this,
                "Camera permission is requested before joining. Please rejoin after granting it.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        startVideoInput()
    }

    private fun startVideoInput() {
        val binding = mBinding ?: return
        if (!viewModel.setExternalVideoPublishingEnabled(true)) {
            Toast.makeText(
                this,
                "Failed to enable external video publishing.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (videoInputPreviewView == null) {
            val previewView = PreviewView(this)
            binding.flLocalPreview.removeAllViews()
            binding.flLocalPreview.addView(
                previewView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            videoInputPreviewView = previewView
        }
        binding.cardLocalPreview.visibility = View.VISIBLE
        cameraVideoInputManager.start(videoInputPreviewView ?: return)
        isVideoInputStarted = true
        updateVideoInputButton()
        updateFacePreviewFloatVisibility()
    }

    private fun stopVideoInput() {
        viewModel.setExternalVideoPublishingEnabled(false)
        cameraVideoInputManager.stop()
        mBinding?.apply {
            flLocalPreview.removeAllViews()
            cardLocalPreview.visibility = View.GONE
        }
        videoInputPreviewView = null
        isVideoInputStarted = false
        updateVideoInputButton()
        viewModel.refreshRobotFaceRtmUplink(
            this,
            mBinding?.faceRtmPreview,
            mBinding?.faceRtmDebugOverlay,
        )
        updateFacePreviewFloatVisibility()
    }

    private fun updateVideoInputButton() {
        mBinding?.btnInputVideo?.apply {
            text = if (isVideoInputStarted) {
                context.getString(R.string.video_input_stop)
            } else {
                context.getString(R.string.video_input_start)
            }
            setBackgroundResource(
                if (isVideoInputStarted) {
                    R.drawable.selector_button_hangup
                } else {
                    R.drawable.selector_gradient_button
                }
            )
            setTextColor(ContextCompat.getColor(this@AgentChatActivity, R.color.white))
        }
    }

    private fun updateAudioInputButton(enabled: Boolean) {
        mBinding?.btnInputAudio?.apply {
            text = if (enabled) {
                context.getString(R.string.audio_input_stop)
            } else {
                context.getString(R.string.audio_input_start)
            }
            setBackgroundResource(
                if (enabled) {
                    R.drawable.selector_button_hangup
                } else {
                    R.drawable.selector_gradient_button
                }
            )
            setTextColor(ContextCompat.getColor(this@AgentChatActivity, R.color.white))
        }
    }

    @SuppressLint("MissingPermission")
    override fun initView() {
        mBinding?.apply {
            setOnApplyWindowInsetsListener(root)
            faceRtmPreview.scaleType = PreviewView.ScaleType.FILL_CENTER

            // Setup RecyclerView for transcript list
            setupRecyclerView()

            // Start button click listener
            btnStart.setOnClickListener {
                // Generate random channel name each time joining channel
                val channelName = AgentChatViewModel.generateRandomChannelName()

                // Check camera and microphone permissions before joining channel
                checkMediaPermissions { granted ->
                    if (granted) {
                        viewModel.joinChannelAndLogin(channelName)
                    } else {
                        Toast.makeText(
                            this@AgentChatActivity,
                            "Camera and microphone permissions are required to join channel",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            // Mute button click listener
            btnMute.setOnClickListener {
                viewModel.toggleMute()
            }

            btnInputVideo.setOnClickListener {
                toggleVideoInput()
            }

            btnInputAudio.setOnClickListener {
                viewModel.toggleAudioInput()
            }

            // Stop button click listener
            btnStop.setOnClickListener {
                viewModel.hangup()
            }

            tvBiometricRegister.setOnClickListener {
                BiometricRegisterActivity.start(this@AgentChatActivity)
            }

            btnCopyLog.setOnClickListener { copyDebugLogToClipboard() }
            tvLog.setOnLongClickListener {
                copyDebugLogToClipboard()
                true
            }

            btnCopyRtmPayloadFloat.setOnClickListener {
                val raw = viewModel.lastFaceRtmUplinkPayload.value
                if (raw.isEmpty()) {
                    Toast.makeText(this@AgentChatActivity, getString(R.string.agent_chat_rtm_payload_empty), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val display = formatJsonForDisplay(raw)
                copyPlainTextToClipboard(display, "rtm_face_uplink_json")
                Toast.makeText(this@AgentChatActivity, getString(R.string.agent_chat_log_copied), Toast.LENGTH_SHORT).show()
            }
            ivRtmPayloadFloatIcon.setOnClickListener {
                toggleRtmPayloadFloatExpanded()
            }
            ivFacePreviewFloatIcon.setOnClickListener {
                toggleFacePreviewFloatExpanded()
            }
            // initData 先于 initView：首帧 collect 时 mBinding 可能为 null；配置变更后须补一次悬浮卡片可见性与文案
            cardRtmPayloadFloat.isVisible =
                viewModel.uiState.value.connectionState == AgentChatViewModel.ConnectionState.Connected
            isRtmPayloadFloatExpanded = false
            isFacePreviewFloatExpanded = false
            applyRtmPayloadFloatExpandedState()
            applyFacePreviewFloatExpandedState()
            applyFaceRtmPayloadFloatText(viewModel.lastFaceRtmUplinkPayload.value)
            updateFacePreviewFloatVisibility()
        }
    }

    override fun onResume() {
        super.onResume()
        applyFaceRtmPipelineOverlayAlign()
    }

    /** 与 face-detc-java MainActivity：窄屏竖屏前置预览与 DebugOverlay Y 对齐。 */
    private fun applyFaceRtmPipelineOverlayAlign() {
        val overlay = mBinding?.faceRtmDebugOverlay ?: return
        val sw = resources.configuration.smallestScreenWidthDp
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        overlay.setInvertPreviewY(sw < 600 && portrait)
    }

    /** 人脸上行管线可用时展示（已连接且未占用自定义视频）；隐藏时收起展开态。 */
    private fun updateFacePreviewFloatVisibility() {
        val b = mBinding ?: return
        val connected = viewModel.uiState.value.connectionState == AgentChatViewModel.ConnectionState.Connected
        val show = connected && !isVideoInputStarted
        b.cardFacePreviewFloat.isVisible = show
        if (!show) {
            isFacePreviewFloatExpanded = false
            applyFacePreviewFloatExpandedState()
        }
    }

    private fun applyFaceRtmPayloadFloatText(raw: String) {
        mBinding?.apply {
            tvRtmPayloadFloat.text = if (raw.isEmpty()) {
                getString(R.string.agent_chat_rtm_payload_empty)
            } else {
                formatJsonForDisplay(raw)
            }
            if (isRtmPayloadFloatExpanded) {
                scrollRtmPayloadFloat.post {
                    scrollRtmPayloadFloat.scrollTo(0, 0)
                }
            }
        }
    }

    private fun formatJsonForDisplay(raw: String): String {
        return try {
            JSONObject(raw).toString(2)
        } catch (_: Exception) {
            raw
        }
    }

    private fun copyPlainTextToClipboard(label: String, clipLabel: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(clipLabel, label))
    }

    /** 复制上方调试日志（纯文本，含 agentId 等），便于粘贴到 IM / 工单。 */
    private fun copyDebugLogToClipboard() {
        val lines = viewModel.debugLogList.value
        if (lines.isEmpty()) {
            Toast.makeText(this, getString(R.string.agent_chat_log_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val text = lines.joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("agent_debug_log", text))
        Toast.makeText(this, getString(R.string.agent_chat_log_copied), Toast.LENGTH_SHORT).show()
    }

    private fun checkMediaPermissions(granted: (Boolean) -> Unit) {
        if (mPermissionHelp.hasCameraPerm() && mPermissionHelp.hasMicPerm()) {
            granted.invoke(true)
            return
        }

        mPermissionHelp.checkCameraAndMicPerms(
            granted = {
                granted.invoke(true)
            },
            unGranted = {
                handleMediaPermissionDenied(granted)
            }
        )
    }

    private fun handleMediaPermissionDenied(granted: (Boolean) -> Unit) {
        showPermissionDialog(
            "Permission Required",
            "Camera and microphone permissions are required before joining the channel and starting custom video capture.",
            launchSettings = {
                mPermissionHelp.launchAppSettingForCameraAndMic(
                    granted = { checkMediaPermissions(granted) },
                    unGranted = { granted(false) }
                )
            },
            onDeclined = {
                granted(false)
            }
        )
    }

    private fun showPermissionDialog(
        title: String,
        content: String,
        launchSettings: () -> Unit,
        onDeclined: () -> Unit
    ) {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return

        CommonDialog.Builder()
            .setTitle(title)
            .setContent(content)
            .setPositiveButton("Settings") {
                launchSettings.invoke()
            }
            .setNegativeButton("Exit") {
                onDeclined.invoke()
            }
            .setCancelable(false)
            .build()
            .show(supportFragmentManager, "permission_dialog")
    }

    /**
     * Setup RecyclerView for transcript list
     */
    private fun setupRecyclerView() {
        mBinding?.rvTranscript?.apply {
            layoutManager = LinearLayoutManager(this@AgentChatActivity).apply {
                reverseLayout = false
            }
            adapter = transcriptAdapter
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            // Check if at bottom when scrolling stops
                            isScrollBottom = !recyclerView.canScrollVertically(1)
                            if (isScrollBottom) {
                                autoScrollToBottom = true
                                isScrollBottom = true
                            }
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> {
                            // When user actively drags
                            autoScrollToBottom = false
                        }
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    // Show button when scrolling up a significant distance
                    if (dy < -50) {
                        if (recyclerView.canScrollVertically(1)) {
                            autoScrollToBottom = false
                        }
                    }
                }
            })
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                mBinding?.apply {
                    // Update button visibility based on connection state
                    val isConnected = state.connectionState == AgentChatViewModel.ConnectionState.Connected
                    val isConnecting = state.connectionState == AgentChatViewModel.ConnectionState.Connecting

                    // Show/hide buttons
                    llStart.visibility = if (isConnected) View.GONE else View.VISIBLE
                    llControls.visibility = if (isConnected) View.VISIBLE else View.GONE
                    btnInputAudio.isEnabled = isConnected
                    btnInputVideo.isEnabled = isConnected || isVideoInputStarted
                    if (!isConnected && isVideoInputStarted) {
                        stopVideoInput()
                    }
                    updateAudioInputButton(state.isAudioInputEnabled)

                    // Update button style based on connection state
                    val isError = state.connectionState == AgentChatViewModel.ConnectionState.Error
                    when {
                        isConnecting -> {
                            btnStart.text = "Connecting..."
                            btnStart.isEnabled = false
                            btnStart.setBackgroundResource(R.drawable.bg_start_button_disabled)
                            btnStart.setTextColor(
                                ContextCompat.getColor(
                                    this@AgentChatActivity,
                                    R.color.btn_disabled_text
                                )
                            )
                        }

                        isError -> {
                            btnStart.text = "Retry"
                            btnStart.isEnabled = true
                            btnStart.setBackgroundResource(R.drawable.bg_start_button_error)
                            btnStart.setTextColor(ContextCompat.getColor(this@AgentChatActivity, R.color.white))
                        }

                        else -> {
                            btnStart.text = "Start Agent"
                            btnStart.isEnabled = true
                            btnStart.setBackgroundResource(R.drawable.selector_gradient_button)
                            btnStart.setTextColor(ContextCompat.getColor(this@AgentChatActivity, R.color.white))
                        }
                    }

                    // Update mute button UI with semantic colors
                    if (state.isMuted) {
                        btnMute.setImageResource(R.drawable.ic_mic_off)
                        btnMute.setBackgroundResource(R.drawable.bg_button_mute_muted)
                        btnMute.setColorFilter(ContextCompat.getColor(this@AgentChatActivity, R.color.mic_muted_icon))
                    } else {
                        btnMute.setImageResource(R.drawable.ic_mic)
                        btnMute.setBackgroundResource(R.drawable.bg_button_mute_selector)
                        btnMute.setColorFilter(ContextCompat.getColor(this@AgentChatActivity, R.color.mic_normal_icon))
                    }
                }
            }
        }

        // Observe agent state with semantic colors
        lifecycleScope.launch {
            viewModel.agentState.collect { agentState ->
                mBinding?.apply {
                    val state = agentState ?: AgentState.IDLE
                    tvAgentStatus.text = state.value.replaceFirstChar { it.uppercase() }

                    // Map agent state to semantic color
                    val stateColorRes = when (state) {
                        AgentState.IDLE -> R.color.state_idle
                        AgentState.LISTENING -> R.color.state_listening
                        AgentState.THINKING -> R.color.state_thinking
                        AgentState.SPEAKING -> R.color.state_speaking
                        AgentState.SILENT -> R.color.state_silent
                        AgentState.UNKNOWN -> R.color.text_tertiary
                    }
                    val stateColor = ContextCompat.getColor(this@AgentChatActivity, stateColorRes)

                    // Update status text color
                    tvAgentStatus.setTextColor(stateColor)

                    // Update status dot color
                    val dotDrawable = viewStatusDot.background
                    if (dotDrawable is GradientDrawable) {
                        dotDrawable.setColor(stateColor)
                    }
                }
            }
        }
    }

    private fun observeTranscriptList() {
        lifecycleScope.launch {
            viewModel.transcriptList.collect { transcriptList ->
                // Update transcript list
                transcriptAdapter.submitList(transcriptList)
                if (autoScrollToBottom) {
                    scrollToBottom()
                }
            }
        }
    }

    private fun observeDebugLogs() {
        lifecycleScope.launch {
            viewModel.debugLogList.collect { logList ->
                mBinding?.apply {
                    if (logList.isEmpty()) {
                        tvLog.text = "log"
                        return@apply
                    }
                    // Build colored log text using semantic log-level colors
                    val spannable = SpannableStringBuilder()
                    logList.forEachIndexed { index, log ->
                        val start = spannable.length
                        spannable.append(log)
                        val end = spannable.length

                        // Determine log level color based on content keywords
                        val colorRes = when {
                            log.contains("failed", ignoreCase = true) ||
                                    log.contains("error", ignoreCase = true) -> R.color.error_red_light

                            log.contains("successfully", ignoreCase = true) ||
                                    log.contains("success", ignoreCase = true) -> R.color.success_green_light

                            log.contains("connecting", ignoreCase = true) ||
                                    log.contains("starting", ignoreCase = true) -> R.color.warning_amber_light

                            else -> R.color.text_secondary
                        }
                        spannable.setSpan(
                            ForegroundColorSpan(ContextCompat.getColor(this@AgentChatActivity, colorRes)),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        if (index < logList.size - 1) spannable.append("\n")
                    }
                    tvLog.text = spannable
                    // Auto scroll to bottom
                    tvLog.post {
                        val scrollView = tvLog.parent as? ScrollView
                        scrollView?.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    /**
     * Scroll RecyclerView to the bottom to show latest transcript
     */
    private fun scrollToBottom() {
        mBinding?.rvTranscript?.apply {
            val lastPosition = transcriptAdapter.itemCount - 1
            if (lastPosition < 0) return

            stopScroll()
            val layoutManager = layoutManager as? LinearLayoutManager ?: return

            // Use single post call to handle all scrolling logic
            post {
                layoutManager.scrollToPosition(lastPosition)

                // Handle extra-long messages that exceed viewport height
                val lastView = layoutManager.findViewByPosition(lastPosition)
                if (lastView != null && lastView.height > height) {
                    val offset = height - lastView.height
                    layoutManager.scrollToPositionWithOffset(lastPosition, offset)
                }

                isScrollBottom = true
            }
        }
    }

    override fun onDestroy() {
        FaceRtmStreamPublisher.debugPayloadListener = null
        stopVideoInput()
        cameraVideoInputManager.release()
        super.onDestroy()
    }
}

/**
 * Adapter for displaying transcript list with different view types for USER and AGENT
 */
class TranscriptAdapter : ListAdapter<Transcript, RecyclerView.ViewHolder>(TranscriptDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_AGENT = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).type) {
            TranscriptType.USER -> VIEW_TYPE_USER
            TranscriptType.AGENT -> VIEW_TYPE_AGENT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_USER -> {
                UserViewHolder(ItemTranscriptUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }

            VIEW_TYPE_AGENT -> {
                AgentViewHolder(ItemTranscriptAgentBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            }

            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val transcript = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(transcript)
            is AgentViewHolder -> holder.bind(transcript)
        }
    }

    /**
     * ViewHolder for USER transcript items (right-aligned with "Me" avatar)
     */
    class UserViewHolder(private val binding: ItemTranscriptUserBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(transcript: Transcript) {
            binding.tvTranscriptText.text = transcript.text.ifEmpty { "..." }
        }
    }

    /**
     * ViewHolder for AGENT transcript items (left-aligned with "AI" avatar)
     */
    class AgentViewHolder(private val binding: ItemTranscriptAgentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(transcript: Transcript) {
            binding.tvTranscriptText.text = transcript.text.ifEmpty { "..." }
        }
    }

    private class TranscriptDiffCallback : DiffUtil.ItemCallback<Transcript>() {
        override fun areItemsTheSame(oldItem: Transcript, newItem: Transcript): Boolean {
            return oldItem.turnId == newItem.turnId && oldItem.type == newItem.type
        }

        override fun areContentsTheSame(oldItem: Transcript, newItem: Transcript): Boolean {
            return oldItem == newItem
        }
    }
}
