package com.popkter.robotface.vision

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * CameraX ImageAnalysis 分析器
 *
 * 接收每帧图像 → 送入 ML Kit 人脸检测 → 回调检测结果
 * 启用 tracking 模式，ML Kit 会为每张人脸分配稳定的 trackingId
 */
class FaceDetectorProcessor(
    private val onFacesDetected: (List<Face>) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "FaceDetectorProcessor"

        /** ML Kit 处理超时 (ms)：超时后强制关闭 imageProxy，防止分析器卡死 */
        private const val PROCESS_TIMEOUT_MS = 2000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .enableTracking()
            .build()
    )

    @Volatile
    private var closed = false

    /** 帧计数（诊断用） */
    private var frameCount = 0L
    private var lastFrameLogTime = 0L

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        closed = false
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFrameLogTime > 3000) {
            Log.d(TAG, "分析器运行中: ${frameCount}帧已处理, 当前帧 ${imageProxy.width}x${imageProxy.height} rotation=${imageProxy.imageInfo.rotationDegrees}")
            lastFrameLogTime = now
        }
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        // 超时保护：ML Kit 卡住时强制关闭 imageProxy，防止分析器死锁
        val timeoutRunnable = Runnable {
            if (!closed) {
                closed = true
                Log.w(TAG, "ML Kit 处理超时 (${PROCESS_TIMEOUT_MS}ms)，强制关闭 imageProxy")
                try { imageProxy.close() } catch (_: Exception) {}
            }
        }
        mainHandler.postDelayed(timeoutRunnable, PROCESS_TIMEOUT_MS)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                // 过滤假人脸（摄像头遮挡时 ML Kit 会误识别手/布料为脸）
                val imgW = imageProxy.width
                val imgH = imageProxy.height
                Log.d(TAG, "ML Kit 检测完成: 原始${faces.size}张脸, imgW=$imgW imgH=$imgH")
                val validFaces = faces.filter { face -> isValidFace(face, imgW, imgH) }
                if (validFaces.isNotEmpty()) {
                    val desc = validFaces.joinToString { f -> "cx=${f.boundingBox.centerX()} cy=${f.boundingBox.centerY()}" }
                    Log.d(TAG, "有效人脸: ${validFaces.size}张 | $desc")
                    onFacesDetected(validFaces)
                } else if (faces.isNotEmpty()) {
                    Log.d(TAG, "所有脸被过滤: ${faces.map { "cx=${it.boundingBox.centerX()} w=${it.boundingBox.width()}" }}")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "检测失败: ${e.message}")
            }
            .addOnCompleteListener {
                mainHandler.removeCallbacks(timeoutRunnable)
                if (!closed) {
                    closed = true
                    imageProxy.close()
                }
            }
    }

    /**
     * 过滤假人脸：
     * 1. bounding box 中心太靠近画面边缘（< 10%）→ 假
     * 2. bounding box 太小（宽或高 < 30px）→ 假
     * 3. bounding box 太大（宽或高 > 画面 80%）→ 假
     */
    private fun isValidFace(face: Face, imgW: Int, imgH: Int): Boolean {
        val box: Rect = face.boundingBox
        val cx = box.centerX().toFloat()
        val cy = box.centerY().toFloat()
        val bw = box.width().toFloat()
        val bh = box.height().toFloat()

        // 中心太靠近边缘
        val marginX = imgW * 0.10f
        val marginY = imgH * 0.10f
        if (cx < marginX || cx > imgW - marginX || cy < marginY || cy > imgH - marginY) {
            return false
        }

        // 太小
        if (bw < 30f || bh < 30f) return false

        // 太大（超过画面 80%）
        if (bw > imgW * 0.80f || bh > imgH * 0.80f) return false

        return true
    }

    fun close() {
        detector.close()
    }
}
