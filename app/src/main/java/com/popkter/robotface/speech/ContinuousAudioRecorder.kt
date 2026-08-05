package com.popkter.robotface.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log

/**
 * 持续录音器
 *
 * 职责单一：持续采集麦克风 PCM 数据并回调。
 * TTS 播放期间也不停止 —— 唤醒词打断依赖 ASR 持续接收音频。
 * 回声消除由 ReSpeaker 硬件 AEC 或系统 AcousticEchoCanceler 处理。
 */
class ContinuousAudioRecorder(
    private val context: Context,
    private val onAudioChunk: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
    }

    private var recorder: AudioRecord? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        Log.d(TAG, "═══════ 录音初始化 ═══════")
        Log.d(TAG, "  采样率: $SAMPLE_RATE Hz")
        Log.d(TAG, "  缓冲区: $bufferSize bytes")

        // ★ 优先查找 USB 麦克风（ReSpeaker Mic Array）
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val usbDevice = inputDevices.find {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

        Log.d(TAG, "  找到 ${inputDevices.size} 个输入设备:")
        inputDevices.forEach { d ->
            Log.d(TAG, "    - ${d.productName} type=${d.type} (${typeToName(d.type)})")
        }

        // 使用传统构造函数（AudioRecord.Builder.setPreferredDevice 在某些 API 级别不可用）
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (usbDevice != null) {
            Log.d(TAG, "  ✅ 发现 USB 麦克风: ${usbDevice.productName} (id=${usbDevice.id})")
            try {
                val success = recorder!!.setPreferredDevice(usbDevice)
                Log.d(TAG, "  setPreferredDevice 结果: $success")
            } catch (e: Exception) {
                Log.w(TAG, "  ⚠️ setPreferredDevice 异常: ${e.message}")
            }
        } else {
            Log.w(TAG, "  ⚠️ 未找到 USB 麦克风，使用默认麦克风")
        }

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            recorder = null
            Log.e(TAG, "  ❌ AudioRecord 初始化失败，请检查麦克风权限")
            return
        }
        Log.d(TAG, "  AudioRecord 状态: INITIALIZED ✅")
        Log.d(TAG, "  audioSessionId: ${recorder?.audioSessionId}")

        // ★ 仅在非 USB 麦克风时启用系统 AEC 兜底
        // ReSpeaker USB 麦克风已在 DSP 硬件层面完成 AEC，无需系统再处理
        val isUsingUSBMic = usbDevice != null
        try {
            if (!isUsingUSBMic && AcousticEchoCanceler.isAvailable()) {
                val aec = AcousticEchoCanceler.create(recorder!!.audioSessionId)
                aec.enabled = true
                Log.d(TAG, "  ✅ 系统 AEC 回声消除: 已启用（内置麦克风模式）")
            } else if (isUsingUSBMic) {
                Log.d(TAG, "  ℹ️ 使用 USB 麦克风，硬件 AEC 已处理，跳过系统 AEC")
            } else {
                Log.w(TAG, "  ⚠️ AEC 设备不可用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "  ❌ AEC 创建失败: ${e.message}")
        }

        isRecording = true
        recorder?.startRecording()
        Log.d(TAG, "  🎤 录音线程已启动")

        Thread {
            val buffer = ByteArray(bufferSize)
            var frameIndex = 0L
            var logCounter = 0
            while (isRecording) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    frameIndex++
                    val pcmData = buffer.copyOf(read)
                    onAudioChunk(pcmData)
                    // 每 50 帧输出一次日志（约每秒 1.5 次）
                    if (++logCounter % 50 == 0) {
                        Log.d(TAG, "  [录音中] 帧#$frameIndex")
                    }
                } else if (read < 0) {
                    Log.w(TAG, "  ⚠️ 帧#$frameIndex read=$read")
                }
            }
            Log.d(TAG, "  录音线程退出")
        }.apply { isDaemon = true }.start()
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording()")
        isRecording = false
        recorder?.stop()
        recorder?.release()
        recorder = null
    }

    /** 设备类型名称映射（调试用） */
    private fun typeToName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        else -> "TYPE_$type"
    }
}
