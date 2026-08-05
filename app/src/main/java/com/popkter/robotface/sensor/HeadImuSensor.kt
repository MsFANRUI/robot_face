package com.popkter.robotface.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.atan2

/**
 * 头部 IMU 传感器封装 —— 【确定性版·v6 旋转矢量+正确轴映射】
 *
 * 手机竖屏装在机器人头上(短边左右,长边上下,屏幕朝前)。
 *
 * 坐标轴映射(竖屏·屏幕朝前):
 * - 手机 X 轴 → 左右(水平) → roll(不关心)
 * - 手机 Y 轴 → 前方(屏幕朝向) → yaw(左右转头)
 * - 手机 Z 轴 → 上方(长边方向) → pitch(点头/抬头)
 *
 * 安装旋转关系: 相对旧竖屏前向安装,绕Z轴旋转90°
 * → 旧 roll(orientation[2]) = 新 pitch
 * → 旧 yaw(orientation[0]) = 新 yaw
 * → 旧 pitch(orientation[1]) = 新 roll
 *
 * 核心:TYPE_ROTATION_VECTOR(内置卡尔曼滤波)
 * - pitch = orientation[2] (旧roll,对应新安装的点头轴)
 * - yaw = 手机Y轴(前方)在水平面的投影角度(绝对磁北参考,同位置值确定)
 * - roll = 固定0
 */
class HeadImuSensor(
    context: Context,
    private val intervalMs: Long = 100L,
    private val onImu: (gx: Float, gy: Float, gz: Float, rx: Float, ry: Float, rz: Float) -> Unit
) {
    companion object {
        private const val TAG = "HeadImuSensor"
        private const val RAD2DEG = 57.29578f  // 180/PI
        private const val LOG_EVERY_TICKS = 10 // 约每秒打一条(100ms×10)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotVecSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // 临时数组,避免每次分配
    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val mainHandler = Handler(Looper.getMainLooper())

    // 缓存值(主线程读写)。
    @Volatile private var gx = 0f
    @Volatile private var gy = 0f
    @Volatile private var gz = 0f
    // rx=yaw(磁北参考), ry=pitch(旋转矢量roll), rz=roll(固定0)
    @Volatile private var rx = 0f
    @Volatile private var ry = 0f
    @Volatile private var rz = 0f

    private var running = false
    private var tickCount = 0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    gx = event.values[0]
                    gy = event.values[1]
                    gz = event.values[2]
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    // 旋转矢量 → 旋转矩阵 → 欧拉角
                    SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                    SensorManager.getOrientation(rotMatrix, orientation)
                    // orientation[0]=azimuth, [1]=pitch(绕X), [2]=roll(绕Y)
                    
                    // 新安装(短边左右,长边上下,屏幕朝前)的映射:
                    // 旧 roll(orientation[2]) = 新 pitch(点头)
                    // 旧 pitch(orientation[1]) = 新 yaw(左右转头)
                    val pitchDeg = orientation[2] * RAD2DEG
                    ry = pitchDeg
                    val yawDeg = orientation[1] * RAD2DEG
                    rx = yawDeg
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // 精度变化时可选择性记录
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            onImu(gx, gy, gz, rx, ry, rz)
            if (++tickCount % LOG_EVERY_TICKS == 0) {
                Log.d(
                    TAG,
                    "gyro=(%.4f, %.4f, %.4f) rad/s | pitch=%.2f° yaw=%.2f° (旋转矢量)"
                        .format(gx, gy, gz, ry, rx)
                )
            }
            mainHandler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        if (gyroSensor != null) {
            sensorManager.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Log.w(TAG, "设备无陀螺仪(TYPE_GYROSCOPE),gyro 恒为 0")
        }
        if (rotVecSensor != null) {
            sensorManager.registerListener(listener, rotVecSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            Log.w(TAG, "设备无旋转矢量传感器(TYPE_ROTATION_VECTOR),pitch 恒为 0")
        }
        mainHandler.postDelayed(ticker, intervalMs)
        Log.d(TAG, "start【确定性版·v6】(interval=${intervalMs}ms) pitch=orientation[2], yaw=orientation[1]")
    }

    fun stop() {
        running = false
        sensorManager.unregisterListener(listener)
        mainHandler.removeCallbacks(ticker)
        Log.d(TAG, "stop")
    }
}
