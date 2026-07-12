package com.popkter.robotface

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.popkter.robot.Robot
import com.popkter.robot.viewmodel.RobotViewModel
import com.popkter.robotface.ui.theme.RobotFaceTheme
import com.popkter.robot.status.RobotStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.scale
import com.popkter.robotface.speech.QwenASRClient
import com.popkter.robotface.speech.ContinuousAudioRecorder
import com.popkter.robotface.BuildConfig

class MainActivity : ComponentActivity() {
    private val robotViewModel by viewModels<RobotViewModel>()

    // ==================== LLM 对话客户端 ====================
    private val chatClient = ChatClient(
        apiKey = BuildConfig.LLM_API_KEY
    )

    // ==================== TTS 语音合成客户端 ====================
    private val ttsClient = TTSClient(
        apiKey = BuildConfig.TTS_API_KEY
    )

    // TTS 播放前记住当前表情，播完后恢复（类型安全，用 RobotStatus?）
    private var expressionBeforeTTS: RobotStatus? = null
    // MONITOR 日志：记录上次输出的音量值，只在值变化时输出
    private var lastLoggedVolume: Float = -1f

    // ==================== ASR 语音识别客户端 ====================
    // 负责实时语音识别，识别到一句话后触发 onTranscript 回调
    private val asrClient = QwenASRClient(
        apiKey = BuildConfig.ASR_API_KEY,
        onTranscript = { text ->
            // ASR 识别到一句话后，发送给 LLM 进行对话
            // LLM 的回复会流式打印到 Logcat，同时可能通过 tool call 切换表情
            chatClient.sendMessage(text, object : ChatClient.ChatCallback {
                override fun onToolCall(name: String, arguments: String) {
                    // 当 LLM 调用 tool 时，此方法会在主线程被触发
                    if (name == "set_expression") {
                        try {
                            // 解析 tool call 的参数，如 {"expression":"Happiness"}
                            val args = org.json.JSONObject(arguments)
                            val expressionName = args.getString("expression")
                            // 根据名称找到对应的 RobotStatus 对象
                            val status = RobotStatus.allStates.find {
                                it::class.simpleName == expressionName
                            }
                            if (status != null) {
                                Log.d("ChatLLM", "✅ 切换表情: $expressionName")
                                // ⚠️ 关键：在 updateStatus 之前保存当前表情
                                // 因为 updateStatus 是异步的（IO线程），而 onStart 读 value 时可能还没更新
                                // 这样 TTS 播放完恢复的就是正确的表情，而不是被竞态覆盖的旧值
                                if (expressionBeforeTTS == null) {
                                    expressionBeforeTTS = robotViewModel.robotStatus.value
                                }
                                robotViewModel.updateStatus(status)
                            } else {
                                Log.w("ChatLLM", "⚠️ 未知表情: $expressionName")
                            }
                        } catch (e: Exception) {
                            Log.e("ChatLLM", "❌ 解析 tool_call 参数失败: ${e.message}")
                        }
                    }
                }

                override fun onStreamComplete(content: String) {
                    // LLM 回复完成后，触发 TTS 语音播放
                    Log.d("TTS", "LLM 回复完成，开始 TTS 播放")
                    ttsClient.speak(content, object : TTSClient.TTSCallback {
                        override fun onStart() {
                            // 开始播放：切到 MONITOR 模式（防止 TTS 声音被 ASR 识别）
                            Log.d("TTS", "TTS 开始播放，切到 Talk 表情 + MONITOR 模式")
                            lastLoggedVolume = -1f  // 重置日志记录
                            audioRecorder.startMonitoring { volume ->
                                // 打断检测：音量超过阈值时停止 TTS
                                // 日志只在值变化超过 0.005 时输出，避免刷屏
                                if (kotlin.math.abs(volume - lastLoggedVolume) > 0.005f) {
                                    Log.d("TTS", "MONITOR 音量: ${"%.3f".format(volume)} / 阈值: ${"%.3f".format(INTERRUPT_THRESHOLD)}")
                                    lastLoggedVolume = volume
                                }
                                if (volume > INTERRUPT_THRESHOLD) {
                                    Log.d("TTS", "检测到用户打断(volume=${"%.3f".format(volume)})，停止 TTS")
                                    ttsClient.stop()
                                    audioRecorder.stopMonitoring()
                                    restoreExpression()  // 统一恢复表情
                                }
                            }
//                            runOnUiThread {
//                                // expressionBeforeTTS 已在 onToolCall 中保存，这里不再覆盖
//                                // 如果没有 tool call（纯对话），才在此处保存
//                                if (expressionBeforeTTS == null) {
//                                    expressionBeforeTTS = robotViewModel.robotStatus.value
//                                }
//                                val talkStatus = RobotStatus.allStates.find {
//                                    it::class.simpleName == "Talk"
//                                }
//                                if (talkStatus != null) robotViewModel.updateStatus(talkStatus)
//                            }
                        }

                        override fun onComplete() {
                            // 播放完成：切回 NORMAL 模式，恢复之前的表情
                            Log.d("TTS", "TTS 播放完成，恢复 NORMAL 模式")
                            audioRecorder.stopMonitoring()
                            restoreExpression()  // 统一恢复表情
                        }

                        override fun onError(message: String) {
                            Log.e("TTS", "TTS 播放出错: $message")
                            audioRecorder.stopMonitoring()
                            restoreExpression()  // 出错也要恢复表情
                        }
                    })
                }
            })
        }
    )

    // ==================== 表情恢复辅助方法 ====================
    // 统一处理表情恢复逻辑，打断/完成/出错都调这个方法
    private fun restoreExpression() {
        val prev = expressionBeforeTTS
        expressionBeforeTTS = null
        if (prev != null) {
            runOnUiThread {
                robotViewModel.updateStatus(prev)
            }
        }
    }

    // ==================== 录音器 ====================
    // 持续采集麦克风音频，发送给 ASR 进行实时识别
    // 使用 lazy 延迟初始化，避免与 asrClient 的循环引用导致编译错误
    private val audioRecorder: ContinuousAudioRecorder by lazy {
        ContinuousAudioRecorder { pcmData: ByteArray ->
            asrClient.sendAudio(pcmData)
        }
    }



    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001
        // 打断检测阈值：TTS 播放期间，麦克风音量超过此值视为用户打断
        private const val INTERRUPT_THRESHOLD = 0.03f
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.stopRecording()
        asrClient.disconnect()
        ttsClient.destroy()  // 停止播放 + 释放协程资源
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        //hide system ui
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())
        insetsController.hide(WindowInsetsCompat.Type.navigationBars())


        setContent {
            RobotFaceTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    // 上半部分：眼睛（占满剩余空间）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .scale(1.5f)
                    ) {
                        Robot(robotViewModel)
                    }

                    // 下半部分：表情按钮横向滚动列表（占比小）
                    val currentStatus by robotViewModel.robotStatus.collectAsState()
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .background(Color(0xFF111111)),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        items(RobotStatus.allStates) { state ->
                            val isSelected = currentStatus == state
                            val bgColor = if (isSelected) Color.Cyan.copy(alpha = 0.5f) else Color.DarkGray
                            Text(
                                text = state::class.simpleName ?: state.toString(),
                                color = if (isSelected) Color.White else Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .background(bgColor, RoundedCornerShape(6.dp))
                                    .clickable { robotViewModel.updateStatus(state) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
        // 先检查录音权限，授权后再连接 ASR 和开始录音
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("QwenASR", "Permission already granted, starting ASR")
            asrClient.connect()
            audioRecorder.startRecording()
        } else {
            Log.d("QwenASR", "Requesting RECORD_AUDIO permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("QwenASR", "Permission granted, starting ASR")
                asrClient.connect()
                audioRecorder.startRecording()
            } else {
                Log.e("QwenASR", "Permission denied! Cannot record audio")
            }
        }
    }
}
