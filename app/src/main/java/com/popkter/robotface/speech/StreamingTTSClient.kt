package com.popkter.robotface.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 流式 TTS 语音合成客户端（基于 CosyVoice WebSocket API）
 *
 * 相比非流式 TTSClient，优势：
 * 1. 不需要等全文合成完 — WebSocket 直连，首帧延迟 ~50ms
 * 2. 边合成边播放 — 音频帧到达即写入 AudioTrack
 * 3. 支持增量文本输入 — 可以先开始播第一句，后续句子追加
 *
 * CosyVoice WebSocket 协议（/api-ws/v1/inference/）：
 *   Client: run-task → Server: task-started
 *   Client: continue-task(text) → Server: binary audio frames
 *   Client: finish-task → Server: task-finished
 */
class StreamingTTSClient(
    private val apiKey: String,
    private val context: Context? = null,  // ★ 用于查找 USB 音频设备（双路输出）
    @Volatile
    private var voice: String = "longcheng_v3",
    private val model: String = "cosyvoice-v3-flash"
) {
    companion object {
        private const val TAG = "StreamingTTS"

        // CosyVoice WebSocket 端点（Authorization 在请求头中传递）
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"

        // 音频参数（必须和 TTS API parameters 一致）
        private const val SAMPLE_RATE = 24000

        // ★ 全局异常处理器安装标记
        @Volatile
        private var handlerInstalled = false

        // ==================== 安全 Executor ====================
        // OkHttp 4.12 AsyncCall.run() 内部 catch(t: Throwable) { ... throw t } 会重新抛出异常！
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
                    val task = java.util.concurrent.FutureTask(command, null)
                    super.execute(task)
                }
                override fun afterExecute(r: Runnable?, t: Throwable?) {
                    super.afterExecute(r, t)
                    if (t == null && r is java.util.concurrent.Future<*>) {
                        try {
                            r.get()
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

    // ★ 全局未捕获异常处理器：兜底 OkHttp TaskRunner 内部线程（非 Dispatcher 线程池）逃逸的异常
    // safeExecutor 只保护 Dispatcher 线程池，TaskRunner 的 WebSocket 写入线程不受其保护
    private fun installGlobalExceptionHandler() {
        if (!handlerInstalled) {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.w(TAG, "全局异常捕获(防崩溃) [${thread.name}]: ${throwable.javaClass.simpleName}: ${throwable.message}")
            }
            handlerInstalled = true
        }
    }

    // ==================== 状态机 ====================
    // IDLE → CONNECTING → READY → PLAYING → DONE
    // 任何状态 → DISCONNECTED（出错或主动断开）

    private enum class State { IDLE, CONNECTING, READY, PLAYING, FINISHING, DONE }

    @Volatile
    private var state: State = State.IDLE
    private val taskId: String
        get() = _taskId
    private var _taskId = ""

    // WebSocket
    private var webSocket: WebSocket? = null
    // 待发送文本队列（在 task-started 之前到达的文本先暂存）
    private val pendingTexts = mutableListOf<String>()

    // ★ 双路 AudioTrack（与 TTSClient 保持一致）
    // speakerTrack: 手机内置扬声器（USAGE_ALARM 强制外放）
    // refTrack: ReSpeaker USB（USAGE_MEDIA，给 DSP 做 AEC 参考信号）
    @Volatile
    private var speakerTrack: AudioTrack? = null
    @Volatile
    private var refTrack: AudioTrack? = null
    private val audioTrackLock = Any()

    // 销毁标记
    private val destroyed = AtomicBoolean(false)

    // ★ WebSocket 关闭标记 + 同步锁（防止向已关闭的 WS 发送触发 TaskRunner 线程异常）
    @Volatile
    private var wsClosed = false
    private val wsLock = Any()

    // HTTP 客户端
    // ⚠️ 自定义 executor：防止 OkHttp 内部 WebSocket 清理路径 NPE 逃逸导致崩溃
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher(safeExecutor("StreamingTTS")))
        .build()

    // ==================== 回调接口 ====================
    interface Callback {
        /** 第一批音频数据到达，AudioTrack 已开始播放 */
        fun onStart()
        /** 所有音频播放完毕 */
        fun onComplete()
        /** 出错，msg 为人可读的错误描述 */
        fun onError(message: String)
    }

    init {
        installGlobalExceptionHandler()
    }

    // ==================== 公开 API ====================

    /**
     * 开始流式 TTS 会话：连接 WebSocket → 配置 TTS 引擎 → 发送文本 → 实时播放
     *
     * @param text      第一句要合成的文本
     * @param callback  播放状态回调
     */
    fun speak(text: String, callback: Callback) {
        if (destroyed.get()) {
            callback.onError("StreamingTTSClient 已销毁")
            return
        }
        // 如果上次会话还在，先断开
        if (state != State.IDLE && state != State.DONE) {
            Log.d(TAG, "中断上一个 TTS 会话")
            disconnectInternal()
        }

        // ★ 重置音频帧计数器
        ttsAudioFrameCount = 0L
        ttsAudioByteTotal = 0L

        // 重置状态
        _taskId = UUID.randomUUID().toString().replace("-", "")
        state = State.CONNECTING
        pendingTexts.clear()
        if (text.isNotBlank()) pendingTexts.add(text)

        // ★ 重置 wsClosed 标记（新建连接前必须清除）
        synchronized(wsLock) { wsClosed = false }

        // 创建双路 AudioTrack
        initDualAudioTracks()

        // 连接 WebSocket
        // ★ 按官方文档，Authorization 在 WebSocket 请求头中传递
        val request = Request.Builder()
            .url(WS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        Log.d(TAG, "Connecting to: $WS_URL")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 已连接，发送 run-task...")
                sendRunTask()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleTextMessage(text, callback)
            }

            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                handleBinaryAudio(bytes.toByteArray(), callback)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 连接失败: ${t.message} code=${response?.code}")
                synchronized(wsLock) { wsClosed = true }
                state = State.DONE
                callback.onError("TTS 连接失败: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 关闭: code=$code reason=$reason")
                synchronized(wsLock) { wsClosed = true }
                if (state != State.DONE) {
                    state = State.DONE
                    // 等 AudioTrack 排空缓冲区
                    drainAndCallback(callback)
                }
            }
        })
    }

    /**
     * 向当前 TTS 会话追加文本（LLM 继续流出内容）
     * 必须在 task-started 之后调用，否则文本会被暂存
     */
    fun addText(text: String) {
        if (destroyed.get() || text.isBlank()) return
        Log.d(TAG, "addText: ${text.take(40)}...")

        if (!canSendText()) {
            // 还没 task-started → 暂存
            pendingTexts.add(text)
            Log.d(TAG, "  暂存（未就绪），队列: ${pendingTexts.size} 条")
            return
        }

        // 先清空暂存队列
        flushPendingTexts()
        doSendText(text)
    }

    /**
     * 标记所有文本已发送完毕，通知服务端结束合成
     */
    fun finishInput() {
        if (destroyed.get()) return
        Log.d(TAG, "finishInput()")

        if (!canSendText()) {
            // 还没就绪，先清空暂存再 finish
            pendingTexts.add("__FINISH__")
            return
        }

        flushPendingTexts()
        state = State.FINISHING
        sendJson(JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "finish-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("input", JSONObject())
            })
        })
    }

    /**
     * 立刻停止播放并断开连接（打断时调用）
     */
    fun stop() {
        Log.d(TAG, "stop() — 立即停止播放")
        stopAudioTrack()
        disconnectInternal()
        state = State.DONE
    }

    /**
     * 释放所有资源（Activity.onDestroy 时调用）
     */
    fun destroy() {
        destroyed.set(true)
        stop()
        client.dispatcher.executorService.shutdown()
    }

    /**
     * 动态切换 TTS 音色（运行时生效）
     * 下次 speak() 创建的 WebSocket 连接将使用新音色
     */
    fun updateVoice(newVoice: String) {
        voice = newVoice
        Log.d(TAG, "TTS 音色已切换: $newVoice")
    }

    // ==================== 内部实现 ====================

    private fun sendRunTask() {
        sendJson(JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "run-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("task_group", "audio")
                put("task", "tts")
                put("function", "SpeechSynthesizer")
                put("model", model)
                put("parameters", JSONObject().apply {
                    put("text_type", "PlainText")
                    put("voice", voice)
                    put("format", "pcm")
                    put("sample_rate", SAMPLE_RATE)
                    put("volume", 100)
                    put("rate", 1.0)
                    put("pitch", 1.0)
                })
                put("input", JSONObject())
            })
        })
    }

    private fun doSendText(text: String) {
        sendJson(JSONObject().apply {
            put("header", JSONObject().apply {
                put("action", "continue-task")
                put("task_id", taskId)
                put("streaming", "duplex")
            })
            put("payload", JSONObject().apply {
                put("input", JSONObject().apply {
                    put("text", text)
                })
            })
        })
    }

    private fun flushPendingTexts() {
        while (pendingTexts.isNotEmpty()) {
            val text = pendingTexts.removeAt(0)
            if (text == "__FINISH__") {
                finishInput()
                return
            }
            doSendText(text)
        }
    }

    private fun canSendText(): Boolean = state == State.READY || state == State.PLAYING

    private fun sendJson(json: JSONObject) {
        val msg = json.toString()
        synchronized(wsLock) {
            if (wsClosed) {
                Log.w(TAG, "sendJson: WebSocket 已关闭，跳过发送")
                return
            }
            try {
                webSocket?.send(msg)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "sendJson: WebSocket 已关闭，忽略发送: ${e.message}")
            }
        }
    }

    // ── 消息处理 ──

    private fun handleTextMessage(text: String, callback: Callback) {
        try {
            Log.d(TAG, "← 原始消息: $text")
            val json = JSONObject(text)
            val header = json.optJSONObject("header")
            val eventName = header?.optString("event", "")

            Log.d(TAG, "  header=$header, eventName=$eventName")

            when (eventName) {
                "task-started" -> {
                    state = State.READY
                    Log.d(TAG, "  TTS 引擎就绪，清空 ${pendingTexts.size} 条暂存文本")
                    flushPendingTexts()
                }

                "result-generated" -> {
                    val payload = json.optJSONObject("payload")
                    if (payload != null) {
                        val output = payload.optJSONObject("output")
                        // 音频数据以二进制帧形式到达，这里只是标记
                        Log.d(TAG, "  result-generated (音频帧随二进制通道到达)")
                    }
                }

                "task-finished" -> {
                    Log.d(TAG, "  服务端合成完毕 → 立即回调 onComplete，后台排空 AudioTrack")
                    state = State.DONE
                    synchronized(wsLock) {
                        wsClosed = true
                        try { webSocket?.close(1000, "task-finished") } catch (_: Exception) {}
                    }
                    // ★ 立即回调 onComplete（让 Orchestrator 立刻恢复状态/表情）
                    callback.onComplete()
                    // ★ 后台异步等 AudioTrack 排空后释放资源（不阻塞状态恢复）
                    Thread {
                        try {
                            var patience = 50
                            // ★ Bug 7 修复：在 synchronized 内检查 playState，防止与 stop() 竞态
                            while (patience > 0) {
                                val stillPlaying = synchronized(audioTrackLock) {
                                    speakerTrack?.playState == AudioTrack.PLAYSTATE_PLAYING ||
                                            refTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
                                }
                                if (!stillPlaying) break
                                Thread.sleep(100)
                                patience--
                            }
                            synchronized(audioTrackLock) { releaseDualAudioTracks() }
                            Log.d(TAG, "  后台 AudioTrack 排空完毕，资源已释放")
                        } catch (e: Exception) {
                            Log.e(TAG, "  后台排空异常: ${e.message}")
                            synchronized(audioTrackLock) { releaseDualAudioTracks() }
                        }
                    }.apply { isDaemon = true }.start()
                }

                "task-failed" -> {
                    val error = header?.optString("error_code", "unknown")
                    val message = header?.optString("error_message", "未知错误")
                    Log.e(TAG, "  TTS 合成失败: $error - $message")
                    state = State.DONE
                    callback.onError("TTS 合成失败: $message")
                }

                else -> {
                    Log.d(TAG, "  未处理事件: $eventName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "消息解析异常: ${e.message}", e)
        }
    }

    // ★ 音频帧计数器（用于日志刷新）
    private var ttsAudioFrameCount = 0L
    private var ttsAudioByteTotal = 0L

    /** 二进制音频帧到达 → 同时写入双路 AudioTrack */
    @SuppressLint("MissingPermission")
    private fun handleBinaryAudio(data: ByteArray, callback: Callback) {
        if (data.isEmpty()) return

        ttsAudioFrameCount++
        ttsAudioByteTotal += data.size
        // ★ 每 5 帧打印一次（持续刷新）
        if (ttsAudioFrameCount % 5 == 0L) {
            Log.d(TAG, "🎵 [TTS音频] 帧#$ttsAudioFrameCount | 本帧=${data.size}B | 累计=${ttsAudioByteTotal / 1024}KB")
        }

        synchronized(audioTrackLock) {
            val speaker = speakerTrack
            if (speaker == null) {
                // ★ Bug 8 修复：speakerTrack 创建失败时，报错而非静默丢弃音频
                Log.e(TAG, "speakerTrack 为 null，无法播放音频")
                callback.onError("AudioTrack 创建失败，无法播放")
                return
            }

            // ★ 首帧到达：启动双路播放
            if (speaker.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    // ★ 先启动 refTrack（预热 USB 音频流，让 ReSpeaker DSP 先收到参考信号）
                    refTrack?.play()
                    Log.d(TAG, "  🎵 refTrack 先启动（预热 USB 音频流）")

                    // 再启动 speakerTrack
                    speaker.play()
                    Log.d(TAG, "  🎵 speakerTrack 启动（扬声器开始发声，首帧 ${data.size} bytes）")

                    state = State.PLAYING
                    callback.onStart()
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack.play() 失败: ${e.message}")
                    callback.onError("音频播放失败: ${e.message}")
                    return
                }
            }

            // ★ 相同数据同时写入双路 AudioTrack
            try {
                // refTrack: AEC 参考信号 → ReSpeaker USB
                refTrack?.let { ref ->
                    val refWritten = ref.write(data, 0, data.size)
                    if (refWritten < 0) {
                        Log.w(TAG, "refTrack.write() 失败: $refWritten")
                    }
                }

                // speakerTrack: 手机扬声器外放
                val spkWritten = speaker.write(data, 0, data.size)
                if (spkWritten < 0) {
                    Log.w(TAG, "speakerTrack.write() 失败: $spkWritten")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack.write() 异常: ${e.message}")
            }
        }
    }

    // ── 双路 AudioTrack（与 TTSClient 保持一致的路由策略） ──

    /**
     * 创建双路 AudioTrack：
     * - speakerTrack: USAGE_ALARM → 手机内置扬声器（强制外放，即使连接耳机）
     * - refTrack: USAGE_MEDIA → ReSpeaker USB 设备（AEC 参考信号）
     */
    @SuppressLint("MissingPermission")
    private fun initDualAudioTracks() {
        synchronized(audioTrackLock) {
            releaseDualAudioTracks()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )

            val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
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

            // ★ speakerTrack: 手机内置扬声器（USAGE_ALARM 强制外放）
            try {
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

                if (builtinSpeaker != null) {
                    speakerTrack!!.setPreferredDevice(builtinSpeaker)
                    Log.d(TAG, "  ✅ speakerTrack → 内置扬声器: ${builtinSpeaker.productName}")
                } else {
                    Log.w(TAG, "  ⚠️ 未找到内置扬声器，speakerTrack 未指定首选设备")
                }
            } catch (e: Exception) {
                Log.e(TAG, "  speakerTrack 创建失败: ${e.message}")
                speakerTrack = null
            }

            // ★ refTrack: ReSpeaker USB（AEC 参考信号）
            if (usbDevice != null) {
                try {
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

                    refTrack!!.setPreferredDevice(usbDevice)
                    Log.d(TAG, "  ✅ refTrack → USB: ${usbDevice.productName} (AEC 参考)")
                } catch (e: Exception) {
                    Log.w(TAG, "  ⚠️ refTrack 创建失败: ${e.message}")
                    refTrack = null
                }
            } else {
                Log.w(TAG, "  ⚠️ 未找到 USB 音频设备，仅使用手机扬声器（无 AEC 参考信号）")
            }

            Log.d(TAG, "  双路 AudioTrack 已创建 (speaker=${speakerTrack != null}, ref=${refTrack != null})")
        }
    }

    /** 释放双路 AudioTrack */
    private fun releaseDualAudioTracks() {
        try {
            speakerTrack?.let {
                try {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.pause()
                        it.flush()
                    }
                    it.release()
                } catch (_: Exception) {}
            }
            speakerTrack = null

            refTrack?.let {
                try {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.pause()
                        it.flush()
                    }
                    it.release()
                } catch (_: Exception) {}
            }
            refTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "releaseDualAudioTracks 异常: ${e.message}")
        }
    }

    private fun stopAudioTrack() {
        synchronized(audioTrackLock) { releaseDualAudioTracks() }
    }

    /** 等 AudioTrack 排空剩余音频后回调 onComplete */
    private fun drainAndCallback(callback: Callback) {
        Thread {
            try {
                synchronized(audioTrackLock) {
                    // 等待双路 AudioTrack 播放完毕
                    var patience = 50 // 最多等 5 秒
                    while (patience > 0 &&
                        (speakerTrack?.playState == AudioTrack.PLAYSTATE_PLAYING ||
                                refTrack?.playState == AudioTrack.PLAYSTATE_PLAYING)
                    ) {
                        Thread.sleep(100)
                        patience--
                    }
                    releaseDualAudioTracks()
                }
                Log.d(TAG, "播放完毕 (双路 AudioTrack 已释放)")
            } catch (e: Exception) {
                Log.e(TAG, "drainAndCallback 异常，强制释放: ${e.message}")
                synchronized(audioTrackLock) { releaseDualAudioTracks() }
            } finally {
                // ★ 无论如何都确保 onComplete 被调用，防止状态卡死
                try {
                    callback.onComplete()
                } catch (e: Exception) {
                    Log.e(TAG, "onComplete 回调异常: ${e.message}")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun disconnectInternal() {
        synchronized(wsLock) {
            wsClosed = true
            try { webSocket?.cancel() } catch (_: Exception) {}
            webSocket = null
        }
        pendingTexts.clear()
        state = State.IDLE
    }
}
