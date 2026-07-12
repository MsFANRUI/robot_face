package com.popkter.robotface

import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TTS 语音合成客户端
 *
 * 职责：
 * 1. 将文本发送给百炼 CosyVoice API 合成语音
 * 2. 获取合成后的音频 URL 并下载
 * 3. 通过 MediaPlayer 播放音频
 *
 * 使用阿里云百炼 CosyVoice 非流式 HTTP API
 */
class TTSClient(
    private val apiKey: String  // 百炼 DashScope API Key（和 ASR 共用）
) {
    companion object {
        private const val TAG = "TTS"
        // TTS API 地址（百炼标准域名）
        private const val TTS_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
    }

    // HTTP 客户端（TTS 用同步请求，设置合理的读取超时）
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    // 主线程 Handler，用于确保 MediaPlayer 操作都在主线程执行
    private val handler = Handler(Looper.getMainLooper())
    // 协程作用域，替代裸 Thread，生命周期可管理
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    // ==================== 回调接口 ====================
    interface TTSCallback {
        fun onStart()           // 开始播放
        fun onComplete()        // 播放完成
        fun onError(message: String)  // 出错
    }

    // ==================== 核心方法：合成并播放 ====================
    /**
     * 将文本合成为语音并播放
     *
     * @param text 要合成的文本（LLM 的回复内容）
     * @param callback 播放状态回调
     */
    fun speak(text: String, callback: TTSCallback) {
        // 清理文本：去除 emoji 和特殊字符，避免 TTS 读出奇怪内容
        val cleanText = text.replace(Regex("[\\p{So}\\p{Sk}\\p{Sc}\\p{Sm}]"), "").trim()
        if (cleanText.isBlank()) {
            Log.d(TAG, "文本为空或只有 emoji，跳过 TTS")
            callback.onComplete()
            return
        }

        // 先停止上一次播放（如果还在播放）
        stop()

        // 用协程替代裸 Thread，可取消、可追踪
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

                // 2. 下载音频数据
                val audioData = downloadAudio(audioUrl) ?: run {
                    Log.e(TAG, "音频下载失败")
                    callback.onError("音频下载失败")
                    return@launch
                }
                Log.d(TAG, "音频下载完成，大小: ${audioData.size} bytes，开始播放")

                // 3. 播放音频（切到主线程操作 MediaPlayer）
                callback.onStart()
                withContext(Dispatchers.Main) {
                    playAudio(audioData, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS 异常: ${e.message}", e)
                callback.onError(e.message ?: "未知错误")
            }
        }
    }

    // ==================== 步骤1：调用 TTS API ====================
    /**
     * 调用 CosyVoice API 合成语音，返回音频 URL
     *
     * 非流式模式：API 返回一个包含完整音频的 URL（24小时有效）
     */
    private fun callTTSAPI(text: String): String? {
        return try {
            val requestBody = JSONObject().apply {
                put("model", "cosyvoice-v3-flash")  // 模型
                put("input", JSONObject().apply {
                    put("text", text)                    // 待合成文本
                    put("voice", "longcheng_v3")         // 音色
                    put("format", "mp3")                 // 音频格式
                    put("sample_rate", 24000)            // 采样率
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

    // ==================== 步骤2：下载音频 ====================
    /**
     * 从 URL 下载音频数据（ByteArray）
     */
    private fun downloadAudio(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else {
                Log.e(TAG, "音频下载失败: code=${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "音频下载异常: ${e.message}")
            null
        }
    }

    // ==================== 步骤3：播放音频 ====================
    /**
     * 使用 MediaPlayer 播放音频数据
     * 通过 MediaDataSource 直接传入 ByteArray，无需写临时文件
     */
    private fun playAudio(data: ByteArray, callback: TTSCallback) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(object : MediaDataSource() {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        val available = data.size - position.toInt()
                        if (available <= 0) return -1
                        val bytesToRead = minOf(size, available)
                        System.arraycopy(data, position.toInt(), buffer, offset, bytesToRead)
                        return bytesToRead
                    }
                    override fun getSize(): Long = data.size.toLong()
                    override fun close() {}
                })
                setOnCompletionListener {
                    Log.d(TAG, "播放完成")
                    callback.onComplete()
                    it.release()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "播放错误: what=$what, extra=$extra")
                    callback.onError("播放错误: $what")
                    mp.release()
                    true
                }
                setOnPreparedListener { mp ->
                    Log.d(TAG, "开始播放音频")
                    mp.start()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放异常: ${e.message}")
            callback.onError(e.message ?: "播放异常")
        }
    }

    // ==================== 停止播放 ====================
    /**
     * 停止当前播放（用户打断或销毁时调用）
     * 同步清理 MediaPlayer，避免异步竞态
     */
    fun stop() {
        // 取消正在进行的协程（TTS 合成/下载）
        currentJob?.cancel()
        currentJob = null

        // 同步清理 MediaPlayer（已在主线程则直接执行，否则 post 到主线程）
        val cleanup: () -> Unit = {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
                mediaPlayer = null
            } catch (e: Exception) {
                Log.e(TAG, "停止播放异常: ${e.message}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cleanup()
        } else {
            handler.post(cleanup)
        }
    }

    /**
     * 释放资源（Activity 销毁时调用）
     */
    fun destroy() {
        stop()
        scope.cancel()
    }
}
