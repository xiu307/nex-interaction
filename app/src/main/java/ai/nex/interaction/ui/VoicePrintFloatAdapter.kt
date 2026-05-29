package ai.nex.interaction.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ai.nex.interaction.databinding.ItemVoicePrintFloatBinding
import ai.nex.interaction.biometric.VpSpeakerUiItem

class VoicePrintFloatAdapter(
    private val onDelete: (VpSpeakerUiItem) -> Unit,
) : ListAdapter<VpSpeakerUiItem, VoicePrintFloatAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<VpSpeakerUiItem>() {
        override fun areItemsTheSame(old: VpSpeakerUiItem, new: VpSpeakerUiItem): Boolean =
            old.speakerId == new.speakerId

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
        holder.binding.apply {
            tvVpSpeakerId.text = item.speakerId
            tvVpMeta.text = "uid=${item.rtcUid}"
            btnVpDelete.setOnClickListener { onDelete(item) }
        }
    }
}
