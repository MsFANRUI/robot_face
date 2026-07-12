package com.popkter.robotface.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

class ContinuousAudioRecorder(
    private val onAudioChunk: (ByteArray) -> Unit
) {
    private var recorder: AudioRecord? = null
    private var isRecording = false

    // ==================== 录音模式 ====================
    // NORMAL: 录音 → 发给 ASR（正常对话）
    // MONITOR: 录音 → 只回调音量，不发给 ASR（TTS 播放期间，用于检测用户打断）
    enum class Mode { NORMAL, MONITOR }
    @Volatile
    private var mode: Mode = Mode.NORMAL
    private var onVolumeCallback: ((Float) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // 检查 AudioRecord 是否初始化成功
        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            recorder = null
            android.util.Log.e("ContinuousAudioRecorder", "AudioRecord 初始化失败，请检查麦克风权限")
            return
        }

        isRecording = true
        recorder?.startRecording()

        Thread {
            val buffer = ByteArray(bufferSize)
            var logCounter = 0
            while (isRecording) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val pcmData = buffer.copyOf(read)
                    // 计算归一化音量 (0.0~1.0)
                    val volume = calculateNormalizedVolume(pcmData)

                    // 根据模式决定行为
                    when (mode) {
                        Mode.NORMAL -> {
                            // 正常模式：发送音频给 ASR
                            onAudioChunk(pcmData)
                            // 每 20 帧输出一次音量日志（约每秒 3 次）
                            if (++logCounter % 20 == 0) {
                                Log.d("AudioVolume", "volume=${"%.3f".format(volume)}")
                            }
                        }
                        Mode.MONITOR -> {
                            // 监控模式：只回调音量，不发给 ASR
                            onVolumeCallback?.invoke(volume)
                        }
                    }
                }
            }
        }.start()
    }

    /**
     * 计算 PCM 16bit 归一化音量，范围 0.0~1.0
     * 与 VAD threshold 参数同尺度，可直接对比
     */
    private fun calculateNormalizedVolume(pcmData: ByteArray): Float {
        var sum = 0.0
        val sampleCount = pcmData.size / 2
        if (sampleCount == 0) return 0f
        for (i in 0 until sampleCount) {
            val sample = (pcmData[i * 2 + 1].toInt() shl 8) or (pcmData[i * 2].toInt() and 0xFF)
            val normalized = sample.toShort().toDouble() / 32768.0
            sum += normalized * normalized
        }
        // RMS (Root Mean Square) 归一化到 0~1
        return Math.sqrt(sum / sampleCount).toFloat()
    }

    // ==================== 模式切换方法 ====================
    /**
     * 开始监控模式（TTS 播放期间使用）
     * 录音线程继续运行，但不发送音频给 ASR，只回调音量值
     * 用于检测用户是否在 TTS 播放时说话（打断检测）
     *
     * @param onVolume 音量回调，参数为归一化音量 (0.0~1.0)
     */
    fun startMonitoring(onVolume: (Float) -> Unit) {
        onVolumeCallback = onVolume
        mode = Mode.MONITOR
        Log.d("ContinuousAudioRecorder", "切换到 MONITOR 模式")
    }

    /**
     * 停止监控模式，切回正常模式
     * 录音数据会重新发送给 ASR
     */
    fun stopMonitoring() {
        onVolumeCallback = null
        mode = Mode.NORMAL
        Log.d("ContinuousAudioRecorder", "切换到 NORMAL 模式")
    }

    fun stopRecording() {
        isRecording = false
        recorder?.stop()
        recorder?.release()
        recorder = null
    }
}
