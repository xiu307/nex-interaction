package cn.shengwang.convoai.quickstart.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import cn.shengwang.convoai.quickstart.R
import cn.shengwang.convoai.quickstart.biometric.BiometricRegistrationSnapshot
import cn.shengwang.convoai.quickstart.biometric.BiometricSalRegistry
import cn.shengwang.convoai.quickstart.biometric.ConvoFacedetDock
import cn.shengwang.convoai.quickstart.databinding.ActivityBiometricRegisterBinding
import cn.shengwang.convoai.quickstart.oss.OssStsRuntime
import cn.shengwang.convoai.quickstart.oss.OssTestBucketUploader
import cn.shengwang.convoai.quickstart.oss.OssUploadResult
import cn.shengwang.convoai.quickstart.ui.common.BaseActivity
import com.robotchat.facedet.FaceDetector
import com.robotchat.facedet.callback.FrameAnalysisCallback
import com.robotchat.facedet.model.FaceResult
import com.robotchat.facedet.recognition.PhotoFaceEnrollment
import com.robotchat.facedet.recognition.VideoFaceEnrollment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 与 Android `CovBiometricRegisterActivity` 对齐：人脸**视频**入库（相册/录制）→ 封面图 OSS → PCM 录音 → PCM OSS → 保存本地 JSON。
 */
class BiometricRegisterActivity : BaseActivity<ActivityBiometricRegisterBinding>() {

    companion object {
        private const val TAG = "BiometricRegister"
        private const val FACE_JPEG_QUALITY = 88
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_MULTIPLIER = 2

        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, BiometricRegisterActivity::class.java))
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val mic = result[Manifest.permission.RECORD_AUDIO] == true
        val cam = result[Manifest.permission.CAMERA] == true
        if (mic && cam) {
            onPermissionsReady()
        } else {
            Toast.makeText(this, getString(R.string.biometric_permission_need), Toast.LENGTH_LONG).show()
            refreshFaceButtonsEnabled()
            mBinding?.btnVoiceStart?.isEnabled = false
            mBinding?.btnSaveRegistration?.isEnabled = false
        }
    }

    private var videoCaptureUri: Uri? = null

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                enrollInProgress = false
                refreshFaceButtonsEnabled()
                mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_cancel_pick)
                return@registerForActivityResult
            }
            enrollFromVideoUri(uri)
        }

    private val captureVideoLauncher =
        registerForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
            val uri = videoCaptureUri
            if (!success || uri == null) {
                enrollInProgress = false
                refreshFaceButtonsEnabled()
                mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_video_cancel)
                return@registerForActivityResult
            }
            mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_enrolling)
            enrollFromVideoUri(uri)
        }

    private val requestCameraMicForVideoLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val camOk = result[Manifest.permission.CAMERA] == true
            val micOk = result[Manifest.permission.RECORD_AUDIO] == true
            if (camOk && micOk) {
                startVideoCaptureForEnrollment()
            } else {
                enrollInProgress = false
                refreshFaceButtonsEnabled()
                mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_video_need_cam_mic)
            }
        }

    private var audioRecord: AudioRecord? = null
    private var pcmOut: FileOutputStream? = null
    private var pcmFile: File? = null
    private var recordBufferSize = 0
    @Volatile
    private var isRecordingPcm = false
    private var pcmThread: Thread? = null

    @Volatile
    private var enrollInProgress = false

    private var faceDetector: FaceDetector? = null

    override fun getViewBinding(): ActivityBiometricRegisterBinding {
        return ActivityBiometricRegisterBinding.inflate(layoutInflater)
    }

    override fun initView() {
        mBinding?.apply {
            val savedId = BiometricSalRegistry.getLastRegisteredFaceId().orEmpty()
            etFaceId.setText(savedId)
            if (savedId.isNotEmpty()) {
                tvFaceIdStatus.text = getString(R.string.biometric_face_saved, savedId)
            } else {
                tvFaceIdStatus.text = getString(R.string.biometric_faceid_waiting)
            }
            etFaceId.doAfterTextChanged { refreshStepGates() }
            btnFaceFromGallery.setOnClickListener { launchFaceFromGallery() }
            btnFaceRecord.setOnClickListener { launchFaceRecordVideo() }
            btnVoiceStart.setOnClickListener { startPcmRecording() }
            btnVoiceStop.setOnClickListener { stopPcmRecordingAndUpload() }
            btnSaveRegistration.setOnClickListener { saveRegistrationBundle() }
            btnViewRegistered.setOnClickListener { showRegisteredRecordsDialog() }
            btnClearRegistration.setOnClickListener { showClearRegistrationConfirmDialog() }
            tvVoiceStatus.text = getString(R.string.biometric_voice_idle)
            refreshSaveRegistrationStatusText()
            refreshStepGates()
        }
        prepareFaceDetectorLikeDemoOnCreate()
        checkPermissions()
    }

    override fun onDestroy() {
        stopPcmRecordingSync()
        runCatching { faceDetector?.unbindCamera() }
        runCatching { PhotoFaceEnrollment.releaseLandmarker() }
        runCatching { faceDetector?.release() }
        faceDetector = null
        super.onDestroy()
    }

    private fun showClearRegistrationConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.biometric_clear_registration_title)
            .setMessage(R.string.biometric_clear_registration_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.biometric_clear_registration_confirm_btn) { _, _ ->
                performClearAllRegistration()
            }
            .show()
    }

    /**
     * 清除 SP、工作目录文件，并删除 facedet 人脸库中全部已登记 userId（embedding）。
     */
    private fun performClearAllRegistration() {
        runCatching {
            val fd = faceDetector
            val mgr = fd?.multiPersonRecognitionManager
            val im = mgr?.getIdentityManager() ?: return@runCatching
            val ids = im.getAllUserIds().toList()
            for (uid in ids) {
                im.deleteEmbedding(uid)
            }
            Log.i(TAG, "cleared face embeddings count=${ids.size}")
        }.onFailure {
            Log.e(TAG, "clear face SDK: ${it.message}", it)
        }

        BiometricSalRegistry.clearAllRegistration()
        runCatching {
            val dir = workDir()
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    runCatching { f.delete() }.onFailure { e -> Log.w(TAG, "delete ${f.name}: ${e.message}") }
                }
            }
        }.onFailure {
            Log.e(TAG, "clear work dir: ${it.message}", it)
        }

        mBinding?.apply {
            etFaceId.setText("")
            previewFace.setImageDrawable(null)
            tvFaceIdStatus.text = getString(R.string.biometric_faceid_waiting)
            tvVoiceStatus.text = getString(R.string.biometric_voice_idle)
            tvSaveRegistrationStatus.text = ""
        }
        refreshStepGates()
        refreshSaveRegistrationStatusText()
        applyFaceRecognitionReadyStatusUi(showToastIfRecognitionNull = false)
        refreshFaceButtonsEnabled()
        Toast.makeText(this, R.string.biometric_clear_registration_ok, Toast.LENGTH_SHORT).show()
    }

    /** 仅展示 OSS http 链接；local:// 或未上传显示为「—」。 */
    private fun formatOssUrlForDialog(url: String?): String {
        if (url.isNullOrBlank()) return "—"
        return if (BiometricSalRegistry.isHttpUrl(url)) url else "—"
    }

    /** 展示 SharedPreferences + 上次快照；标明是否满足 SAL（双 http）。 */
    private fun showRegisteredRecordsDialog() {
        val rows = BiometricSalRegistry.getAllStoredPersonRows()
        val snap = BiometricSalRegistry.getRegistrationSnapshot()
        val complete = BiometricSalRegistry.getCompleteSalFaceIdToPcmUrls()
        val sb = StringBuilder()
        if (snap != null) {
            sb.append(
                getString(
                    R.string.biometric_snapshot_block,
                    snap.faceId,
                    snap.faceImageOssUrl,
                    snap.pcmOssUrl,
                ),
            ).append("\n")
        }
        if (rows.isEmpty()) {
            sb.append(getString(R.string.biometric_no_registered_rows))
        } else {
            for (row in rows) {
                val salReady = complete.containsKey(row.faceId)
                sb.append("━━ ").append(row.faceId).append(" ━━\n")
                sb.append(
                    getString(
                        R.string.biometric_row_face_line,
                        formatOssUrlForDialog(row.faceImageOssUrl),
                    ),
                ).append("\n")
                sb.append(
                    getString(
                        R.string.biometric_row_pcm_line,
                        formatOssUrlForDialog(row.pcmOssUrl),
                    ),
                ).append("\n")
                sb.append(
                    getString(
                        R.string.biometric_sal_ready_line,
                        getString(
                            if (salReady) {
                                R.string.biometric_sal_ready_yes
                            } else {
                                R.string.biometric_sal_ready_no
                            },
                        ),
                    ),
                ).append("\n\n")
            }
        }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = sb.toString()
            setTextColor(ContextCompat.getColor(this@BiometricRegisterActivity, android.R.color.black))
            textSize = 12f
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle(R.string.biometric_view_registered_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun workDir(): File {
        val dir = File(getExternalFilesDir(null), "biometric_register")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun deviceIdForOss(): String = ConvoFacedetDock.stableDeviceId(this)

    private fun messageForOssUploadFailure(result: OssUploadResult): String {
        return when (result.errorCode) {
            OssUploadResult.ERROR_OSS_INIT_ERR -> getString(R.string.biometric_oss_init_fail)
            OssUploadResult.ERROR_OSS_NETWORK_ERR -> getString(R.string.biometric_oss_network_fail)
            OssUploadResult.ERROR_OSS_SERVICE_ERR -> getString(R.string.biometric_oss_service_fail)
            else -> getString(R.string.biometric_oss_upload_fail_generic, result.errorCode ?: -1)
        }
    }

    private fun checkPermissions() {
        val needMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        val needCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        if (needMic || needCam) {
            permLauncher.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
            )
        } else {
            onPermissionsReady()
        }
    }

    private fun onPermissionsReady() {
        applyFaceRecognitionReadyStatusUi(showToastIfRecognitionNull = true)
        refreshFaceButtonsEnabled()
        refreshStepGates()
    }

    private fun prepareFaceDetectorLikeDemoOnCreate() {
        mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_init_library)
        runCatching {
            initFaceDetectorIfNeeded()
            faceDetector!!.prepareEngine(applicationContext)
            faceDetector!!.unbindCamera()
        }.onFailure { e ->
            Log.e(TAG, "prepareEngine: ${e.message}", e)
            val msg = e.message ?: e.javaClass.simpleName
            mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_init_fail, msg)
            Toast.makeText(this, getString(R.string.biometric_init_fail, msg), Toast.LENGTH_LONG).show()
            refreshFaceButtonsEnabled()
            return
        }
        applyFaceRecognitionReadyStatusUi(showToastIfRecognitionNull = false)
    }

    private fun applyFaceRecognitionReadyStatusUi(showToastIfRecognitionNull: Boolean) {
        val fd = faceDetector
        if (fd?.multiPersonRecognitionManager == null) {
            val why = fd?.getLastRecognitionInitFailureMessage()?.trim().orEmpty()
            val statusText = if (why.isNotEmpty()) {
                getString(R.string.biometric_recognition_detail, why)
            } else {
                getString(R.string.biometric_recognition_not_ready)
            }
            mBinding?.tvFaceIdStatus?.text = statusText
            if (showToastIfRecognitionNull) {
                Toast.makeText(this, statusText, Toast.LENGTH_LONG).show()
            }
        } else {
            mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_ready)
        }
        refreshFaceButtonsEnabled()
    }

    private fun initFaceDetectorIfNeeded() {
        if (faceDetector != null) return
        val config = ConvoFacedetDock.configForBiometricRegistration(this)
        val fd = FaceDetector(config)
        fd.setCallback(
            object : FrameAnalysisCallback {
                override fun onFaceResult(result: FaceResult) {}
            },
        )
        fd.start()
        faceDetector = fd
    }

    private fun launchFaceFromGallery() {
        if (enrollInProgress) return
        val fd = faceDetector ?: run {
            Toast.makeText(this, getString(R.string.biometric_lib_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (fd.multiPersonRecognitionManager == null) {
            Toast.makeText(this, getString(R.string.biometric_recognition_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        enrollInProgress = true
        refreshFaceButtonsEnabled()
        mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_pick_video)
        pickVideoLauncher.launch(arrayOf("video/*"))
    }

    private fun launchFaceRecordVideo() {
        if (enrollInProgress) return
        val fd = faceDetector ?: run {
            Toast.makeText(this, getString(R.string.biometric_lib_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (fd.multiPersonRecognitionManager == null) {
            Toast.makeText(this, getString(R.string.biometric_recognition_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!camOk || !micOk) {
            enrollInProgress = true
            refreshFaceButtonsEnabled()
            mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_video_need_cam_mic)
            requestCameraMicForVideoLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )
            return
        }
        enrollInProgress = true
        refreshFaceButtonsEnabled()
        mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_opening_camera)
        startVideoCaptureForEnrollment()
    }

    private fun startVideoCaptureForEnrollment() {
        val file = File(cacheDir, "biometric_enroll_${System.currentTimeMillis()}.mp4")
        runCatching {
            if (!file.exists()) file.createNewFile()
        }.onFailure { Log.w(TAG, "create mp4: ${it.message}") }
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file,
        )
        videoCaptureUri = uri
        runCatching {
            captureVideoLauncher.launch(uri)
        }.onFailure { e ->
            Log.e(TAG, "CaptureVideo launch failed", e)
            enrollInProgress = false
            refreshFaceButtonsEnabled()
            mBinding?.tvFaceIdStatus?.text = e.message ?: "CaptureVideo failed"
        }
    }

    private fun enrollFromVideoUri(uri: Uri) {
        val fd = faceDetector ?: run {
            enrollInProgress = false
            refreshFaceButtonsEnabled()
            Toast.makeText(this, getString(R.string.biometric_lib_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        val mgr = fd.multiPersonRecognitionManager
        if (mgr == null) {
            enrollInProgress = false
            refreshFaceButtonsEnabled()
            mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_recognition_not_ready)
            Toast.makeText(this, getString(R.string.biometric_recognition_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_enrolling)
        VideoFaceEnrollment.enrollFromVideo(
            this,
            uri,
            mgr,
            fd.getConfig(),
            false,
            object : VideoFaceEnrollment.ProgressCallback {
                override fun onProgress(current: Int, total: Int, stage: String) {
                    runOnUiThread { mBinding?.tvFaceIdStatus?.text = stage }
                }
            },
            object : PhotoFaceEnrollment.ResultCallback {
                override fun onSuccess(faceId: String, isNewUser: Boolean, quality: Float) {
                    if (isFinishing || isDestroyed) return
                    Log.d(TAG, "VideoFaceEnrollment ok faceId=$faceId new=$isNewUser q=$quality")
                    val rawPreview = VideoFaceEnrollment.extractPreviewFrame(this@BiometricRegisterActivity, uri)
                    val uploadCopy = rawPreview?.copy(Bitmap.Config.ARGB_8888, false)
                    rawPreview?.recycle()
                    enrollInProgress = false
                    refreshFaceButtonsEnabled()
                    mBinding?.etFaceId?.setText(faceId)
                    mBinding?.tvFaceIdStatus?.text = getString(
                        R.string.biometric_faceid_enrolled_uploading,
                        faceId,
                        quality,
                    )
                    BiometricSalRegistry.setLastRegisteredFaceId(faceId)
                    refreshStepGates()
                    if (uploadCopy == null) {
                        Toast.makeText(
                            this@BiometricRegisterActivity,
                            R.string.biometric_face_preview_fail,
                            Toast.LENGTH_LONG,
                        ).show()
                        return
                    }
                    val displayCopy = uploadCopy.copy(Bitmap.Config.ARGB_8888, false)
                    mBinding?.previewFace?.setImageBitmap(displayCopy)
                    uploadFaceBitmapForFaceId(faceId, uploadCopy)
                }

                override fun onError(message: String) {
                    if (isFinishing || isDestroyed) return
                    enrollInProgress = false
                    refreshFaceButtonsEnabled()
                    mBinding?.tvFaceIdStatus?.text = message
                    Toast.makeText(this@BiometricRegisterActivity, message, Toast.LENGTH_LONG).show()
                    Log.e(TAG, "VideoFaceEnrollment: $message")
                }
            },
        )
    }

    private fun refreshFaceButtonsEnabled() {
        val m = mBinding ?: return
        val mgrReady = faceDetector?.multiPersonRecognitionManager != null
        val base = !enrollInProgress && mgrReady
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        m.btnFaceFromGallery.isEnabled = base
        m.btnFaceRecord.isEnabled = base && camOk && micOk
    }

    private fun uploadFaceBitmapForFaceId(faceKey: String, bitmap: Bitmap) {
        lifecycleScope.launch {
            val jpegBytes = withContext(Dispatchers.Default) {
                val bytes = ByteArrayOutputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, FACE_JPEG_QUALITY, out)) {
                        null
                    } else {
                        out.toByteArray()
                    }
                }
                bitmap.recycle()
                bytes
            }
            if (jpegBytes == null || jpegBytes.isEmpty()) {
                Toast.makeText(this@BiometricRegisterActivity, R.string.biometric_face_image_upload_fail, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (!OssStsRuntime.hasStsEndpoint()) {
                Log.i(TAG, "face OSS: skip (OSS_STS_TOKEN_URL empty)")
                BiometricSalRegistry.saveFaceIdToFaceImageUrl(
                    faceKey,
                    BiometricSalRegistry.FACE_IMAGE_URL_LOCAL_ONLY,
                )
                Toast.makeText(
                    this@BiometricRegisterActivity,
                    R.string.biometric_oss_sts_not_configured,
                    Toast.LENGTH_LONG,
                ).show()
                refreshStepGates()
                return@launch
            }
            val upload = runCatching {
                withContext(Dispatchers.IO) {
                    val uploader = OssTestBucketUploader.createWithHttpSts(this@BiometricRegisterActivity)
                    uploader.uploadSpeakerIdBytes(jpegBytes, deviceIdForOss(), "jpg", faceKey)
                }
            }.getOrElse {
                Log.e(TAG, "face OSS: ${it.message}", it)
                Toast.makeText(this@BiometricRegisterActivity, R.string.biometric_face_image_upload_fail, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (upload.ok && !upload.url.isNullOrEmpty()) {
                BiometricSalRegistry.saveFaceIdToFaceImageUrl(faceKey, upload.url!!)
                Log.d(TAG, "face image uploaded key=$faceKey url=${upload.url}")
                Toast.makeText(
                    this@BiometricRegisterActivity,
                    getString(R.string.biometric_face_image_upload_ok, upload.url!!),
                    Toast.LENGTH_SHORT,
                ).show()
                refreshStepGates()
                persistRegistrationSnapshotIfHttpCompleteAfterOss()
            } else {
                Toast.makeText(
                    this@BiometricRegisterActivity,
                    messageForOssUploadFailure(upload),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun isStep1Complete(): Boolean {
        val currentId = mBinding?.etFaceId?.text?.toString()?.trim().orEmpty()
        if (currentId.isEmpty()) return false
        val lastId = BiometricSalRegistry.getLastRegisteredFaceId() ?: return false
        if (currentId != lastId) return false
        val faceUrl = BiometricSalRegistry.getFaceImageHttpUrl(lastId) ?: return false
        if (BiometricSalRegistry.isHttpUrl(faceUrl)) return true
        return faceUrl == BiometricSalRegistry.FACE_IMAGE_URL_LOCAL_ONLY
    }

    private fun isStep2Complete(): Boolean {
        if (!isStep1Complete()) return false
        val lastId = BiometricSalRegistry.getLastRegisteredFaceId() ?: return false
        val pcmUrl = BiometricSalRegistry.getPcmHttpUrl(lastId) ?: return false
        if (BiometricSalRegistry.isHttpUrl(pcmUrl)) return true
        return pcmUrl == BiometricSalRegistry.PCM_URL_LOCAL_ONLY
    }

    /** 本地快照与 biometric_registration.json 只写入 OSS 的 http(s)，不写入本地文件路径。 */
    private fun canPersistRegistrationSnapshotWithOssOnly(): Boolean {
        val id = BiometricSalRegistry.getLastRegisteredFaceId() ?: return false
        return BiometricSalRegistry.getCompleteSalFaceIdToPcmUrls().containsKey(id)
    }

    private fun refreshStepGates() {
        val m = mBinding ?: return
        val step1 = isStep1Complete()
        val step2 = isStep2Complete()
        if (!isRecordingPcm) {
            m.btnVoiceStart.isEnabled = step1
        }
        m.btnSaveRegistration.isEnabled = canPersistRegistrationSnapshotWithOssOnly()
    }

    /**
     * OSS 映射已写入 SP，但「上次保存」与 JSON 原只在手动点「保存到本地」时更新。
     * 当人脸图 + PCM 均为 http(s) 时自动同步快照与文件，避免误以为「没更新到本地」。
     * @return 是否已写入快照与 JSON（双 OSS 齐套）
     */
    private fun persistRegistrationSnapshotIfHttpCompleteAfterOss(): Boolean {
        val faceId = BiometricSalRegistry.getLastRegisteredFaceId() ?: return false
        val pcmUrl = BiometricSalRegistry.getCompleteSalFaceIdToPcmUrls()[faceId] ?: return false
        val faceUrl = BiometricSalRegistry.getFaceImageHttpUrl(faceId) ?: return false
        if (!BiometricSalRegistry.isHttpUrl(faceUrl) || !BiometricSalRegistry.isHttpUrl(pcmUrl)) return false
        val snapshot = BiometricRegistrationSnapshot(
            faceId = faceId,
            faceImageOssUrl = faceUrl,
            pcmOssUrl = pcmUrl,
            savedAtEpochMs = System.currentTimeMillis(),
        )
        BiometricSalRegistry.setLastRegisteredFaceId(faceId)
        val json = BiometricSalRegistry.saveRegistrationSnapshot(snapshot)
        BiometricSalRegistry.replaceAllRegistrationDataWithSingleFaceId(faceId)
        refreshSaveRegistrationStatusText()
        Toast.makeText(this, R.string.biometric_auto_snapshot_synced, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val f = File(workDir(), "biometric_registration.json")
                f.writeText(json)
                Log.d(TAG, "auto snapshot file: ${f.absolutePath}")
            }.onFailure {
                Log.e(TAG, "auto write json: ${it.message}")
            }
        }
        return true
    }

    private fun refreshSaveRegistrationStatusText() {
        val snap = BiometricSalRegistry.getRegistrationSnapshot() ?: run {
            mBinding?.tvSaveRegistrationStatus?.text = ""
            return
        }
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(snap.savedAtEpochMs))
        mBinding?.tvSaveRegistrationStatus?.text = getString(
            R.string.biometric_save_registration_status_detail,
            snap.faceId,
            snap.faceImageOssUrl,
            snap.pcmOssUrl,
            timeStr,
        )
    }

    private fun saveRegistrationBundle() {
        val faceId = BiometricSalRegistry.getLastRegisteredFaceId() ?: run {
            Toast.makeText(this, R.string.biometric_save_registration_need_face_id, Toast.LENGTH_SHORT).show()
            return
        }
        val faceUrl = BiometricSalRegistry.getFaceImageHttpUrl(faceId)
        val pcmUrl = BiometricSalRegistry.getPcmHttpUrl(faceId)
        if (!BiometricSalRegistry.isHttpUrl(faceUrl) || !BiometricSalRegistry.isHttpUrl(pcmUrl)) {
            Toast.makeText(this, R.string.biometric_save_only_oss_hint, Toast.LENGTH_LONG).show()
            return
        }
        val snapshot = BiometricRegistrationSnapshot(
            faceId = faceId,
            faceImageOssUrl = faceUrl!!,
            pcmOssUrl = pcmUrl!!,
            savedAtEpochMs = System.currentTimeMillis(),
        )
        BiometricSalRegistry.setLastRegisteredFaceId(faceId)
        val json = BiometricSalRegistry.saveRegistrationSnapshot(snapshot)
        BiometricSalRegistry.replaceAllRegistrationDataWithSingleFaceId(faceId)
        refreshSaveRegistrationStatusText()
        Toast.makeText(this, R.string.biometric_save_registration_ok, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val f = File(workDir(), "biometric_registration.json")
                f.writeText(json)
                Log.d(TAG, "registration snapshot file: ${f.absolutePath}")
            }.onFailure {
                Log.e(TAG, "write json: ${it.message}")
            }
        }
    }

    private fun startPcmRecording() {
        if (!isStep1Complete()) {
            Toast.makeText(this, R.string.biometric_gate_step1, Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            checkPermissions()
            return
        }
        if (isRecordingPcm) return

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf == AudioRecord.ERROR) {
            Toast.makeText(this, "无法初始化录音", Toast.LENGTH_SHORT).show()
            return
        }
        recordBufferSize = minBuf * BUFFER_MULTIPLIER
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        pcmFile = File(workDir(), "voice_$stamp.pcm")
        pcmOut = FileOutputStream(pcmFile)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            recordBufferSize,
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "录音初始化失败", Toast.LENGTH_SHORT).show()
            releasePcmOnly()
            return
        }

        isRecordingPcm = true
        audioRecord?.startRecording()
        mBinding?.apply {
            btnVoiceStart.isEnabled = false
            btnVoiceStop.isEnabled = true
            tvVoiceStatus.text = getString(R.string.biometric_voice_recording)
        }

        pcmThread = Thread({
            val buf = ByteArray(recordBufferSize)
            while (isRecordingPcm && !Thread.currentThread().isInterrupted) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                if (n > 0) {
                    try {
                        pcmOut?.write(buf, 0, n)
                    } catch (e: Exception) {
                        Log.e(TAG, "pcm write: ${e.message}")
                        break
                    }
                } else if (n < 0) break
            }
        }, "biometric-pcm").apply { start() }
    }

    private fun stopPcmRecordingSync() {
        if (!isRecordingPcm) return
        isRecordingPcm = false
        pcmThread?.interrupt()
        pcmThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) { }
        audioRecord?.release()
        audioRecord = null
        try {
            pcmOut?.flush()
            pcmOut?.close()
        } catch (_: Exception) { }
        pcmOut = null
        mBinding?.btnVoiceStop?.isEnabled = false
        refreshStepGates()
    }

    private fun stopPcmRecordingAndUpload() {
        if (!isRecordingPcm) return
        val file = pcmFile
        stopPcmRecordingSync()
        val path = file?.absolutePath ?: run {
            mBinding?.tvVoiceStatus?.text = getString(R.string.biometric_voice_idle)
            pcmFile = null
            return
        }
        mBinding?.tvVoiceStatus?.text = getString(R.string.biometric_voice_upload_pending)
        val currentId = mBinding?.etFaceId?.text?.toString()?.trim().orEmpty()
        val lastId = BiometricSalRegistry.getLastRegisteredFaceId()
        if (currentId.isEmpty() || lastId.isNullOrEmpty() || currentId != lastId) {
            Toast.makeText(this, R.string.biometric_faceid_mismatch_pcm, Toast.LENGTH_SHORT).show()
            pcmFile = null
            refreshStepGates()
            return
        }
        val faceKey = lastId
        lifecycleScope.launch {
            if (!OssStsRuntime.hasStsEndpoint()) {
                Log.i(TAG, "PCM OSS: skip (OSS_STS_TOKEN_URL empty)")
                BiometricSalRegistry.saveFaceIdToPcmUrl(faceKey, BiometricSalRegistry.PCM_URL_LOCAL_ONLY)
                Toast.makeText(
                    this@BiometricRegisterActivity,
                    R.string.biometric_oss_sts_not_configured,
                    Toast.LENGTH_LONG,
                ).show()
                refreshStepGates()
                pcmFile = null
                return@launch
            }
            val upload = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = File(path).readBytes()
                    val uploader = OssTestBucketUploader.createWithHttpSts(this@BiometricRegisterActivity)
                    uploader.uploadSpeakerIdPcm(bytes, deviceIdForOss(), faceKey)
                }
            }.getOrElse {
                Log.e(TAG, "PCM OSS: ${it.message}", it)
                Toast.makeText(
                    this@BiometricRegisterActivity,
                    getString(R.string.biometric_pcm_upload_fail, it.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
                pcmFile = null
                return@launch
            }
            if (upload.ok && !upload.url.isNullOrEmpty()) {
                BiometricSalRegistry.saveFaceIdToPcmUrl(faceKey, upload.url!!)
                refreshStepGates()
                val synced = persistRegistrationSnapshotIfHttpCompleteAfterOss()
                if (!synced) {
                    Toast.makeText(
                        this@BiometricRegisterActivity,
                        getString(R.string.biometric_pcm_upload_ok, upload.url!!),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else {
                Toast.makeText(
                    this@BiometricRegisterActivity,
                    messageForOssUploadFailure(upload),
                    Toast.LENGTH_LONG,
                ).show()
            }
            pcmFile = null
        }
    }

    private fun releasePcmOnly() {
        try {
            pcmOut?.close()
        } catch (_: Exception) { }
        pcmOut = null
        audioRecord?.release()
        audioRecord = null
    }

}
