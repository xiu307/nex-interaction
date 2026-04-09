package ai.nex.interaction.video

import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import io.agora.rtc2.video.AgoraVideoFrame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class CameraVideoInputManager(
    private val activity: ComponentActivity,
    private val onFrameAvailable: (AgoraVideoFrame) -> Unit
) {

    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private val resolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(
            AspectRatioStrategy(
                AspectRatio.RATIO_4_3,
                AspectRatioStrategy.FALLBACK_RULE_AUTO
            )
        )
        .setResolutionStrategy(
            ResolutionStrategy(
                Size(640, 480),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
            )
        )
        .build()

    /**
     * 启动本地相机预览与视频帧采集。
     *
     * 该方法会绑定前置摄像头到 Activity 生命周期，同时建立 Preview 与
     * ImageAnalysis 链路；分析到的帧会通过构造参数中的回调抛给业务层。
     *
     * @param previewView 用于展示本地预览画面的视图
     */
    fun start(previewView: PreviewView) {
        previewView.scaleX = -1f
        cameraProviderFuture.addListener(
            {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                previewUseCase = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build()
                    .also { preview ->
                        preview.surfaceProvider = previewView.surfaceProvider
                    }

                analysisUseCase = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { image ->
                            analyzeFrame(image)
                        }
                    }

                provider.unbindAll()
                provider.bindToLifecycle(
                    activity,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    previewUseCase,
                    analysisUseCase
                )
            },
            mainExecutor
        )
    }

    /**
     * 停止本地相机预览与帧分析。
     *
     * 调用后会解除当前 CameraX use case 绑定，并清理预览和分析链路，
     * 但不会销毁分析线程本身。
     */
    fun stop() {
        analysisUseCase?.clearAnalyzer()
        cameraProvider?.unbindAll()
        previewUseCase = null
        analysisUseCase = null
    }

    /**
     * 彻底释放采集管理器资源。
     *
     * 除了停止预览与分析外，还会关闭内部单线程执行器，适合在 Activity
     * 或页面生命周期结束时调用。
     */
    fun release() {
        stop()
        analysisExecutor.shutdown()
    }

    private fun analyzeFrame(image: ImageProxy) {
        val plane = image.planes.firstOrNull()
        if (plane == null) {
            image.close()
            return
        }
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val pixelStride = plane.pixelStride.takeIf { it > 0 } ?: 4
        val stridePixels = plane.rowStride / pixelStride
        val frame = AgoraVideoFrame().apply {
            format = AgoraVideoFrame.FORMAT_RGBA
            buf = bytes
            stride = stridePixels
            height = image.height
            cropRight = max(0, stridePixels - image.width)
            rotation = image.imageInfo.rotationDegrees
            timeStamp = System.currentTimeMillis()
        }
        onFrameAvailable.invoke(frame)
        image.close()
    }
}
