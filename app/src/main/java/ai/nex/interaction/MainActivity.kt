package ai.nex.interaction

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ai.nex.interaction.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: VoiceChatViewModel

    private val recordAudioPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.startConversation()
            } else {
                Toast.makeText(this, R.string.demo_permission_required, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[VoiceChatViewModel::class.java]

        initView()
        observeViewModel()
    }

    private fun initView() {
        binding.btnStart.setOnClickListener {
            if (hasRecordAudioPermission()) {
                viewModel.startConversation()
            } else {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.btnMute.setOnClickListener {
            viewModel.toggleMute()
        }
        binding.btnToggleAudio.setOnClickListener {
            viewModel.toggleAudioInput()
        }
        binding.btnStop.setOnClickListener {
            viewModel.hangup()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { renderUiState(it) }
                }
                launch {
                    viewModel.agentState.collect { renderAgentState(it) }
                }
                launch {
                    viewModel.transcriptList.collect { renderTranscriptList(it) }
                }
                launch {
                    viewModel.debugLogList.collect { renderDebugLogs(it) }
                }
            }
        }
    }

    private fun renderUiState(state: VoiceChatViewModel.ConversationUiState) {
        binding.tvSessionInfo.text = getString(
            R.string.demo_session_info,
            viewModel.localUserId.toString(),
            viewModel.agentUid.toString(),
            state.channelName.ifBlank { "-" }
        )
        binding.tvConnectionState.text = getString(
            R.string.demo_connection_state,
            connectionStateLabel(state.connectionState)
        )

        binding.btnStart.text = when (state.connectionState) {
            VoiceChatViewModel.ConnectionState.Connecting -> getString(R.string.demo_start_connecting)
            VoiceChatViewModel.ConnectionState.Error -> getString(R.string.demo_start_retry)
            else -> getString(R.string.demo_start)
        }
        binding.btnStart.isEnabled = state.connectionState == VoiceChatViewModel.ConnectionState.Idle ||
            state.connectionState == VoiceChatViewModel.ConnectionState.Error

        binding.btnMute.isEnabled = state.connectionState == VoiceChatViewModel.ConnectionState.Connected
        binding.btnMute.text = if (state.isMuted) {
            getString(R.string.demo_unmute)
        } else {
            getString(R.string.demo_mute)
        }

        binding.btnToggleAudio.isEnabled = state.connectionState == VoiceChatViewModel.ConnectionState.Connected
        binding.btnToggleAudio.text = if (state.isAudioInputEnabled) {
            getString(R.string.demo_audio_stop)
        } else {
            getString(R.string.demo_audio_start)
        }

        binding.btnStop.isEnabled = state.connectionState != VoiceChatViewModel.ConnectionState.Idle
        binding.progressConnecting.visibility =
            if (state.connectionState == VoiceChatViewModel.ConnectionState.Connecting) View.VISIBLE else View.GONE
    }

    private fun renderAgentState(agentState: String) {
        binding.tvAgentState.text = getString(
            R.string.demo_agent_state,
            agentState
        )
    }

    private fun renderTranscriptList(transcripts: List<String>) {
        binding.tvTranscript.text = if (transcripts.isEmpty()) {
            getString(R.string.demo_transcript_placeholder)
        } else {
            transcripts.joinToString(separator = "\n\n")
        }
        binding.scrollTranscript.post {
            binding.scrollTranscript.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun renderDebugLogs(logs: List<String>) {
        binding.tvLog.text = if (logs.isEmpty()) {
            getString(R.string.demo_log_placeholder)
        } else {
            logs.joinToString(separator = "\n")
        }
        binding.scrollLog.post {
            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun connectionStateLabel(state: VoiceChatViewModel.ConnectionState): String = when (state) {
        VoiceChatViewModel.ConnectionState.Idle -> getString(R.string.demo_state_idle)
        VoiceChatViewModel.ConnectionState.Connecting -> getString(R.string.demo_state_connecting)
        VoiceChatViewModel.ConnectionState.Connected -> getString(R.string.demo_state_connected)
        VoiceChatViewModel.ConnectionState.Error -> getString(R.string.demo_state_error)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
