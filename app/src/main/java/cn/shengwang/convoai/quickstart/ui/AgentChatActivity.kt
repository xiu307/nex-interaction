package cn.shengwang.convoai.quickstart.ui

import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.shengwang.convoai.quickstart.R
import cn.shengwang.convoai.quickstart.tools.PermissionHelp
import cn.shengwang.convoai.quickstart.ui.common.BaseActivity
import cn.shengwang.convoai.quickstart.video.CameraVideoInputManager
import io.agora.convoai.convoaiApi.AgentState
import io.agora.convoai.convoaiApi.Transcript
import io.agora.convoai.convoaiApi.TranscriptType
import cn.shengwang.convoai.quickstart.databinding.ActivityAgentChatBinding
import cn.shengwang.convoai.quickstart.databinding.ItemTranscriptUserBinding
import cn.shengwang.convoai.quickstart.databinding.ItemTranscriptAgentBinding
import kotlinx.coroutines.launch

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

    override fun initView() {
        mBinding?.apply {
            setOnApplyWindowInsetsListener(root)

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

            // Stop button click listener
            btnStop.setOnClickListener {
                viewModel.hangup()
            }
        }
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
                    btnInputVideo.isEnabled = isConnected || isVideoInputStarted
                    if (!isConnected && isVideoInputStarted) {
                        stopVideoInput()
                    }

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
