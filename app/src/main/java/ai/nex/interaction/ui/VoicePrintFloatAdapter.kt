package ai.nex.interaction.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ai.nex.interaction.R
import ai.nex.interaction.biometric.VpSpeakerStatus
import ai.nex.interaction.biometric.VpSpeakerUiItem
import ai.nex.interaction.databinding.ItemVoicePrintFloatBinding

class VoicePrintFloatAdapter(
    private val onConfirm: (VpSpeakerUiItem) -> Unit,
    private val onDelete: (VpSpeakerUiItem) -> Unit,
) : ListAdapter<VpSpeakerUiItem, VoicePrintFloatAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<VpSpeakerUiItem>() {
        override fun areItemsTheSame(old: VpSpeakerUiItem, new: VpSpeakerUiItem): Boolean =
            old.speakerId == new.speakerId && old.status == new.status

        override fun areContentsTheSame(old: VpSpeakerUiItem, new: VpSpeakerUiItem): Boolean =
            old == new
    }

    class VH(val binding: ItemVoicePrintFloatBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemVoicePrintFloatBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.binding.root.context
        holder.binding.apply {
            tvVpSpeakerId.text = item.speakerId
            val statusLabel = when (item.status) {
                VpSpeakerStatus.PENDING -> ctx.getString(R.string.agent_chat_voice_print_status_pending)
                VpSpeakerStatus.ACTIVE -> ctx.getString(R.string.agent_chat_voice_print_status_active)
            }
            tvVpMeta.text = "uid=${item.rtcUid} · $statusLabel"
            btnVpConfirm.isVisible = item.status == VpSpeakerStatus.PENDING
            btnVpConfirm.setOnClickListener { onConfirm(item) }
            btnVpDelete.setOnClickListener { onDelete(item) }
        }
    }
}
