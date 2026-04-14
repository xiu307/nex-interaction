package ai.nex.interaction.ui

import android.app.Activity
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ai.nex.interaction.R
import ai.nex.interaction.audio.Pcm16kMonoPreviewPlayer
import ai.nex.interaction.biometric.BiometricSalRegistry
import ai.nex.interaction.databinding.ActivityBiometricRegisteredRecordsBinding
import ai.nex.interaction.ui.common.BaseActivity
import com.google.android.material.button.MaterialButton

/**
 * 注册记录：faceId、快照、SAL 状态、PCM 试听（16k raw）、单条删除。
 */
class BiometricRegisteredRecordsActivity : BaseActivity<ActivityBiometricRegisteredRecordsBinding>() {

    private val pcmPlayer = Pcm16kMonoPreviewPlayer()
    private var playingButton: MaterialButton? = null

    private lateinit var adapter: BiometricRegisteredRecordsAdapter

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, BiometricRegisteredRecordsActivity::class.java))
        }
    }

    override fun getViewBinding(): ActivityBiometricRegisteredRecordsBinding {
        return ActivityBiometricRegisteredRecordsBinding.inflate(layoutInflater)
    }

    override fun initView() {
        mBinding?.apply {
            setOnApplyWindowInsetsListener(root)
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setTitle(R.string.biometric_view_registered_title)
            toolbar.setNavigationOnClickListener { finish() }

            adapter = BiometricRegisteredRecordsAdapter(
                onPlayPcm = { url, button -> playPcm(url, button) },
                onDelete = { faceId -> confirmDelete(faceId) },
            )
            recycler.layoutManager = LinearLayoutManager(this@BiometricRegisteredRecordsActivity)
            recycler.adapter = adapter
            refreshList()
        }
    }

    override fun initData() {}

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_biometric_registered_records, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_all_records -> {
                confirmClearAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onDestroy() {
        pcmPlayer.stop()
        super.onDestroy()
    }

    private fun buildUiModels(): List<RegisteredRecordUiModel> {
        val rows = BiometricSalRegistry.getAllStoredPersonRows()
        val complete = BiometricSalRegistry.getCompleteSalFaceIdToPcmUrls()
        val snap = BiometricSalRegistry.getRegistrationSnapshot()
        return rows.map { row ->
            RegisteredRecordUiModel(
                row = row,
                snapshotForRow = snap?.takeIf { it.faceId == row.faceId },
                salReady = complete.containsKey(row.faceId),
            )
        }
    }

    private fun refreshList() {
        val list = buildUiModels()
        mBinding?.apply {
            adapter.submit(list)
            val empty = list.isEmpty()
            tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            recycler.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    private fun playPcm(url: String, button: MaterialButton) {
        playingButton?.let { prev ->
            if (prev !== button) {
                prev.text = getString(R.string.biometric_record_play_pcm)
                prev.isEnabled = true
            }
        }
        playingButton = button
        button.text = getString(R.string.biometric_record_play_pcm_busy)
        button.isEnabled = false
        pcmPlayer.playFromHttpUrl(
            url,
            lifecycleScope,
            onError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                button.text = getString(R.string.biometric_record_play_pcm)
                button.isEnabled = true
                if (playingButton === button) playingButton = null
            },
            onStarted = {},
            onEnded = {
                button.text = getString(R.string.biometric_record_play_pcm)
                button.isEnabled = true
                if (playingButton === button) playingButton = null
            },
        )
    }

    private fun confirmDelete(faceId: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.biometric_record_delete_title)
            .setMessage(R.string.biometric_record_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.biometric_record_delete) { _, _ ->
                BiometricSalRegistry.removeRegistrationForFaceId(faceId)
                pcmPlayer.stop()
                playingButton = null
                refreshList()
                Toast.makeText(this, R.string.biometric_record_delete_ok, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.biometric_record_clear_all_title)
            .setMessage(R.string.biometric_record_clear_all_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.biometric_record_clear_all) { _, _ ->
                BiometricSalRegistry.clearAllRegistration()
                pcmPlayer.stop()
                playingButton = null
                refreshList()
                Toast.makeText(this, R.string.biometric_record_clear_all_ok, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
