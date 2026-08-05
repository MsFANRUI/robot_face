package com.popkter.robotface.orchestrator

import android.util.Log
import com.popkter.robot.status.RobotStatus
import com.popkter.robotface.ChatClient
import com.popkter.robotface.speech.ContinuousAudioRecorder
import com.popkter.robotface.speech.StreamingTTSClient
import org.json.JSONObject

/**
 * 对话全链路协调器
 *
 * 把 ASR → LLM → TTS 的完整流程串起来，管理中间的状态机。
 *
 * 打断机制：唤醒词检测（替代旧的音量阈值方案）
 * - TTS 播放期间，ASR 持续接收 AEC 处理后的干净音频
 * - ASR 实时返回部分识别结果 → Orchestrator 检测唤醒词
 * - 检测到"小萝"→ 立即停 TTS → 恢复表情
 *
 * @param setExpression  在主线程设置机器人表情
 * @param getExpression  获取当前表情快照（线程安全）
 */
class ConversationOrchestrator(
    private val chatClient: ChatClient,
    private val streamingTTSClient: StreamingTTSClient,
    private val audioRecorder: ContinuousAudioRecorder,
    private val setExpression: (RobotStatus) -> Unit,
    private val getExpression: () -> RobotStatus?,
    private val onLlError: (String) -> Unit = {},
    private val sendCmd: (category: String, action: String) -> Unit
) {

    companion object {
        private const val TAG = "Orchestrator"
    }

    // ★ 唤醒词列表（ASR 可能识别出同音不同字的变体）
    private val wakeWords = listOf("小萝", "小罗", "萝卜", "萝博")

    // ★ 当前角色配置（来自平板 Web 页面，为 null 表示未配置/已重置）
    @Volatile
    var currentConfig: com.popkter.robotface.server.CharacterConfig? = null
        private set

    // ==================== 状态 ====================
    private var expressionBeforeTTS: RobotStatus? = null
    private var ttsTriggered = false
    /** TTS 代数计数器：每次启动新 TTS 时递增，旧 TTS 的回调发现代数不匹配则忽略 */
    private var ttsGeneration = 0
    /** TTS 是否正在播放（用于唤醒词检测的门控） */
    @Volatile
    private var isTTSPlaying = false
    /** 流式 TTS 是否已启动（第一句已发送 speak） */
    private var streamingTTSStarted = false
    /** 是否正在播放公告（打招呼等），用于唤醒词打断时正确清理公告状态 */
    @Volatile
    private var isAnnouncementActive = false
    /** 公告完成回调，用于唤醒词打断时触发清理 */
    @Volatile
    private var announcementOnComplete: (() -> Unit)? = null
    /** 对话是否正在进行中（handleTranscript 触发 → TTS 播放完毕） */
    @Volatile
    private var isConversationActive = false
    /** 对话代数：用于在 TTS 回调中判断是否应清理 isConversationActive */
    private var conversationGeneration = 0
    // ★ 防回声反馈循环：TTS 播放期间 + 结束后冷却期内忽略 ASR 结果
    // AEC 处理大部分回声，但仍有残留需要 Orchestrator 兜底
    private var ttsEndCooldownUntil = 0L
    private val TTS_END_COOLDOWN_MS = 800L  // TTS 结束后 0.8s 内忽略（覆盖声音衰减期）
    // ★ 唤醒词打断标记：打断后忽略紧随的 ASR final result，防止残留文本触发新对话
    @Volatile
    private var wakeWordJustInterrupted = false
    // ★ TTS 安全看门狗：如果 TTS 播放超过此时间仍未 onComplete，强制重置状态
    private var ttsStartTime = 0L
    private val TTS_SAFETY_TIMEOUT_MS = 15_000L  // 15 秒超时

    // ==================== 唤醒词检测 ====================

    /**
     * 处理 ASR 部分识别结果（由 MainActivity 转发）
     * 仅在 TTS 播放期间检测唤醒词
     */
    fun handlePartialResult(partialText: String) {
        // ★ 安全看门狗：如果 TTS 播放超时仍未 onComplete，强制重置状态
        // 防止 onComplete 回调永远不来（WebSocket 卡死、服务端不发 task-finished 等）
        if (isTTSPlaying && ttsStartTime > 0) {
            val elapsed = System.currentTimeMillis() - ttsStartTime
            if (elapsed > TTS_SAFETY_TIMEOUT_MS) {
                Log.w(TAG, "⏰ TTS 安全超时(${elapsed}ms)，强制重置状态（onComplete 可能丢失）")
                forceResetTTSState()
                return
            }
        }
        if (!isTTSPlaying) return
        val matched = wakeWords.firstOrNull { partialText.contains(it) }
        if (matched != null) {
            Log.d(TAG, "🔔 检测到唤醒词: '$matched' (原文: $partialText)")
            onWakeWordInterrupt()
        }
    }

    /** 唤醒词触发打断：停 TTS → 恢复表情 → 清理公告状态 */
    private fun onWakeWordInterrupt() {
        Log.d(TAG, "⚡ 唤醒词打断 → 停止流式 TTS")
        streamingTTSClient.stop()
        isTTSPlaying = false
        streamingTTSStarted = false
        isConversationActive = false  // ★ 对话结束，允许后续新对话启动
        // ★ 设置标记，让 handleTranscript 忽略紧随的 ASR final result
        wakeWordJustInterrupted = true
        restoreExpression()

        // ★ 清理公告状态（打招呼等）
        // 唤醒词打断时 TTS callback 不会触发，需要手动清理
        if (isAnnouncementActive) {
            Log.d(TAG, "  公告被打断，清理公告状态")
            isAnnouncementActive = false
            announcementOnComplete?.invoke()
            announcementOnComplete = null
        }
    }

    // ==================== 公开状态查询 ====================

    /** 对话是否正在进行中（供 MainActivity 判断是否应强制切表情） */
    fun isConversationActive(): Boolean = isConversationActive

    // ==================== 入口 ====================

    /**
     * 播放公告类语音（打招呼等），走完整 TTS 路径
     */
    fun playAnnouncement(text: String, expression: RobotStatus? = null, onComplete: () -> Unit) {
        saveExpressionIfNeeded()
        expression?.let { setExpression(it) }
        ttsTriggered = false
        val gen = ++ttsGeneration
        isTTSPlaying = true
        ttsStartTime = System.currentTimeMillis()  // ★ 记录 TTS 开始时间
        streamingTTSStarted = true
        isAnnouncementActive = true
        announcementOnComplete = onComplete
        Log.d(TAG, "📢 播放公告: ${text.take(40)}...")
        streamingTTSClient.speak(text, createStreamingTTSCallback(gen))
        streamingTTSClient.finishInput()
    }

    /**
     * 应用角色配置并播报个性化开场白
     *
     * 从平板 Web 页面 POST /config 后调用。
     * 流程：更新 LLM 提示词 → 切换 TTS 音色 → 设表情 → 清历史 → 播开场白
     */
    fun applyConfigAndGreet(
        config: com.popkter.robotface.server.CharacterConfig,
        onStartExpression: RobotStatus? = null,
        onComplete: () -> Unit = {}
    ) {
        currentConfig = config

        // 1. 更新 LLM 系统提示词
        val newPrompt = config.buildSystemPrompt()
        chatClient.updateSystemPrompt(newPrompt)

        // 2. 切换 TTS 音色
        streamingTTSClient.updateVoice(config.voice)

        // 3. 设置初始表情
        val expression = onStartExpression
            ?: RobotStatus.allStates.find { it::class.simpleName == config.expression }
            ?: RobotStatus.allStates.find { it::class.simpleName == "Happiness" }
            ?: RobotStatus.allStates.first()  // 最终兜底，绝不会为 null
        setExpression(expression)

        // 4. 清空对话历史（新角色新开始）
        chatClient.clearHistory()

        // 5. 播报个性化开场白
        val greeting = config.buildGreeting()
        playAnnouncement(greeting, expression, onComplete)
    }

    /**
     * 重置配置 — 恢复默认系统提示词、默认音色、默认表情
     */
    fun resetConfig() {
        currentConfig = null
        chatClient.updateSystemPrompt(ChatClient.DEFAULT_SYSTEM_PROMPT)
        streamingTTSClient.updateVoice("longcheng_v3")
        chatClient.clearHistory()
        val ordinary = RobotStatus.allStates.find { it::class.simpleName == "Ordinary" }
        ordinary?.let { setExpression(it) }
        Log.d(TAG, "配置已重置")
    }

    /** ASR 识别到一句话后调用（WebSocket 回调线程） */
    fun handleTranscript(text: String) {
        // ★ 唤醒词打断后，ASR 的 final result（如"小萝。"）会紧随到达
        // 清洗后只剩标点（如"。"），不应触发新对话，直接忽略
        if (wakeWordJustInterrupted) {
            Log.w(TAG, "🚫 唤醒词打断后的 ASR 残留结果，忽略: $text")
            wakeWordJustInterrupted = false
            return
        }

        // ★ 防回声反馈循环：TTS 播放期间 / 刚结束时，ASR 拾取的是机器人自己的声音
        // 唤醒词打断不受影响（走 handlePartialResult → onWakeWordInterrupt）
        if (isTTSPlaying) {
            Log.w(TAG, "🚫 TTS 播放中，忽略 ASR 结果（防回声反馈）: $text")
            return
        }
        val now = System.currentTimeMillis()
        if (now < ttsEndCooldownUntil) {
            Log.w(TAG, "🚫 TTS 结束冷却中(${ttsEndCooldownUntil - now}ms)，忽略 ASR 结果（防回声反馈）: $text")
            return
        }

        // ★ 清洗唤醒词：ASR final transcript 可能包含唤醒词（如"小萝你好"）
        var cleanText = text
        for (ww in wakeWords) {
            cleanText = cleanText.replace(ww, "")
        }
        cleanText = cleanText.trim()
        if (cleanText.isEmpty()) {
            Log.d(TAG, "📝 ASR 结果清洗后为空（纯唤醒词），忽略: $text")
            return
        }
        Log.d(TAG, "📝 ASR 最终结果: $text → 清洗后: $cleanText")

        // ★ 清理公告状态（打招呼等）
        // 用户说话意味着公告应被终止，否则后续 gen 不匹配会导致状态卡死
        cleanupAnnouncementState()

        // ★ 停止当前 TTS 播放
        if (isTTSPlaying) {
            Log.d(TAG, "  停止当前流式 TTS 播放")
            streamingTTSClient.stop()
            isTTSPlaying = false
            streamingTTSStarted = false
        }

        // ★ 标记对话开始（递增代数，旧 TTS 回调将不再清理此标志）
        isConversationActive = true
        conversationGeneration++

        if (expressionBeforeTTS == null) {
            expressionBeforeTTS = getExpression()
        }
        setThink()

        chatClient.sendMessage(cleanText, object : ChatClient.ChatCallback {
            override fun onToolCall(name: String, arguments: String) = handleToolCall(name, arguments)
            override fun onSentence(text: String) = handleStreamingSentence(text)
            override fun onStreamComplete(content: String) = handleStreamComplete(content)
            override fun onError(message: String) {
                Log.e(TAG, "LLM 请求失败: $message")
                isConversationActive = false
                onLlError(message)
                restoreExpression()
            }
        })
    }

    fun destroy() {
        streamingTTSClient.destroy()
    }

    // ==================== 流式 TTS 处理 ====================

    /**
     * 处理 LLM 流式输出中检测到的每个句子
     * 第一句启动流式 TTS，后续句子追加
     */
    private fun handleStreamingSentence(text: String) {
        if (!streamingTTSStarted) {
            // 第一句：启动流式 TTS
            streamingTTSStarted = true
            saveExpressionIfNeeded()

            // ★ 不再在这里兜底设 Talk！
            // tool call（设表情）比 onSentence 晚到，这里设 Talk 会闪一下
            // 兜底设 Talk 移到 handleStreamComplete，此时 tool call 已全部到达

            val gen = ++ttsGeneration
            isTTSPlaying = true
            ttsStartTime = System.currentTimeMillis()  // ★ 记录 TTS 开始时间（看门狗用）
            Log.d(TAG, "🎙 流式 TTS 启动，首句: ${text.take(40)}...")
            streamingTTSClient.speak(text, createStreamingTTSCallback(gen))
        } else {
            // 后续句子：追加文本
            Log.d(TAG, "🎙 流式 TTS 追加: ${text.take(40)}...")
            streamingTTSClient.addText(text)
        }
    }

    // ==================== LLM 回调 ====================

    private fun handleToolCall(name: String, arguments: String) {
        when (name) {
            "set_expression" -> {
                try {
                    val args = JSONObject(arguments)
                    val expressionName = args.optString("expression", "")
                    val ttsText = args.optString("text", "")
                    val status = RobotStatus.allStates.find { it::class.simpleName == expressionName }

                    if (status != null && ttsText.isNotBlank()) {
                        Log.d(TAG, "✅ 表情: $expressionName, 文字: $ttsText")
                        saveExpressionIfNeeded()
                        setExpression(status)
                        ttsTriggered = true

                        // ★ 如果流式 TTS 已在运行，先停止（tool call 的文本要用指定表情重新说）
                        if (streamingTTSStarted) {
                            streamingTTSClient.stop()
                            streamingTTSStarted = false
                        }

                        val gen = ++ttsGeneration
                        isTTSPlaying = true
                        streamingTTSStarted = true
                        streamingTTSClient.speak(ttsText, createStreamingTTSCallback(gen))
                        streamingTTSClient.finishInput()
                    } else if (status != null) {
                        Log.d(TAG, "✅ 表情: $expressionName（无 text，等兜底）")
                        saveExpressionIfNeeded()
                        setExpression(status)
                    } else {
                        Log.w(TAG, "⚠️ 未知表情: $expressionName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 解析 tool_call 失败: ${e.message}")
                }
            }
            "head_script", "head_turn", "body_move" -> {
                try {
                    val action = JSONObject(arguments).optString("action", "")
                    if (action.isNotBlank()) {
                        Log.d(TAG, "🤖 动作: $name → $action")
                        sendCmd(name, action)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 解析动作指令失败: ${e.message}")
                }
            }
        }
    }

    private fun handleStreamComplete(content: String) {
        if (streamingTTSStarted && !ttsTriggered) {
            // 流式 TTS 已在运行，通知服务端合成结束
            Log.d(TAG, "LLM 流结束，通知流式 TTS finishInput")
            streamingTTSClient.finishInput()
            // ★ 如果 tool call 始终没设过表情，在这里兜底设 Talk
            // 此时 tool call 已经全部到达（SSE 流已结束），不会有表情冲突
            val talk = RobotStatus.allStates.find { it::class.simpleName == "Talk" }
            talk?.let { setExpression(it) }
        } else if (ttsTriggered) {
            ttsTriggered = false
            Log.d(TAG, "LLM 回复完成，TTS 已在 tool call 中触发")
        } else {
            // 兜底：LLM 没有通过 onSentence 推送过任何文本（如纯 tool call 无 content）
            val ttsText = if (content.isNotEmpty()) content else "好的"
            Log.d(TAG, "LLM 回复完成(兜底)，开始流式 TTS: ${ttsText.take(50)}...")
            saveExpressionIfNeeded()
            val talk = RobotStatus.allStates.find { it::class.simpleName == "Talk" }
            talk?.let { setExpression(it) }
            val gen = ++ttsGeneration
            isTTSPlaying = true
            ttsStartTime = System.currentTimeMillis()  // ★ 记录 TTS 开始时间
            streamingTTSStarted = true
            streamingTTSClient.speak(ttsText, createStreamingTTSCallback(gen))
            streamingTTSClient.finishInput()
        }
    }

    // ==================== TTS 回调 ====================

    private fun createStreamingTTSCallback(gen: Int) = object : StreamingTTSClient.Callback {
        // ★ 捕获创建时的对话代数，用于判断是否应清理 isConversationActive
        private val myConvGen = conversationGeneration
        // ★ TTS 是否真正开始播放（onStart 触发）。连接失败时 started=false，不应恢复表情
        private var started = false
        override fun onStart() {
            started = true
            Log.d(TAG, "  流式 TTS 开始播放 (gen=$gen) → 唤醒词检测已激活")
        }
        override fun onComplete() {
            if (gen != ttsGeneration) return
            Log.d(TAG, "  流式 TTS 播放完毕 (gen=$gen) → 唤醒词检测已关闭")
            ttsStartTime = 0L  // ★ 重置看门狗
            isTTSPlaying = false
            streamingTTSStarted = false
            ttsEndCooldownUntil = System.currentTimeMillis() + TTS_END_COOLDOWN_MS
            // ★ 对话 TTS 完成，清理对话状态
            if (myConvGen == conversationGeneration) {
                isConversationActive = false
            }
            restoreExpression()
        }
        override fun onError(message: String) {
            if (gen != ttsGeneration) return
            Log.w(TAG, "  流式 TTS 出错: $message (gen=$gen)")
            ttsStartTime = 0L  // ★ 重置看门狗
            isTTSPlaying = false
            streamingTTSStarted = false
            ttsEndCooldownUntil = System.currentTimeMillis() + TTS_END_COOLDOWN_MS
            // ★ 对话 TTS 出错，清理对话状态
            if (myConvGen == conversationGeneration) {
                isConversationActive = false
            }
            // ★ 只有 TTS 真正播放过才恢复表情；连接失败时不恢复（防止覆盖 Think）
            if (started) {
                restoreExpression()
            } else {
                Log.w(TAG, "  TTS 未实际播放，跳过表情恢复（保持当前 Think/Happiness 等）")
            }
        }
    }

    // ==================== 安全看门狗 ====================

    /** 强制重置所有 TTS 相关状态（看门狗超时时调用） */
    private fun forceResetTTSState() {
        streamingTTSClient.stop()
        isTTSPlaying = false
        streamingTTSStarted = false
        isConversationActive = false
        ttsStartTime = 0L
        ttsEndCooldownUntil = System.currentTimeMillis() + TTS_END_COOLDOWN_MS
        restoreExpression()
        if (isAnnouncementActive) {
            isAnnouncementActive = false
            announcementOnComplete?.invoke()
            announcementOnComplete = null
        }
    }

    // ==================== 公告状态清理 ====================

    /** 清理公告状态（打招呼等），在 handleTranscript 开头调用 */
    private fun cleanupAnnouncementState() {
        if (isAnnouncementActive) {
            Log.d(TAG, "  清理公告状态（用户开始对话）")
            isAnnouncementActive = false
            announcementOnComplete?.invoke()
            announcementOnComplete = null
        }
    }

    // ==================== 表情状态机 ====================

    private fun saveExpressionIfNeeded() {
        if (expressionBeforeTTS == null) {
            expressionBeforeTTS = getExpression()
        }
    }

    private fun setThink() {
        val think = RobotStatus.allStates.find { it::class.simpleName == "Think" }
        if (think != null) setExpression(think)
    }

    private fun restoreExpression() {
        val prev = expressionBeforeTTS
        expressionBeforeTTS = null
        if (prev != null) {
            Log.d(TAG, "  恢复表情: ${prev::class.simpleName}")
            setExpression(prev)
        }
    }
}
