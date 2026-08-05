package com.popkter.robotface.vision

/**
 * 人脸状态事件
 */
enum class FaceEvent {
    /** 首次检测到人脸（从无人脸 → 有人脸） */
    FACE_APPEARED,
    /** 人脸消失超过防抖时间（从有人脸 → 无人脸） */
    FACE_LOST
}

/**
 * 人脸事件监听器
 */
interface FaceEventListener {
    /** 人脸状态变化（在主线程回调） */
    fun onFaceEvent(event: FaceEvent, faces: List<FaceInfo>)
}

/**
 * 单张人脸的跟踪信息
 *
 * @property trackingId   ML Kit 分配的稳定跟踪 ID，跨帧不变
 * @property offsetX      人脸中心相对画面中心的水平归一化偏移 (-1.0 ~ 1.0)
 * @property offsetY      人脸中心相对画面中心的垂直归一化偏移 (-1.0 ~ 1.0)
 * @property distanceMeters 基于 bounding box 估算的距离（米）
 * @property boundingBoxWidth  检测框宽度（像素）
 * @property boundingBoxHeight 检测框高度（像素）
 */
data class FaceInfo(
    val trackingId: Int,
    val offsetX: Float,
    val offsetY: Float,
    val distanceMeters: Float,
    val boundingBoxWidth: Float,
    val boundingBoxHeight: Float
)
