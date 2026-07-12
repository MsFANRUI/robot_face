package com.popkter.robotface

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLM 对话客户端
 *
 * 职责：
 * 1. 将用户的语音文本（来自 ASR）发送给 LLM 大模型
 * 2. 流式接收 LLM 的回复，实时打印到 Logcat
 * 3. 如果 LLM 决定切换表情，通过 tool call 回调给调用方执行
 *
 * 使用 OpenAI 兼容的流式 Chat Completion API
 */
class ChatClient(
    private val apiKey: String,                                          // API 密钥
    private val baseUrl: String = "https://ai-gateway.roboparty.com/v1", // API 地址
    private val model: String = "chat-fast"                              // 模型名称
) {
    companion object {
        private const val TAG = "ChatLLM"
        // ==================== 摘要压缩配置 ====================
        // 历史消息数超过此值时触发摘要（防止 token 超出模型上下文窗口）
        private const val MAX_HISTORY_SIZE = 30
        // 触发摘要时，保留最近的消息数，只压缩更早的
        private const val KEEP_RECENT_COUNT = 10
    }

    // ==================== HTTP 客户端 ====================
    // OkHttp 用于发送 HTTP 请求，readTimeout=0 表示不超时（流式响应需要长时间保持连接）
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .build()

    // Handler 用于将 tool call 回调切到主线程执行（因为 updateStatus 需要在主线程）
    private val handler = Handler(Looper.getMainLooper())

    // ==================== 对话历史 ====================
    // 保存多轮对话记录，让 LLM 能记住上下文
    // 格式: [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}, ...]
    // ⚠️ 多线程访问（主线程 sendMessage + OkHttp 回调线程 onResponse），需加锁保护
    private val conversationHistory = mutableListOf<JSONObject>()
    private val historyLock = Any()

    // ==================== 请求取消机制 ====================
    // 记录当前正在进行的 HTTP 请求，发新请求时自动取消上一个
    // 防止用户快速说话时多个请求并发导致回复混乱
    @Volatile
    private var currentCall: okhttp3.Call? = null
    // 标记是否为主动取消（区分主动取消和真正的网络错误）
    @Volatile
    private var isCancelling = false

    // ==================== System Prompt ====================
    // 系统提示词：告诉 LLM 它的角色、可用表情、以及要积极使用表情工具
    private val systemPrompt = JSONObject().apply {
        put("role", "system")
        put("content", """你是一个友好的机器人助手。请用简短自然的中文回复用户。
你可以通过 set_expression 工具来改变自己的面部表情，表达你的情感。
可用表情：Ordinary(默认)、Sadness(伤心)、Happiness(开心)、Music(音乐)、Coldness(冷)、Speechless(无语)、Angry(生气)、Think(思考)、Talk(说话)、Singing(唱歌)、SparkLight(灵感)、Coffee(咖啡)、Focus(专注)。
请积极使用表情工具来表达你的情感。""".trimIndent())
    }

    // ==================== Tool 定义 ====================
    // 定义 set_expression 工具，让 LLM 可以主动调用它来切换表情
    // 这是 OpenAI function calling 的标准格式
    private val setExpressionTool = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", "set_expression")
            put("description", "设置机器人的面部表情，用于表达你的情感反应")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("expression", JSONObject().apply {
                        put("type", "string")
                        put("description", "表情名称")
                        // enum 限制 LLM 只能选择这些预定义的表情
                        put("enum", JSONArray().apply {
                            put("Ordinary"); put("Sadness"); put("Happiness")
                            put("Music"); put("Coldness"); put("Speechless")
                            put("Angry"); put("Think"); put("Talk")
                            put("Singing"); put("SparkLight"); put("Coffee"); put("Focus")
                        })
                    })
                })
                put("required", JSONArray().apply { put("expression") })
            })
        })
    }

    // ==================== 回调接口 ====================
    /**
     * LLM 返回 tool call 时的回调接口
     * 调用方（MainActivity）实现此接口来执行表情切换
     */
    interface ChatCallback {
        fun onToolCall(name: String, arguments: String)
        // LLM 流式回复结束后触发，传入完整回复文本（用于 TTS 播放）
        fun onStreamComplete(content: String) {}
        // LLM 请求失败时触发（网络错误、API 错误等）
        fun onError(message: String) {}
    }

    // ==================== 核心方法：发送消息 ====================
    /**
     * 将用户消息发送给 LLM，流式接收回复
     *
     * @param userMessage 用户说的话文本（来自 ASR 识别结果）
     * @param callback    回调，当 LLM 调用 tool 时触发
     */
    fun sendMessage(userMessage: String, callback: ChatCallback) {
        // 0. 取消上一个未完成的请求（防止多请求并发混乱）
        currentCall?.let { prevCall ->
            if (!prevCall.isCanceled()) {
                Log.d(TAG, "取消上一个未完成的请求")
                isCancelling = true
                prevCall.cancel()
            }
        }
        isCancelling = false

        // 1. 将用户消息加入对话历史（加锁保护）
        synchronized(historyLock) {
            conversationHistory.add(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        // 2. 构建请求体（在锁内快照历史，避免构建过程中被修改）
        // 注意：不能传 extra_body 或 reasoning_effort，否则 API 会报错
        val historySnapshot: List<JSONObject>
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(systemPrompt)                              // 系统提示词
                synchronized(historyLock) {
                    historySnapshot = conversationHistory.toList()
                    historySnapshot.forEach { put(it) }         // 历史对话快照
                }
            })
            put("tools", JSONArray().apply { put(setExpressionTool) }) // 注册工具
            put("stream", true)                                // 开启流式响应
        }

        Log.d(TAG, ">>> 发送: $userMessage")

        // 3. 构建 HTTP 请求
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create("application/json".toMediaType(), requestBody.toString()))
            .build()

        // 4. 异步发送请求，在回调中处理流式响应
        val call = client.newCall(request)
        currentCall = call  // 保存引用，下次发请求时可以取消

        call.enqueue(object : okhttp3.Callback {
            // 请求失败（网络错误等）
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                // 如果是主动取消的，不打错误日志，不处理
                if (isCancelling || call.isCanceled()) {
                    Log.d(TAG, "请求已取消，忽略本次失败")
                    return
                }
                Log.e(TAG, "请求失败: ${e.message}")
                // 通知调用方请求失败（切到主线程），让 UI 可以给出反馈
                handler.post { callback.onError("网络请求失败: ${e.message}") }
            }

            // 请求成功，开始读取流式响应
            override fun onResponse(call: okhttp3.Call, response: Response) {
                // 检查 HTTP 状态码
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "无响应体"
                    Log.e(TAG, "API 错误: code=${response.code}, message=${response.message}, body=$errorBody")
                    return
                }
                
                val source = response.body?.source() ?: return
                Log.d(TAG, "API 响应成功，开始读取流式数据...")

                // tool_calls 累积器：流式返回时 tool call 参数是分段到达的
                // 需要按 index 累积，等流结束后才能完整解析
                val toolCallsMap = mutableMapOf<Int, ToolCallAccumulator>()
                // 累积完整的回复文本（用于保存到对话历史）
                val fullContent = StringBuilder()

                try {
                    // 逐行读取 SSE（Server-Sent Events）流
                    // 每行格式: "data: {json}" 或 "data: [DONE]"
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") break  // 流结束标志

                        val json = JSONObject(data)
                        val choices = json.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        // delta 包含本次增量数据
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue

                        // ---- 处理文本内容 ----
                        // delta.content 有值时，说明 LLM 在输出文字
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty() && content != "null") {
                            // 累积完整回复（流结束后统一输出到 Logcat）
                            fullContent.append(content)
                        }

                        // ---- 处理 tool_calls ----
                        // delta.tool_calls 有值时，说明 LLM 在调用工具
                        // 流式返回中，一个 tool call 可能分多个 chunk 到达
                        val tcArray = delta.optJSONArray("tool_calls")
                        if (tcArray != null) {
                            for (i in 0 until tcArray.length()) {
                                val tc = tcArray.getJSONObject(i)
                                val index = tc.optInt("index", 0)
                                // 按 index 获取或创建累积器
                                val acc = toolCallsMap.getOrPut(index) { ToolCallAccumulator() }

                                val fn = tc.optJSONObject("function")
                                if (tc.has("id")) acc.id = tc.getString("id")       // 工具调用 ID
                                if (fn != null) {
                                    if (fn.has("name")) acc.name = fn.getString("name")           // 工具名称
                                    if (fn.has("arguments")) acc.arguments.append(fn.getString("arguments")) // 参数（分段累积）
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "流解析异常: ${e.message}")
                }

                // 检查是否被取消了（新请求到来时会取消）
                if (call.isCanceled()) {
                    Log.d(TAG, "请求已被取消，停止读取流式数据")
                    return
                }

                // ==================== 流结束后处理 ====================

                // 输出完整的 LLM 回复到 Logcat
                if (fullContent.isNotEmpty()) {
                    Log.d(TAG, "LLM 回复: $fullContent")
                }

                // 5. 将 LLM 的完整回复保存到对话历史（加锁保护）
                val assistantMsg = JSONObject().apply {
                    put("role", "assistant")
                    if (fullContent.isNotEmpty()) put("content", fullContent.toString())
                    if (toolCallsMap.isNotEmpty()) {
                        put("tool_calls", JSONArray().apply {
                            toolCallsMap.values.forEach { acc ->
                                put(JSONObject().apply {
                                    put("id", acc.id)
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", acc.name)
                                        put("arguments", acc.arguments.toString())
                                    })
                                })
                            }
                        })
                    }
                }
                synchronized(historyLock) {
                    conversationHistory.add(assistantMsg)

                    // 6. 将 tool 响应加入对话历史（API 要求每个 tool_call 必须有对应的 tool 回复）
                    toolCallsMap.values.forEach { acc ->
                        if (acc.name.isNotEmpty()) {
                            conversationHistory.add(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", acc.id)
                                put("content", "success")
                            })
                        }
                    }
                }

                // 6b. 执行 tool calls（切到主线程，因为回调里要操作 UI）
                toolCallsMap.values.forEach { acc ->
                    if (acc.name.isNotEmpty()) {
                        Log.d(TAG, "🔧 Tool call: ${acc.name}(${acc.arguments})")
                        handler.post { callback.onToolCall(acc.name, acc.arguments.toString()) }
                    }
                }

                // 7. 检查是否需要摘要压缩（防止 token 超限）
                summarizeAndTrim()

                // 8. 通知调用方：LLM 回复已完成（切到主线程，用于触发 TTS 播放）
                // 如果 LLM 只返回表情（无文字），默认用"好的"触发 TTS
                val ttsContent = if (fullContent.isNotEmpty()) fullContent.toString() else "好的"
                handler.post { callback.onStreamComplete(ttsContent) }
            }
        })
    }

    // ==================== 对话历史摘要压缩 ====================
    /**
     * 当对话历史过长时，将旧消息压缩为一条摘要，
     * 保留最近的消息不变，防止 token 超出模型上下文窗口。
     *
     * 流程：
     * 1. 历史消息数 > MAX_HISTORY_SIZE 时触发
     * 2. 取出旧消息（前面的），发给 LLM 生成摘要
     * 3. 用一条摘要消息替换旧消息
     * 4. 保留最近 KEEP_RECENT_COUNT 条消息
     */
    private fun summarizeAndTrim() {
        val oldMessages: List<JSONObject>
        val recentMessages: List<JSONObject>
        val currentSize: Int

        synchronized(historyLock) {
            if (conversationHistory.size <= MAX_HISTORY_SIZE) return
            currentSize = conversationHistory.size
            val splitIndex = conversationHistory.size - KEEP_RECENT_COUNT
            oldMessages = conversationHistory.subList(0, splitIndex).toList()
            recentMessages = conversationHistory.subList(splitIndex, conversationHistory.size).toList()
        }

        Log.d(TAG, "📝 对话历史过长(${currentSize}条)，开始摘要压缩...")

        val summary = callSummarizeAPI(oldMessages)
        if (summary != null) {
            synchronized(historyLock) {
                conversationHistory.clear()
                // 用一条摘要消息替代所有旧消息
                conversationHistory.add(JSONObject().apply {
                    put("role", "system")
                    put("content", "以下是之前对话的摘要：\n$summary")
                })
                conversationHistory.addAll(recentMessages)
            }
            Log.d(TAG, "✅ 摘要压缩完成，历史从${oldMessages.size + recentMessages.size}条缩减为${conversationHistory.size}条")
        }
    }

    /**
     * 调用 LLM API 生成对话摘要（非流式，同步调用）
     * 将旧对话内容发给模型，让它用 2-3 句话总结关键信息
     */
    private fun callSummarizeAPI(messages: List<JSONObject>): String? {
        return try {
            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "请用2-3句话总结以下对话的关键信息，保留人名、重要事实和情感变化。只输出摘要，不要加任何前缀。")
                    })
                    // 只提取 user 和 assistant 的文本内容（跳过 tool 消息）
                    messages.forEach { msg ->
                        val role = msg.getString("role")
                        val content = msg.optString("content", "")
                        if (content.isNotEmpty() && (role == "user" || role == "assistant")) {
                            put(JSONObject().apply {
                                put("role", role)
                                put("content", content)
                            })
                        }
                    }
                })
                put("stream", false)
            }

            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create("application/json".toMediaType(), requestBody.toString()))
                .build()

            // 同步调用（在 OkHttp 回调线程中执行，本身就是后台线程）
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (response.isSuccessful && body != null) {
                val json = JSONObject(body)
                json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
            } else {
                Log.e(TAG, "摘要 API 错误: code=${response.code}, body=$body")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "摘要生成失败: ${e.message}")
            null
        }
    }

    // 清空对话历史（重新开始新对话）
    fun clearHistory() {
        synchronized(historyLock) {
            conversationHistory.clear()
        }
    }

    // ==================== 内部数据类 ====================
    /**
     * Tool Call 累积器
     * 因为流式响应中 tool call 的参数是分段到达的，
     * 比如 {"expression":"Hap 和 {"pression":"piness"} 可能分两次到达，
     * 需要用 StringBuilder 累积拼接成完整的 JSON 字符串
     */
    private data class ToolCallAccumulator(
        var id: String = "",                    // 工具调用 ID
        var name: String = "",                  // 工具名称，如 "set_expression"
        val arguments: StringBuilder = StringBuilder() // 参数 JSON 字符串（分段累积）
    )
}
