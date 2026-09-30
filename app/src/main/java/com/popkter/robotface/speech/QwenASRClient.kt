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
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class QwenASRClient(
    private val apiKey: String,
    private val onTranscript: (String) -> Unit,        // 最终识别结果回调
    private val onPartialResult: (String) -> Unit = {}  // ★ 部分识别结果回调（用于唤醒词检测）
) {
    companion object {
        private const val TAG = "QwenASR"
        // 重连配置
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L   // 初始重连延迟 1s
        private const val MAX_RECONNECT_DELAY_MS = 30000L      // 最大延迟 30s

        // ★ 全局异常处理器安装标记
        @Volatile
        private var handlerInstalled = false

        // ==================== 安全 Executor ====================
        // OkHttp 4.12 AsyncCall.run() 内部 catch(t: Throwable) { ... throw t } 会重新抛出异常！
        // 普通 execute() 重写包 try-catch 拦不住这个 re-throw。
        // 方案：用 FutureTask 包装 —— FutureTask.run() 内部捕获异常后存储，不 re-throw。
        // 在 afterExecute() 中检查 Future 提取异常并吞掉，防止崩溃。
        private fun safeExecutor(name: String): ExecutorService {
            return object : ThreadPoolExecutor(
                0, Int.MAX_VALUE, 60L, TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>(),
                ThreadFactory { r ->
                    Thread(r, "OkHttp-$name").apply { isDaemon = true }
                }
            ) {
                override fun execute(command: Runnable) {
                    // FutureTask 捕获所有 Throwable（含 re-throw），不向线程逃逸
                    val task = java.util.concurrent.FutureTask(command, null)
                    super.execute(task)
                }
                override fun afterExecute(r: Runnable?, t: Throwable?) {
                    super.afterExecute(r, t)
                    if (t == null && r is java.util.concurrent.Future<*>) {
                        try {
                            r.get() // 提取 FutureTask 内部存储的异常
                        } catch (ex: java.util.concurrent.ExecutionException) {
                            Log.w(TAG, "OkHttp 内部异常已捕获(防崩溃): ${ex.cause?.javaClass?.simpleName}: ${ex.cause?.message}")
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            }
        }
    }

    // ★ 全局未捕获异常处理器：兜底 OkHttp TaskRunner 内部线程逃逸的异常
    private fun installGlobalExceptionHandler() {
        if (!handlerInstalled) {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.w(TAG, "全局异常捕获(防崩溃) [${thread.name}]: ${throwable.javaClass.simpleName}: ${throwable.message}")
            }
            handlerInstalled = true
        }
    }

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var sessionCreated = false
    private val eventIdCounter = AtomicLong(1)
    // ★ 连接代数：每次 doConnect 递增，旧连接的回调发现代数不匹配则忽略
    // 防止 cancel 旧连接触发的 onClosed 导致重连循环
    @Volatile
    private var connectionGeneration = 0
    // 重连控制
    private var shouldReconnect = false
    private var reconnectScheduled = false       // 防重入：确保同一时间只有一个重连任务
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    // ★ WebSocket 同步锁（防止 cancel/close 与 send 并发触发 TaskRunner 线程异常）
    private val wsLock = Any()

    // OkHttpClient 复用（避免每次重连都创建新实例）
    // ⚠️ 自定义 executor：OkHttp 内部 WebSocket 清理路径（failWebSocket → DeflaterSink.close）
    //    在长时间运行后可能抛 NPE（okio buffer 竞态），该异常从线程池逃逸会导致 App 崩溃。
    //    这里用 safeExecutor 兜底捕获，防止崩溃。
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .pingInterval(15, TimeUnit.SECONDS)  // WS 心跳：检测半开/静默掉线的连接，触发自动重连
        .dispatcher(okhttp3.Dispatcher(safeExecutor("QwenASR")))
        .build()

    private fun nextEventId(): String = "event_${eventIdCounter.getAndIncrement()}"

    fun connect() {
        shouldReconnect = true
        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
        doConnect()
    }

    init {
        installGlobalExceptionHandler()
    }
    
    private fun doConnect() {
        val url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        // ★ 递增连接代数，让旧连接的回调失效
        val myGen = ++connectionGeneration

        // 新建前先取消旧连接，避免手动重连时遗留半开旧 socket
        synchronized(wsLock) {
            try { webSocket?.cancel() } catch (_: Exception) {}
        }
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                if (myGen != connectionGeneration) return  // ★ 旧连接回调，忽略
                Log.d(TAG, "WebSocket connected, waiting for session.created...")
                isConnected = true
                reconnectScheduled = false
                reconnectDelay = INITIAL_RECONNECT_DELAY_MS  // 连接成功，重置重连延迟
            }
    
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myGen != connectionGeneration) return  // ★ 旧连接回调，忽略
                Log.d(TAG, "onMessage: ${text.take(300)}")
                handleMessage(text)
            }
    
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (myGen != connectionGeneration) return  // ★ 旧连接回调，忽略
                Log.e(TAG, "WebSocket error: ${t.message}, code=${response?.code}")
                isConnected = false
                sessionCreated = false
                scheduleReconnect()
            }
    
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (myGen != connectionGeneration) return  // ★ 旧连接回调，忽略
                Log.d(TAG, "WebSocket closing: code=$code, reason=$reason")
                isConnected = false
                sessionCreated = false
                // 兜底：部分情况下 onClosed 不一定跟上，这里也尝试重连
                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
    
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myGen != connectionGeneration) {
                    Log.d(TAG, "旧连接 onClosed (gen=$myGen, current=$connectionGeneration)，忽略")
                    return  // ★ 旧连接被 cancel 触发的，忽略！
                }
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
        if (!shouldReconnect || reconnectScheduled) return
        reconnectScheduled = true
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        Log.d(TAG, "🔌 ${reconnectDelay}ms 后尝试重连...")
        val runnable = Runnable {
            reconnectScheduled = false
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
                        Log.d(TAG, "✅ [ASR最终] transcript=\"$transcript\"")
                        onTranscript(transcript)
                    }
                }
                "conversation.item.input_audio_transcription.text" -> {
                    val partialText = json.optString("text", "")
                    val stash = json.optString("stash", "")
                    val combined = (partialText + stash).trim()
                    if (combined.isNotBlank()) {
                        Log.d(TAG, "📝 [ASR部分] text=\"$partialText\" stash=\"$stash\" → combined=\"$combined\"")
                        onPartialResult(combined)  // ★ 回调给 Orchestrator 做唤醒词检测
                    }
                }
                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "🎤 [ASR VAD] 检测到说话开始")
                }
                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "🔇 [ASR VAD] 检测到说话结束")
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
        synchronized(wsLock) {
            try {
                webSocket?.send(msg)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "sendSessionUpdate: WebSocket 已关闭，忽略发送: ${e.message}")
            }
        }
    }

    private var audioChunkCount = 0L  // ★ 音频帧计数器
    private var audioByteTotal = 0L   // ★ 音频字节总数

    fun sendAudio(pcmData: ByteArray) {
        if (!isConnected || !sessionCreated) {
            return
        }
        audioChunkCount++
        audioByteTotal += pcmData.size
        // ★ 每 10 帧打印一次音频发送状态（持续刷新）
        if (audioChunkCount % 10 == 0L) {
            Log.d(TAG, "📤 [ASR音频] 帧#$audioChunkCount | 本帧=${pcmData.size}B | 累计=${audioByteTotal / 1024}KB")
        }
        // 音频数据需要 base64 编码后包装在 JSON 里发送
        val encoded = Base64.getEncoder().encodeToString(pcmData)
        val event = JSONObject().apply {
            put("event_id", nextEventId())
            put("type", "input_audio_buffer.append")
            put("audio", encoded)
        }
        synchronized(wsLock) {
            try {
                webSocket?.send(event.toString())
            } catch (e: IllegalStateException) {
                Log.w(TAG, "sendAudio: WebSocket 已关闭，忽略发送: ${e.message}")
                isConnected = false
                sessionCreated = false
                scheduleReconnect()
            }
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() called")
        shouldReconnect = false
        reconnectScheduled = false
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
        synchronized(wsLock) {
            try { webSocket?.close(1000, "disconnect") } catch (_: Exception) {}
        }
        isConnected = false
        sessionCreated = false
    }

}
