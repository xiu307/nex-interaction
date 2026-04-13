package ai.nex.interaction.ui

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import ai.nex.interaction.R
import ai.nex.interaction.biometric.BiometricRegistrationSnapshot
import ai.nex.interaction.biometric.BiometricSalRegistry
import ai.nex.interaction.biometric.ConvoFacedetDock
import ai.nex.interaction.databinding.ActivityBiometricRegisterBinding
import ai.nex.interaction.oss.OssStsRuntime
import ai.nex.interaction.oss.OssTestBucketUploader
import ai.nex.interaction.oss.OssUploadResult
import ai.nex.interaction.ui.common.BaseActivity
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
        private const val AUTO_VOICE_RECORD_MS = 6000L

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

    /** 系统 Photo Picker（仅视频），较 OpenDocument 更省权限、OEM 兼容性更好 */
    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
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
            lifecycleScope.launch(Dispatchers.IO) {
                val saved = runCatching { copyCapturedVideoToMediaStore(uri) }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@BiometricRegisterActivity,
                        if (saved) {
                            R.string.biometric_video_saved_to_gallery
                        } else {
                            R.string.biometric_video_save_gallery_fail
                        },
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_enrolling)
            enrollFromVideoUri(uri)
        }

    private val requestCameraMicForVideoLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val camOk = result[Manifest.permission.CAMERA] == true
            val micOk = result[Manifest.permission.RECORD_AUDIO] == true
            if (camOk && micOk) {
                // 权限弹窗刚关闭时立刻 startActivityForResult 部分机型会 IllegalStateException，延后一帧
                mainHandler.post { startVideoCaptureForEnrollment() }
            } else {
                enrollInProgress = false
                refreshFaceButtonsEnabled()
                mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_video_need_cam_mic)
            }
        }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var pcmOut: FileOutputStream? = null
    private var pcmFile: File? = null
    private var recordBufferSize = 0
    @Volatile
    private var isRecordingPcm = false
    private var pcmThread: Thread? = null

    @Volatile
    private var enrollInProgress = false

    /** [prepareFaceDetectorLikeDemoOnCreate] 中 prepareEngine 成功后才为 true，用于控制人脸按钮可点 */
    @Volatile
    private var faceEnginePreparedOk = false

    private var faceDetector: FaceDetector? = null
    @Volatile
    private var liveEnrollRunning = false
    private var autoLiveEnrollAttempted = false
    private var autoVoiceCaptureScheduled = false
    private var lastLiveEnrollStartAtMs = 0L

    override fun getViewBinding(): ActivityBiometricRegisterBinding {
        return ActivityBiometricRegisterBinding.inflate(layoutInflater)
    }

    override fun initView() {
        mBinding?.apply {
            setOnApplyWindowInsetsListener(root)
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setTitle(R.string.biometric_register_title_full)
            toolbar.setNavigationOnClickListener { finish() }

            val savedId = BiometricSalRegistry.getLastRegisteredFaceId().orEmpty()
            etFaceId.setText(savedId)
            if (savedId.isNotEmpty()) {
                tvFaceIdStatus.text = getString(R.string.biometric_face_saved, savedId)
            } else {
                tvFaceIdStatus.text = getString(R.string.biometric_faceid_waiting)
            }
            // faceId 由算法分配，不允许手动编辑，避免与本地登记/OSS 映射不一致。
            etFaceId.isFocusable = false
            etFaceId.isFocusableInTouchMode = false
            etFaceId.isCursorVisible = false
            etFaceId.isLongClickable = false
            etFaceId.keyListener = null
            etFaceId.doAfterTextChanged { refreshStepGates() }
            btnFaceFromGallery.setOnClickListener { launchFaceFromGallery() }
            btnFaceRecord.setOnClickListener { launchFaceLiveEnrollment() }
            btnVoiceStart.setOnClickListener { startPcmRecording() }
            btnVoiceStop.setOnClickListener { stopPcmRecordingAndUpload() }
            btnSaveRegistration.setOnClickListener { saveRegistrationBundle() }
            btnViewRegistered.setOnClickListener { BiometricRegisteredRecordsActivity.start(this@BiometricRegisterActivity) }
            tvVoiceStatus.text = getString(R.string.biometric_voice_idle)
            // 默认走自动化动态注册流程；相册入口保留能力但隐藏到当前版本之外
            btnFaceFromGallery.visibility = android.view.View.GONE
            refreshSaveRegistrationStatusText()
            refreshStepGates()
        }
        // 避免在 onCreate 同步阶段做 facedet 原生初始化 / 弹权限，部分机型会崩；首帧后再执行
        mBinding?.root?.post {
            prepareFaceDetectorLikeDemoOnCreate()
            checkPermissions()
        }
    }

    override fun onDestroy() {
        stopLiveEnrollmentIfRunning()
        stopPcmRecordingSync()
        faceEnginePreparedOk = false
        runCatching { faceDetector?.unbindCamera() }
        runCatching { PhotoFaceEnrollment.releaseLandmarker() }
        runCatching { faceDetector?.release() }
        faceDetector = null
        super.onDestroy()
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
        maybeAutoStartLiveEnrollment()
    }

    private fun prepareFaceDetectorLikeDemoOnCreate() {
        mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_init_library)
        faceEnginePreparedOk = false
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
        faceEnginePreparedOk = true
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
        maybeAutoStartLiveEnrollment()
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

    private fun toastRecognitionNotReady(fd: FaceDetector) {
        val why = fd.getLastRecognitionInitFailureMessage()?.trim().orEmpty()
        val msg = if (why.isNotEmpty()) {
            getString(R.string.biometric_recognition_detail, why)
        } else {
            getString(R.string.biometric_recognition_not_ready)
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun launchFaceFromGallery() {
        if (enrollInProgress) return
        val fd = faceDetector ?: run {
            Toast.makeText(this, getString(R.string.biometric_lib_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (fd.multiPersonRecognitionManager == null) {
            toastRecognitionNotReady(fd)
            return
        }
        enrollInProgress = true
        refreshFaceButtonsEnabled()
        mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_pick_video)
        pickVideoLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
        )
    }

    private fun launchFaceRecordVideo() {
        if (enrollInProgress || liveEnrollRunning) {
            mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_live_enroll_already_running)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastLiveEnrollStartAtMs < 1200L) return
        lastLiveEnrollStartAtMs = now
        val fd = ensureLiveEnrollmentDetectorReady() ?: run {
            Toast.makeText(this, getString(R.string.biometric_lib_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        if (fd.isLiveEnrollmentActive()) {
            liveEnrollRunning = true
            enrollInProgress = true
            refreshFaceButtonsEnabled()
            mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_live_enroll_already_running)
            return
        }
        if (fd.multiPersonRecognitionManager == null) {
            toastRecognitionNotReady(fd)
            return
        }
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!camOk) {
            checkPermissions()
            return
        }
        enrollInProgress = true
        liveEnrollRunning = true
        refreshFaceButtonsEnabled()
        mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_live_enroll_starting)
        ProcessCameraProvider.getInstance(this).addListener(
            {
                val provider = runCatching { ProcessCameraProvider.getInstance(this).get() }.getOrNull()
                if (provider == null) {
                    onLiveEnrollmentFailed(getString(R.string.biometric_live_enroll_bind_fail))
                    return@addListener
                }
                try {
                    mBinding?.previewLive?.visibility = View.VISIBLE
                    mBinding?.previewFace?.visibility = View.GONE
                    fd.bindToCameraX(provider, this, this, mBinding?.previewLive)
                    fd.start()
                    fd.startLiveEnrollment(
                        false,
                        object : com.robotchat.facedet.recognition.LiveFaceEnrollment.Callback {
                            override fun onCaptureProgress(captured: Int, total: Int, hint: String) {
                                if (isFinishing || isDestroyed) return
                                mBinding?.tvFaceIdStatus?.text =
                                    getString(R.string.biometric_live_enroll_progress, captured, total, hint)
                            }

                            override fun onEnrollmentSuccess(faceId: String, isNewUser: Boolean, quality: Float) {
                                if (isFinishing || isDestroyed) return
                                liveEnrollRunning = false
                                enrollInProgress = false
                                runCatching { fd.unbindCamera() }
                                mBinding?.previewLive?.visibility = View.GONE
                                mBinding?.previewFace?.visibility = View.VISIBLE
                                mBinding?.etFaceId?.setText(faceId)
                                BiometricSalRegistry.setLastRegisteredFaceId(faceId)
                                // 动态注册仅拿 embedding：先写 local 占位，解锁后续声纹步骤；需要 OSS 可再走相册视频入口。
                                BiometricSalRegistry.saveFaceIdToFaceImageUrl(
                                    faceId,
                                    BiometricSalRegistry.FACE_IMAGE_URL_LOCAL_ONLY,
                                )
                                mBinding?.tvFaceIdStatus?.text =
                                    getString(R.string.biometric_live_enroll_success, faceId, quality)
                                Toast.makeText(
                                    this@BiometricRegisterActivity,
                                    R.string.biometric_live_enroll_success_toast,
                                    Toast.LENGTH_SHORT,
                                ).show()
                                refreshFaceButtonsEnabled()
                                refreshStepGates()
                                maybeAutoStartVoiceCapture()
                            }

                            override fun onEnrollmentError(message: String) {
                                if (isFinishing || isDestroyed) return
                                Log.e(TAG, "Live enrollment callback error: $message")
                                onLiveEnrollmentFailed(message)
                            }
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Live enrollment start exception: ${e.message}", e)
                    onLiveEnrollmentFailed(e.message ?: getString(R.string.biometric_live_enroll_bind_fail))
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun ensureLiveEnrollmentDetectorReady(): FaceDetector? {
        val current = faceDetector
        if (current != null && !current.getConfig().skipLiveMediaPipeFacePipeline) {
            return current
        }
        // 当前 detector 若是“仅注册模式”（skipLive=true），动态注册前切换到可绑定相机的 live 模式。
        runCatching {
            current?.cancelLiveEnrollment()
            current?.unbindCamera()
            current?.release()
        }
        val liveDetector = runCatching {
            FaceDetector(ConvoFacedetDock.configForBiometricLiveEnrollment(this)).apply {
                setCallback(
                    object : FrameAnalysisCallback {
                        override fun onFaceResult(result: FaceResult) {}
                    },
                )
                start()
                prepareEngine(applicationContext)
            }
        }.getOrElse {
            Log.e(TAG, "ensureLiveEnrollmentDetectorReady failed: ${it.message}", it)
            return null
        }
        faceDetector = liveDetector
        faceEnginePreparedOk = true
        return liveDetector
    }

    private fun onLiveEnrollmentFailed(message: String) {
        Log.e(TAG, "onLiveEnrollmentFailed: $message")
        liveEnrollRunning = false
        enrollInProgress = false
        runCatching { faceDetector?.cancelLiveEnrollment() }
        runCatching { faceDetector?.unbindCamera() }
        mBinding?.previewLive?.visibility = View.GONE
        mBinding?.previewFace?.visibility = View.VISIBLE
        mBinding?.tvFaceIdStatus?.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        refreshFaceButtonsEnabled()
    }

    private fun stopLiveEnrollmentIfRunning() {
        if (!liveEnrollRunning) return
        liveEnrollRunning = false
        enrollInProgress = false
        runCatching { faceDetector?.cancelLiveEnrollment() }
        runCatching { faceDetector?.unbindCamera() }
        mBinding?.previewLive?.visibility = View.GONE
        mBinding?.previewFace?.visibility = View.VISIBLE
        mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_live_enroll_cancelled)
        refreshFaceButtonsEnabled()
    }

    private fun launchFaceLiveEnrollment() {
        launchFaceRecordVideo()
    }

    /**
     * 进入注册页后自动开始动态注册（仅在无完整注册数据时触发一次）。
     */
    private fun maybeAutoStartLiveEnrollment() {
        if (autoLiveEnrollAttempted) return
        if (enrollInProgress || liveEnrollRunning) return
        if (!faceEnginePreparedOk) return
        if (BiometricSalRegistry.getCompleteSalFaceIdToPcmUrls().isNotEmpty()) return
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!camOk) return
        val fd = faceDetector ?: return
        if (fd.multiPersonRecognitionManager == null) return
        autoLiveEnrollAttempted = true
        launchFaceLiveEnrollment()
    }

    private fun maybeAutoStartVoiceCapture() {
        if (autoVoiceCaptureScheduled || isRecordingPcm) return
        if (!isStep1Complete()) return
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micOk) return
        autoVoiceCaptureScheduled = true
        mBinding?.tvVoiceStatus?.text = getString(R.string.biometric_auto_voice_countdown)
        mainHandler.postDelayed(
            {
                if (isFinishing || isDestroyed) return@postDelayed
                if (!isStep1Complete() || isRecordingPcm) return@postDelayed
                startPcmRecording()
                mBinding?.tvVoiceStatus?.text = getString(R.string.biometric_auto_voice_recording)
                mainHandler.postDelayed(
                    {
                        if (isFinishing || isDestroyed) return@postDelayed
                        if (isRecordingPcm) {
                            stopPcmRecordingAndUpload()
                        }
                    },
                    AUTO_VOICE_RECORD_MS,
                )
            },
            500L,
        )
    }

    /**
     * 相册/动态注册按钮不可依赖「识别库已就绪」才 enabled：否则 ArcFace/Room 初始化失败时按钮永远灰色、点击无任何反馈。
     * 识别未就绪时仍允许点击，由入口方法内 Toast 说明原因。
     */
    private fun refreshFaceButtonsEnabled() {
        val m = mBinding ?: return
        val engineReady = faceEnginePreparedOk && !enrollInProgress
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        // 相册选视频仅需 SAF，不要求相机/麦克风权限
        m.btnFaceFromGallery.isEnabled = engineReady
        // 动态注册仅需相机
        m.btnFaceRecord.isEnabled = engineReady && camOk
        m.btnFaceRecord.text = if (liveEnrollRunning) {
            getString(R.string.biometric_live_enroll_cancel)
        } else {
            getString(R.string.biometric_face_video_record)
        }
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
        mainHandler.post {
            runCatching {
                captureVideoLauncher.launch(uri)
            }.onFailure { e ->
                Log.e(TAG, "CaptureVideo launch failed", e)
                enrollInProgress = false
                refreshFaceButtonsEnabled()
                mBinding?.tvFaceIdStatus?.text = e.message ?: "CaptureVideo failed"
            }
        }
    }

    /**
     * 将「或录制新视频」得到的缓存 mp4 复制到系统相册（MediaStore），便于之后在相册中再次选用。
     * Android 10+ 写入 DCIM/ConversationalAI；更低版本走 [copyVideoToMediaStoreLegacy]。
     */
    private fun copyCapturedVideoToMediaStore(sourceUri: Uri): Boolean {
        val displayName = "convoai_register_${System.currentTimeMillis()}.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            copyVideoToMediaStoreQ(displayName, sourceUri)
        } else {
            copyVideoToMediaStoreLegacy(displayName, sourceUri)
        }
    }

    private fun copyVideoToMediaStoreQ(displayName: String, sourceUri: Uri): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ConversationalAI")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val outUri = contentResolver.insert(collection, values) ?: return false
        return try {
            contentResolver.openOutputStream(outUri)?.use { out ->
                val input = contentResolver.openInputStream(sourceUri)
                    ?: return false
                input.use { it.copyTo(out) }
            } ?: return false
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(outUri, values, null, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyVideoToMediaStoreQ", e)
            runCatching { contentResolver.delete(outUri, null, null) }
            false
        }
    }

    private fun copyVideoToMediaStoreLegacy(displayName: String, sourceUri: Uri): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        @Suppress("DEPRECATION")
        val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            contentResolver.openOutputStream(outUri)?.use { out ->
                val input = contentResolver.openInputStream(sourceUri)
                    ?: return false
                input.use { it.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyVideoToMediaStoreLegacy", e)
            runCatching { contentResolver.delete(outUri, null, null) }
            false
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
                mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_upload_fail_status)
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
                mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_upload_fail_status)
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
                mBinding?.tvFaceIdStatus?.text = getString(R.string.biometric_face_upload_fail_status)
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

    /** 人脸封面图已写入 OSS 的 http(s) URL 时视为步骤一在 OSS 侧完成 */
    private fun isStep1OssHttpComplete(): Boolean {
        val lastId = BiometricSalRegistry.getLastRegisteredFaceId() ?: return false
        val url = BiometricSalRegistry.getFaceImageHttpUrl(lastId) ?: return false
        return BiometricSalRegistry.isHttpUrl(url)
    }

    /** 声纹 PCM 已写入 OSS 的 http(s) URL 时视为步骤二在 OSS 侧完成 */
    private fun isStep2OssHttpComplete(): Boolean {
        val lastId = BiometricSalRegistry.getLastRegisteredFaceId() ?: return false
        val url = BiometricSalRegistry.getPcmHttpUrl(lastId) ?: return false
        return BiometricSalRegistry.isHttpUrl(url)
    }

    /** 步骤标题下说明：OSS 上传成功后切换为「步骤一/二注册完毕」，否则保持初始说明。 */
    private fun refreshStepSectionHints() {
        val m = mBinding ?: return
        m.tvStep1Hint.text = if (isStep1OssHttpComplete()) {
            getString(R.string.biometric_step1_done_hint)
        } else {
            getString(R.string.biometric_step1_hint)
        }
        m.tvStep2Hint.text = if (isStep2OssHttpComplete()) {
            getString(R.string.biometric_step2_done_hint)
        } else {
            getString(R.string.biometric_voice_hint)
        }
    }

    private fun refreshStepGates() {
        val m = mBinding ?: return
        val step1 = isStep1Complete()
        if (!isRecordingPcm) {
            m.btnVoiceStart.isEnabled = step1
        }
        m.btnSaveRegistration.isEnabled = canPersistRegistrationSnapshotWithOssOnly()
        refreshStepSectionHints()
        syncBiometricDetailStatusLines()
    }

    /**
     * 将步骤 1/2 下方状态行与 SP 中 OSS 结果对齐，避免 OSS 已成功仍停留在「正在上传人脸图…」等中间态。
     */
    private fun syncBiometricDetailStatusLines() {
        val m = mBinding ?: return
        val faceId = BiometricSalRegistry.getLastRegisteredFaceId()?.trim().orEmpty()
        if (faceId.isNotEmpty()) {
            if (isStep1OssHttpComplete()) {
                m.tvFaceIdStatus.text = getString(R.string.biometric_face_status_line_oss_done, faceId)
            } else if (!enrollInProgress &&
                BiometricSalRegistry.getFaceImageHttpUrl(faceId) == BiometricSalRegistry.FACE_IMAGE_URL_LOCAL_ONLY
            ) {
                m.tvFaceIdStatus.text = getString(R.string.biometric_face_status_line_local, faceId)
            }
        }
        if (faceId.isEmpty()) return
        if (isStep2OssHttpComplete()) {
            if (!isRecordingPcm) {
                m.tvVoiceStatus.text = getString(R.string.biometric_voice_status_line_oss_done)
            }
        } else if (!isRecordingPcm &&
            BiometricSalRegistry.getPcmHttpUrl(faceId) == BiometricSalRegistry.PCM_URL_LOCAL_ONLY
        ) {
            m.tvVoiceStatus.text = getString(R.string.biometric_voice_status_line_local)
        }
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

    /**
     * 自动流程兜底：即便未拿到双 OSS（例如 face 动态注册仅 local://），也在步骤1+2完成时落本地快照并结束页面。
     */
    private fun persistRegistrationSnapshotIfLocalComplete(): Boolean {
        val faceId = BiometricSalRegistry.getLastRegisteredFaceId() ?: return false
        val faceUrl = BiometricSalRegistry.getFaceImageHttpUrl(faceId) ?: return false
        val pcmUrl = BiometricSalRegistry.getPcmHttpUrl(faceId) ?: return false
        val faceOk = BiometricSalRegistry.isHttpUrl(faceUrl) || faceUrl == BiometricSalRegistry.FACE_IMAGE_URL_LOCAL_ONLY
        val pcmOk = BiometricSalRegistry.isHttpUrl(pcmUrl) || pcmUrl == BiometricSalRegistry.PCM_URL_LOCAL_ONLY
        if (!faceOk || !pcmOk) return false
        val snapshot = BiometricRegistrationSnapshot(
            faceId = faceId,
            faceImageOssUrl = faceUrl,
            pcmOssUrl = pcmUrl,
            savedAtEpochMs = System.currentTimeMillis(),
        )
        BiometricSalRegistry.setLastRegisteredFaceId(faceId)
        val json = BiometricSalRegistry.saveRegistrationSnapshot(snapshot)
        refreshSaveRegistrationStatusText()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val f = File(workDir(), "biometric_registration.json")
                f.writeText(json)
            }
        }
        return true
    }

    private fun finishRegistrationFlowIfReady() {
        if (persistRegistrationSnapshotIfHttpCompleteAfterOss() || persistRegistrationSnapshotIfLocalComplete()) {
            Toast.makeText(this, R.string.biometric_register_flow_done, Toast.LENGTH_SHORT).show()
            finish()
        }
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
                finishRegistrationFlowIfReady()
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
                mBinding?.tvVoiceStatus?.text = getString(R.string.biometric_voice_idle)
                refreshStepGates()
                return@launch
            }
            if (upload.ok && !upload.url.isNullOrEmpty()) {
                BiometricSalRegistry.saveFaceIdToPcmUrl(faceKey, upload.url!!)
                refreshStepGates()
                finishRegistrationFlowIfReady()
            } else {
                Toast.makeText(
                    this@BiometricRegisterActivity,
                    messageForOssUploadFailure(upload),
                    Toast.LENGTH_LONG,
                ).show()
                mBinding?.tvVoiceStatus?.text = getString(R.string.biometric_voice_idle)
                refreshStepGates()
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
