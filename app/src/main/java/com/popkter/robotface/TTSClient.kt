package com.popkter.robotface

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TTS 语音合成客户端（双路音频输出版）
 *
 * 职责：
 * 1. 将文本发送给百炼 CosyVoice API 合成语音（PCM 格式）
 * 2. 获取合成后的音频 URL 并下载
 * 3. 通过双 AudioTrack 同时播放：
 *    - AudioTrack 1: 手机内置扬声器（让人听到）
 *    - AudioTrack 2: ReSpeaker USB（给 DSP 做 AEC 参考信号）
 */
class TTSClient(
    private val apiKey: String,
    private val context: Context? = null  // ★ 用于查找 USB 音频设备
) {
    companion object {
        private const val TAG = "TTS"
        private const val TTS_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
        private const val SAMPLE_RATE = 24000
        // 清理文本中的 emoji 和特殊字符
        private val EMOJI_REGEX = Regex("[\\p{So}\\p{Sk}\\p{Sc}\\p{Sm}]")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentJob: Job? = null
    @Volatile
    private var currentCallback: TTSCallback? = null

    // ★ 双路 AudioTrack
    @Volatile
    private var speakerTrack: AudioTrack? = null   // 手机扬声器
    @Volatile
    private var refTrack: AudioTrack? = null       // ReSpeaker USB（AEC 参考）
    @Volatile
    private var isPlaying = false

    interface TTSCallback {
        fun onStart()
        fun onComplete()
        fun onError(message: String)
    }

    // ==================== 核心方法 ====================

    fun speak(text: String, callback: TTSCallback) {
        val cleanText = text.replace(EMOJI_REGEX, "").trim()
        if (cleanText.isBlank()) {
            Log.d(TAG, "文本为空或只有 emoji，跳过 TTS")
            callback.onComplete()
            return
        }

        stop()
        currentCallback = callback

        currentJob = scope.launch {
            try {
                Log.d(TAG, "开始合成语音: ${cleanText.take(50)}...")

                // 1. 调用 TTS API 获取音频 URL
                val audioUrl = callTTSAPI(cleanText) ?: run {
                    Log.e(TAG, "TTS API 返回空 URL")
                    callback.onError("TTS API 返回空 URL")
                    return@launch
                }
                Log.d(TAG, "音频 URL 获取成功，开始下载...")

                // 2. 下载音频数据（PCM）
                val audioData = downloadAudio(audioUrl) ?: run {
                    Log.e(TAG, "音频下载失败")
                    callback.onError("音频下载失败")
                    return@launch
                }
                Log.d(TAG, "音频下载完成，大小: ${audioData.size} bytes (${audioData.size / 2} samples)")

                // 3. 双路 AudioTrack 播放
                withContext(Dispatchers.Main) {
                    playDualAudioTrack(audioData, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS 异常: ${e.message}", e)
                callback.onError(e.message ?: "未知错误")
            }
        }
    }

    // ==================== TTS API ====================

    private fun callTTSAPI(text: String): String? {
        return try {
            val requestBody = JSONObject().apply {
                put("model", "cosyvoice-v3-flash")
                put("input", JSONObject().apply {
                    put("text", text)
                    put("voice", "longcheng_v3")
                    put("format", "pcm")              // ★ PCM 格式，直接送 AudioTrack
                    put("sample_rate", SAMPLE_RATE)
                })
            }

            val request = Request.Builder()
                .url(TTS_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create("application/json".toMediaType(), requestBody.toString()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (response.isSuccessful && body != null) {
                val json = JSONObject(body)
                val url = json.optJSONObject("output")
                    ?.optJSONObject("audio")
                    ?.optString("url")
                Log.d(TAG, "TTS API 响应: request_id=${json.optString("request_id")}")
                url
            } else {
                Log.e(TAG, "TTS API 错误: code=${response.code}, body=$body")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS API 调用失败: ${e.message}")
            null
        }
    }

    // ==================== 下载音频 ====================

    private fun downloadAudio(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else {
                    Log.e(TAG, "音频下载失败: code=${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "音频下载异常: ${e.message}")
            null
        }
    }

    // ==================== 双路 AudioTrack 播放 ====================

    @SuppressLint("MissingPermission")
    private suspend fun playDualAudioTrack(pcmData: ByteArray, callback: TTSCallback) {
        try {
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            // ★ 查找内置扬声器和USB设备
            val outputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
            Log.d(TAG, "  输出设备列表 (${outputDevices.size}):")
            outputDevices.forEach { d ->
                Log.d(TAG, "    - ${d.productName} type=${d.type}")
            }
            val builtinSpeaker = outputDevices.find {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            val usbDevice = outputDevices.find {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            Log.d(TAG, "  内置扬声器: ${builtinSpeaker?.productName ?: "未找到"}")
            Log.d(TAG, "  USB设备: ${usbDevice?.productName ?: "未找到"}")

            // ★★★ AudioTrack 1: 手机内置扬声器
            // USAGE_ALARM + setPreferredDevice(内置扬声器) 双重保险
            val speakerAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            speakerTrack = AudioTrack.Builder()
                .setAudioAttributes(speakerAttrs)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // ★ 显式指定内置扬声器
            if (builtinSpeaker != null) {
                try {
                    speakerTrack!!.setPreferredDevice(builtinSpeaker)
                    Log.d(TAG, "  ✅ speakerTrack → 内置扬声器: ${builtinSpeaker.productName}")
                } catch (e: Exception) {
                    Log.w(TAG, "  ⚠️ speakerTrack setPreferredDevice 失败: ${e.message}")
                }
            } else {
                Log.w(TAG, "  ⚠️ 未找到内置扬声器设备，speakerTrack 未指定首选设备")
            }

            // ★ AudioTrack 2: ReSpeaker USB（AEC 参考信号）
            if (usbDevice != null) {
                val refAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                refTrack = AudioTrack.Builder()
                    .setAudioAttributes(refAttrs)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBufSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                try {
                    refTrack!!.setPreferredDevice(usbDevice)
                    Log.d(TAG, "  ✅ refTrack → USB: ${usbDevice.productName} (AEC 参考)")
                } catch (e: Exception) {
                    Log.w(TAG, "  ⚠️ refTrack setPreferredDevice 失败: ${e.message}")
                    refTrack?.release()
                    refTrack = null
                }
            } else {
                Log.w(TAG, "  ⚠️ 未找到 USB 音频设备，仅使用手机扬声器（无 AEC 参考信号）")
            }

            // 启动播放
            isPlaying = true

            // ★ 先启动 refTrack，让 ReSpeaker DSP 先收到参考信号
            refTrack?.play()
            Log.d(TAG, "  🎵 refTrack 先启动（预热 USB 音频流）")

            // 在 IO 线程写入 PCM 数据
            // ★ 使用 withContext 而非 scope.launch，确保此 IO 操作是 currentJob 的子协程
            // 当 stop() 调用 currentJob?.cancel() 时，此 IO 协程也会被取消，避免竞态
            withContext(Dispatchers.IO) {
                try {
                    val chunkSize = minBufSize
                    var offset = 0
                    var chunkIndex = 0

                    // ★ 先把全部 PCM 数据写入 refTrack（缓冲区满时 write 自动阻塞等待播放）
                    // 这样 ReSpeaker DSP 能拿到完整的 AEC 参考信号
                    while (offset < pcmData.size && isPlaying) {
                        val end = minOf(offset + chunkSize, pcmData.size)
                        val chunk = pcmData.copyOfRange(offset, end)
                        refTrack?.write(chunk, 0, chunk.size)
                        offset = end
                        chunkIndex++
                    }
                    Log.d(TAG, "  🎵 refTrack 全部数据已写入 (${chunkIndex} chunks)，启动扬声器...")

                    // ★ refTrack 数据全部送完，再启动 speakerTrack 让扬声器发声
                    speakerTrack?.play()
                    Log.d(TAG, "  🎵 speakerTrack 启动（扬声器开始发声）")
                    callback.onStart()

                    // 将相同数据写入 speakerTrack
                    offset = 0
                    while (offset < pcmData.size && isPlaying) {
                        val end = minOf(offset + chunkSize, pcmData.size)
                        val chunk = pcmData.copyOfRange(offset, end)
                        speakerTrack?.write(chunk, 0, chunk.size)
                        offset = end
                    }

                    if (isPlaying) {
                        speakerTrack?.stop()
                        refTrack?.stop()
                        Log.d(TAG, "  🎵 播放完毕 (共 ${chunkIndex} chunks)")
                        callback.onComplete()
                    } else {
                        Log.d(TAG, "  🎵 播放被打断 (chunk#$chunkIndex)")
                    }
                } catch (e: CancellationException) {
                    // ★ 协程被取消（stop() 调用），不是真正的错误，正常退出
                    Log.d(TAG, "  ⏹ 播放被取消")
                    throw e  // 重新抛出，让 withContext 正常处理取消
                } catch (e: Exception) {
                    Log.e(TAG, "  ❌ 播放异常: ${e.message}")
                    callback.onError(e.message ?: "播放异常")
                } finally {
                    isPlaying = false
                    releaseTracks()
                    Log.d(TAG, "  🔊 播放结束，资源已释放")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "双路 AudioTrack 创建失败: ${e.message}")
            callback.onError(e.message ?: "AudioTrack 创建失败")
            releaseTracks()
        }
    }

    // ==================== 辅助方法 ====================

    /** 查找 USB 音频输出设备 */
    private fun findUSBAudioDevice(): AudioDeviceInfo? {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        Log.d(TAG, "  输出设备列表 (${outputDevices.size}):")
        outputDevices.forEach { d ->
            Log.d(TAG, "    - ${d.productName} type=${d.type}")
        }
        return outputDevices.find {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    /** 释放双路 AudioTrack */
    private fun releaseTracks() {
        try {
            speakerTrack?.release()
            speakerTrack = null
            refTrack?.release()
            refTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "releaseTracks 异常: ${e.message}")
        }
    }

    // ==================== 停止播放 ====================

    fun stop() {
        Log.d(TAG, "stop() → 取消协程 + 停止 AudioTrack")
        currentJob?.cancel()
        currentJob = null

        val cb = currentCallback
        currentCallback = null

        isPlaying = false

        // 停止并释放 AudioTrack + 恢复音频模式
        val cleanup: () -> Unit = {
            try {
                speakerTrack?.let {
                    try { it.stop() } catch (_: Exception) {}
                    it.release()
                }
                refTrack?.let {
                    try { it.stop() } catch (_: Exception) {}
                    it.release()
                }
                speakerTrack = null
                refTrack = null
                Log.d(TAG, "  🔊 stop() 资源已释放")
            } catch (e: Exception) {
                Log.e(TAG, "stop() AudioTrack 释放异常: ${e.message}")
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup()
        } else {
            handler.post(cleanup)
        }

        // 通知回调：被打断
        cb?.onError("stopped")
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
