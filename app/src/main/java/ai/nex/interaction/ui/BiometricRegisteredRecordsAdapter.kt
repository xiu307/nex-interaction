package ai.nex.interaction.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ai.nex.interaction.R
import ai.nex.interaction.biometric.BiometricRegistrationSnapshot
import ai.nex.interaction.biometric.BiometricSalRegistry
import ai.nex.interaction.biometric.BiometricStoredPersonRow
import ai.nex.interaction.databinding.ItemBiometricRegisteredRecordBinding
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RegisteredRecordUiModel(
    val row: BiometricStoredPersonRow,
    val snapshotForRow: BiometricRegistrationSnapshot?,
    val salReady: Boolean,
)

class BiometricRegisteredRecordsAdapter(
    private val onPlayPcm: (pcmHttpUrl: String, button: MaterialButton) -> Unit,
    private val onDelete: (faceId: String) -> Unit,
) : RecyclerView.Adapter<BiometricRegisteredRecordsAdapter.VH>() {

    private val items = mutableListOf<RegisteredRecordUiModel>()

    fun submit(list: List<RegisteredRecordUiModel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemBiometricRegisteredRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemBiometricRegisteredRecordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(model: RegisteredRecordUiModel) {
            val ctx = binding.root.context
            val row = model.row
            binding.tvFaceId.text = row.faceId

            if (model.snapshotForRow != null) {
                val s = model.snapshotForRow
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(s.savedAtEpochMs))
                binding.tvSnapshot.text = ctx.getString(
                    R.string.biometric_record_snapshot_body,
                    timeStr,
                    s.faceImageOssUrl,
                    s.pcmOssUrl,
                )
            } else {
                binding.tvSnapshot.text = ctx.getString(R.string.biometric_record_snapshot_none)
            }

            binding.tvSal.text = ctx.getString(
                R.string.biometric_sal_ready_line,
                ctx.getString(
                    if (model.salReady) {
                        R.string.biometric_sal_ready_yes
                    } else {
                        R.string.biometric_sal_ready_no
                    },
                ),
            )

            val pcmUrl = row.pcmOssUrl?.trim().orEmpty()
            val canPlay = pcmUrl.isNotEmpty() && BiometricSalRegistry.isHttpUrl(pcmUrl)
            binding.btnPlayPcm.isEnabled = canPlay
            binding.btnPlayPcm.text = ctx.getString(R.string.biometric_record_play_pcm)
            binding.btnPlayPcm.setOnClickListener {
                if (canPlay) {
                    onPlayPcm(pcmUrl, binding.btnPlayPcm)
                }
            }

            binding.btnDelete.setOnClickListener {
                onDelete(row.faceId)
            }
        }
    }
}
