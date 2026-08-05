package com.popkter.robotface.vision

import android.util.Log
import com.google.mlkit.vision.face.Face

/**
 * 人脸跟踪器
 *
 * 职责：
 * 1. 利用 ML Kit 的 trackingId 做跨帧 ID 匹配
 * 2. EMA 平滑偏移量和距离，减少抖动
 * 3. 基于 bounding box 宽度 + 焦距估算距离
 * 4. 超时未出现的人脸自动移除
 */
class FaceTracker(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val onTrackedFaces: (List<FaceInfo>) -> Unit
) {

    companion object {
        private const val TAG = "FaceTracker"

        /** 超过此时间未出现的人脸将被移除 (ms) */
        private const val LOST_TIMEOUT_MS = 500L

        /** EMA 平滑系数，越小越平滑 */
        private const val SMOOTH_ALPHA = 0.3f

        /** 真实人脸平均宽度 (cm)，用于距离估算 */
        private const val REAL_FACE_WIDTH_CM = 15f

        /** 默认等效焦距 (像素)，可从相机参数获取更精确值 */
        private const val DEFAULT_EFFECTIVE_FOCAL_LENGTH = 600f
    }

    /** 内部跟踪状态 */
    private data class TrackedFace(
        val id: Int,
        var smoothedOffsetX: Float,
        var smoothedOffsetY: Float,
        var smoothedDistance: Float,
        var lastSeenMs: Long
    )

    private val trackedFaces = mutableMapOf<Int, TrackedFace>()
    private var effectiveFocalLength: Float = DEFAULT_EFFECTIVE_FOCAL_LENGTH

    /** 日志节流：每 500ms 最多打印一次 */
    private var lastLogTimeMs = 0L
    private val LOG_INTERVAL_MS = 500L

    /** 设置相机焦距（像素），用于更精确的距离估算 */
    fun setCameraFocalLength(focalLengthPx: Float) {
        effectiveFocalLength = focalLengthPx
        Log.d(TAG, "设置焦距: ${focalLengthPx}px")
    }

    /**
     * 每帧更新：传入 ML Kit 检测到的人脸列表
     *
     * 流程：
     * 1. 遍历每张脸，计算原始偏移和距离
     * 2. 匹配已有 trackingId → EMA 平滑 / 新建跟踪
     * 3. 移除超时未出现的人脸
     * 4. 回调输出稳定后的 FaceInfo 列表
     */
    fun update(faces: List<Face>) {
        val now = System.currentTimeMillis()

        for (face in faces) {
            val mlKitId = face.trackingId ?: continue
            val boundingBox = face.boundingBox

            val centerX = boundingBox.centerX().toFloat()
            val centerY = boundingBox.centerY().toFloat()

            // 归一化偏移：人脸中心相对画面中心，范围 -1.0 ~ 1.0
            val rawOffsetX = (centerX - imageWidth / 2f) / (imageWidth / 2f)
            val rawOffsetY = (centerY - imageHeight / 2f) / (imageHeight / 2f)

            val rawDistance = estimateDistance(boundingBox.width(), boundingBox.height())

            val existing = trackedFaces[mlKitId]
            if (existing != null) {
                // EMA 平滑
                existing.smoothedOffsetX = ema(rawOffsetX, existing.smoothedOffsetX, SMOOTH_ALPHA)
                existing.smoothedOffsetY = ema(rawOffsetY, existing.smoothedOffsetY, SMOOTH_ALPHA)
                existing.smoothedDistance = ema(rawDistance, existing.smoothedDistance, SMOOTH_ALPHA)
                existing.lastSeenMs = now
            } else {
                trackedFaces[mlKitId] = TrackedFace(
                    id = mlKitId,
                    smoothedOffsetX = rawOffsetX,
                    smoothedOffsetY = rawOffsetY,
                    smoothedDistance = rawDistance,
                    lastSeenMs = now
                )
                Log.d(TAG, "新人脸入跟踪: id=$mlKitId")
            }
        }

        // 移除超时未出现的人脸
        val expiredIds = trackedFaces.entries
            .filter { now - it.value.lastSeenMs > LOST_TIMEOUT_MS }
            .map { it.key }
        expiredIds.forEach { id ->
            trackedFaces.remove(id)
            Log.d(TAG, "人脸移除: id=$id")
        }

        // 输出当前所有跟踪中的人脸
        val result = trackedFaces.values.map { tracked ->
            FaceInfo(
                trackingId = tracked.id,
                offsetX = tracked.smoothedOffsetX,
                offsetY = tracked.smoothedOffsetY,
                distanceMeters = tracked.smoothedDistance,
                boundingBoxWidth = 0f,
                boundingBoxHeight = 0f
            )
        }

        // 节流打印人脸跟踪数据
        if (now - lastLogTimeMs >= LOG_INTERVAL_MS) {
            lastLogTimeMs = now
            if (result.isNotEmpty()) {
                val desc = result.joinToString("; ") { f ->
                    "id=${f.trackingId} x=${"%.2f".format(f.offsetX)} y=${"%.2f".format(f.offsetY)} d=${"%.2f".format(f.distanceMeters)}m"
                }
                Log.d(TAG, "人脸数据: count=${result.size} | $desc")
                // 打印原始像素坐标用于诊断
                for (face in faces) {
                    val mlKitId = face.trackingId ?: continue
                    val box = face.boundingBox
                    Log.d(TAG, "  原始坐标: id=$mlKitId centerX=${box.centerX()} centerY=${box.centerY()} w=${box.width()} h=${box.height()} | imgW=$imageWidth imgH=$imageHeight")
                }
            } else {
                Log.d(TAG, "人脸数据: 无跟踪中的人脸")
            }
        }

        onTrackedFaces(result)
    }

    /**
     * 距离估算
     *
     * 原理：相似三角形
     *   distance = (real_face_width × focal_length) / apparent_width_in_pixels
     *
     * @return 估算距离（米）
     */
    private fun estimateDistance(boxWidthPx: Int, boxHeightPx: Int): Float {
        val apparentWidth = boxWidthPx.toFloat()
        if (apparentWidth < 1f) return Float.MAX_VALUE
        val distanceCm = (REAL_FACE_WIDTH_CM * effectiveFocalLength) / apparentWidth
        return distanceCm / 100f
    }

    /** 指数移动平均 (EMA) */
    private fun ema(newValue: Float, oldValue: Float, alpha: Float): Float {
        return alpha * newValue + (1f - alpha) * oldValue
    }

    /** 重置所有跟踪状态 */
    fun reset() {
        trackedFaces.clear()
    }
}
