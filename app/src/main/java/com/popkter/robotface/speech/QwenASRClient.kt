package com.popkter.robotface.speech

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class QwenASRClient(
    private val apiKey: String,
    private val onTranscript: (String) -> Unit  // 识别结果回调
) {
    companion object {
        private const val TAG = "QwenASR"
        // 重连配置
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L   // 初始重连延迟 1s
        private const val MAX_RECONNECT_DELAY_MS = 30000L      // 最大延迟 30s
    }

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var sessionCreated = false
    private val eventIdCounter = AtomicLong(1)

    // 重连控制
    private var shouldReconnect = false
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    // OkHttpClient 复用（避免每次重连都创建新实例）
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .build()

    private fun nextEventId(): String = "event_${eventIdCounter.getAndIncrement()}"

    fun connect() {
        shouldReconnect = true
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        doConnect()
    }
    
    private fun doConnect() {
        val url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime"
    
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()
    
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.d(TAG, "WebSocket connected, waiting for session.created...")
                isConnected = true
                reconnectDelay = INITIAL_RECONNECT_DELAY_MS  // 连接成功，重置重连延迟
            }
    
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "onMessage: ${text.take(300)}")
                handleMessage(text)
            }
    
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}, code=${response?.code}")
                isConnected = false
                sessionCreated = false
                scheduleReconnect()
            }
    
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code, reason=$reason")
                isConnected = false
                sessionCreated = false
            }
    
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
                isConnected = false
                sessionCreated = false
                // 非主动关闭时尝试重连
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
        })
    }
    
    /**
     * 指数退避重连
     * 延迟从 1s 开始，每次翻倍，最大 30s
     */
    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        Log.d(TAG, "🔌 ${reconnectDelay}ms 后尝试重连...")
        val runnable = Runnable {
            Log.d(TAG, "🔌 正在重连...")
            doConnect()
        }
        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, reconnectDelay)
        // 指数退避：下次延迟翻倍，封顶 MAX_RECONNECT_DELAY_MS
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            when (type) {
                "session.created" -> {
                    Log.d(TAG, "Session created, sending session.update now")
                    sessionCreated = true
                    sendSessionUpdate()
                }
                "session.updated" -> {
                    Log.d(TAG, "Session updated successfully, ready to receive audio")
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", "")
                    if (transcript.isNotBlank()) {
                        Log.d(TAG, "Transcript final: $transcript")
                        onTranscript(transcript)
                    }
                }
                "conversation.item.input_audio_transcription.text" -> {
                    val partialText = json.optString("text", "")
                    val stash = json.optString("stash", "")
                    if (partialText.isNotBlank() || stash.isNotBlank()) {
                        Log.d(TAG, "Transcript partial: text=$partialText, stash=$stash")
                    }
                }
                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "Speech started")
                }
                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "Speech stopped")
                }
                "error" -> {
                    val error = json.optJSONObject("error")
                    Log.e(TAG, "Server error: code=${error?.optString("code")}, message=${error?.optString("message")}")
                }
                else -> {
                    Log.d(TAG, "Unhandled event: type=$type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}, raw=$text")
        }
    }

    private fun sendSessionUpdate() {
        // 配置 session，开启 VAD（与官方 Python 示例对齐）
        val sessionUpdate = JSONObject().apply {
            put("event_id", nextEventId())
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply { put("text") })
                put("input_audio_format", "pcm")
                put("sample_rate", 16000)
                put("input_audio_transcription", JSONObject().apply {
                    put("language", "zh")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.6)
                    put("silence_duration_ms", 800)  // 停顿 800ms 算说完
                })
            })
        }
        val msg = sessionUpdate.toString()
        Log.d(TAG, "sendSessionUpdate: $msg")
        webSocket?.send(msg)
    }

    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected || !sessionCreated) {
            return
        }
        // 音频数据需要 base64 编码后包装在 JSON 里发送
        val encoded = Base64.getEncoder().encodeToString(pcmData)
        val event = JSONObject().apply {
            put("event_id", nextEventId())
            put("type", "input_audio_buffer.append")
            put("audio", encoded)
        }
        webSocket?.send(event.toString())
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() called")
        shouldReconnect = false
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
        webSocket?.close(1000, "disconnect")
        isConnected = false
        sessionCreated = false
    }
}
