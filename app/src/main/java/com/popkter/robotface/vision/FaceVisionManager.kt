package com.popkter.robotface.vision

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * 视觉模块管理器
 *
 * 职责：
 * 1. 管理 CameraX 生命周期（前置摄像头，无预览）
 * 2. 串联 FaceDetectorProcessor → FaceTracker
 * 3. 通过回调输出跟踪后的人脸数据
 * 4. 状态机：检测人脸出现/消失事件，带防抖
 *
 * @param context         应用上下文
 * @param lifecycleOwner  生命周期所有者（通常是 Activity）
 * @param onFacesUpdated  每帧跟踪结果回调（在分析线程）
 * @param faceEventListener 人脸状态事件监听器（在主线程回调，可为 null）
 */
class FaceVisionManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFacesUpdated: (List<FaceInfo>) -> Unit,
    private val faceEventListener: FaceEventListener? = null
) {

    companion object {
        private const val TAG = "FaceVisionManager"

        /** 人脸消失防抖时间 (ms)：超过此时间未检测到人脸才触发 FACE_LOST */
        private const val FACE_LOST_DEBOUNCE_MS = 3000L

        /** 看门狗超时 (ms)：分析器超过此时间无输出，强制触发 FACE_LOST */
        private const val WATCHDOG_TIMEOUT_MS = 5000L

        /** 看门狗检查间隔 (ms) */
        private const val WATCHDOG_INTERVAL_MS = 2000L
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var faceTracker: FaceTracker? = null
    private var detectorProcessor: FaceDetectorProcessor? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isTracking = false

    // ==================== 状态机 ====================
    private var hadFaceInLastUpdate = false
    private var lastFaceSeenTimeMs = 0L
    private var faceLostNotified = true  // 初始为 true，避免启动时误触发

    // ==================== 看门狗 ====================
    private var lastAnalyzerCallbackTimeMs = 0L
    private var watchdogRunning = false

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (lastAnalyzerCallbackTimeMs > 0 && now - lastAnalyzerCallbackTimeMs > WATCHDOG_TIMEOUT_MS) {
                // 分析器长时间无输出，强制触发 FACE_LOST
                if (hadFaceInLastUpdate && !faceLostNotified) {
                    hadFaceInLastUpdate = false
                    faceLostNotified = true
                    Log.w(TAG, "⚠️ 看门狗：分析器超时 ${WATCHDOG_TIMEOUT_MS}ms 无输出，强制触发人脸消失")
                    notifyEvent(FaceEvent.FACE_LOST, emptyList())
                }
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val defaultResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()

    /** 启动视觉模块：初始化 CameraProvider 并绑定摄像头 */
    fun start() {
        if (isTracking) return

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera()
                isTracking = true
                startWatchdog()
                Log.d(TAG, "视觉模块启动")
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider 初始化失败: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 启动看门狗：定期检查分析器是否还在工作 */
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        lastAnalyzerCallbackTimeMs = System.currentTimeMillis()
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    /** 绑定前置摄像头 + ImageAnalysis */
    private fun bindCamera() {
        val provider = cameraProvider ?: return

        // 创建检测器，每帧检测到人脸后交给 Tracker
        detectorProcessor = FaceDetectorProcessor { faces ->
            faceTracker?.update(faces)
        }

        // ImageAnalysis 配置：4:3 比例，丢帧策略保证实时性
        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(defaultResolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        // 解绑所有已有用例
        provider.unbindAll()

        // 绑定到生命周期：仅 ImageAnalysis，无预览
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            imageAnalysis
        )

        // 设置分析器
        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            // 首帧时初始化 Tracker（需要知道实际分辨率）
            if (faceTracker == null) {
                faceTracker = FaceTracker(
                    imageWidth = imageProxy.width,
                    imageHeight = imageProxy.height,
                    onTrackedFaces = { faces -> handleTrackedFaces(faces) }
                )
                Log.d(TAG, "FaceTracker 初始化: ${imageProxy.width}x${imageProxy.height}")
            }
            detectorProcessor?.analyze(imageProxy)
        }

        Log.d(TAG, "CameraX 绑定完成 (前置摄像头, 无预览)")
    }

    /** 处理 Tracker 输出的跟踪结果 + 状态机 */
    private fun handleTrackedFaces(faces: List<FaceInfo>) {
        // 更新看门狗时间戳
        lastAnalyzerCallbackTimeMs = System.currentTimeMillis()

        // 回调每帧数据
        onFacesUpdated(faces)

        val now = System.currentTimeMillis()
        val hasFace = faces.isNotEmpty()

        if (hasFace) {
            lastFaceSeenTimeMs = now
            faceLostNotified = false

            if (!hadFaceInLastUpdate) {
                // 状态转换：无人脸 → 有人脸
                hadFaceInLastUpdate = true
                Log.d(TAG, "🟢 人脸出现: count=${faces.size}")
                notifyEvent(FaceEvent.FACE_APPEARED, faces)
            }
        } else if (hadFaceInLastUpdate && !faceLostNotified) {
            // 有人脸 → 无人脸，检查防抖
            if (now - lastFaceSeenTimeMs >= FACE_LOST_DEBOUNCE_MS) {
                hadFaceInLastUpdate = false
                faceLostNotified = true
                Log.d(TAG, "🔴 人脸消失 (防抖 ${FACE_LOST_DEBOUNCE_MS}ms)")
                notifyEvent(FaceEvent.FACE_LOST, emptyList())
            }
        }
    }

    /** 在主线程回调事件监听器 */
    private fun notifyEvent(event: FaceEvent, faces: List<FaceInfo>) {
        faceEventListener?.let { listener ->
            mainHandler.post { listener.onFaceEvent(event, faces) }
        }
    }

    /** 停止视觉模块 */
    fun stop() {
        isTracking = false
        watchdogRunning = false
        mainHandler.removeCallbacks(watchdogRunnable)
        cameraProvider?.unbindAll()
        detectorProcessor?.close()
        faceTracker?.reset()
        hadFaceInLastUpdate = false
        faceLostNotified = true
        Log.d(TAG, "视觉模块停止")
    }

    /** 销毁资源 */
    fun destroy() {
        stop()
        analysisExecutor.shutdown()
    }
}
