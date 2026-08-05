package com.popkter.robotface

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import com.popkter.robotface.speech.StreamingTTSClient
import com.popkter.robotface.orchestrator.ConversationOrchestrator
import com.popkter.robotface.net.UdpClient
import com.popkter.robotface.vision.FaceEvent
import com.popkter.robotface.vision.FaceEventListener
import com.popkter.robotface.vision.FaceInfo
import com.popkter.robotface.vision.FaceVisionManager
import com.popkter.robot.status.Happiness
import com.popkter.robot.status.Ordinary
import com.popkter.robotface.server.CharacterConfig
import com.popkter.robotface.server.ConfigServer
import java.io.IOException

/**
 * 机器人主 Activity
 *
 * 职责（只做组装）：
 * 1. 创建各模块（ASR / LLM / TTS / 录音 / 编排器）
 * 2. Compose UI
 * 3. 权限管理
 *
 * 对话全链路逻辑在 ConversationOrchestrator 中
 */
class MainActivity : ComponentActivity() {
    private val robotViewModel by viewModels<RobotViewModel>()

    // ==================== 模块 ====================

    private val chatClient = ChatClient(apiKey = BuildConfig.LLM_API_KEY)
    private val streamingTTSClient = StreamingTTSClient(
        apiKey = BuildConfig.TTS_API_KEY,
        context = this  // ★ 用于查找 USB 音频设备（双路输出）
    )
    private val orchestrator: ConversationOrchestrator by lazy {
        ConversationOrchestrator(
            chatClient = chatClient,
            streamingTTSClient = streamingTTSClient,
            audioRecorder = audioRecorder,
            setExpression = { status -> runOnUiThread { robotViewModel.updateStatus(status) } },
            getExpression = { robotViewModel.targetStatus },
            onLlError = { msg ->
                runOnUiThread { Toast.makeText(this, "网络出错了，请稍后再试", Toast.LENGTH_SHORT).show() }
            },
            // ↓↓↓ 新增 ↓↓↓
            sendCmd = { category, action ->
                udpClient.sendRobotCmd(category, action)
            }
        )
    }



    private val asrClient = QwenASRClient(
        apiKey = BuildConfig.ASR_API_KEY,
        onTranscript = { text -> orchestrator.handleTranscript(text) },
        onPartialResult = { partial -> orchestrator.handlePartialResult(partial) }  // ★ 唤醒词检测
    )

    private val audioRecorder: ContinuousAudioRecorder by lazy {
        ContinuousAudioRecorder(
            context = this,  // ★ 传入 context 用于查找 USB 音频设备
            onAudioChunk = { pcmData -> asrClient.sendAudio(pcmData) }
        )
    }

    // ==================== HTTP 配置服务器（接收平板 Web 页面配置） ====================

    private val configServer: ConfigServer by lazy {
        ConfigServer(
            port = ConfigServer.DEFAULT_PORT,
            onConfigReceived = { config ->
                Log.d(TAG, "收到角色配置: robotName=${config.robotName}, voice=${config.voice}, expression=${config.expression}")
                orchestrator.applyConfigAndGreet(
                    config = config,
                    onStartExpression = Happiness
                ) {
                    Log.d(TAG, "个性化开场白播放完毕")
                }
            },
            onReset = {
                Log.d(TAG, "收到重置请求")
                orchestrator.resetConfig()
                runOnUiThread {
                    robotViewModel.updateStatus(Ordinary)
                }
            },
            getCurrentConfig = { orchestrator.currentConfig }
        )
    }

    // ==================== UDP(x5_server 机器人身体端) ====================

    // 下行:expression 切表情 / motor_status 打日志 / heartbeat_ack 打日志
    private val udpClient = UdpClient(
        host = BuildConfig.UDP_HOST,
        port = BuildConfig.UDP_PORT,
        onExpression = { emotion ->
            runOnUiThread {
                val status = RobotStatus.allStates.find { it::class.simpleName == emotion }
                if (status != null) {
                    robotViewModel.updateStatus(status)
                } else {
                    Log.w(TAG, "收到未知表情名: $emotion")
                }
            }
        },
        onMotorStatus = { pitch, yaw -> Log.d(TAG, "motor_status pitch=$pitch yaw=$yaw") },
        onHeartbeatAck = { ts -> Log.d(TAG, "heartbeat_ack $ts") }
    )

    // ==================== 视觉模块(人脸检测 + 跟踪) ====================

    private val faceVisionManager: FaceVisionManager by lazy {
        FaceVisionManager(
            context = this,
            lifecycleOwner = this,
            onFacesUpdated = { faces -> handleFaceUpdate(faces) },
            faceEventListener = faceEventListener
        )
    }

    /** 人脸事件监听器（在主线程回调） */
    private val faceEventListener = object : FaceEventListener {
        override fun onFaceEvent(event: FaceEvent, faces: List<FaceInfo>) {
            when (event) {
                FaceEvent.FACE_APPEARED -> onFaceAppeared(faces)
                FaceEvent.FACE_LOST -> onFaceLost()
            }
        }
    }

    // ==================== 打招呼状态 ====================

    /** 是否正在打招呼（防止重复触发） */
    @Volatile
    private var isGreeting = false

    /** 打招呼会话计数器：每次触发打招呼 +1，用于区分当前 TTS 是否属于本次打招呼 */
    private var greetingSession = 0L

    /** 打招呼冷却时间 (ms)：上次打招呼结束后至少等这么久才能再次触发 */
    private val greetingCooldownMs = 5_000L

    /** 上次打招呼完成的时间戳 */
    private var lastGreetingCompleteTimeMs = 0L

    /** 招呼语列表，随机选择 */
    private val greetings = listOf(
        "你好呀，我看到你了！",
        "嗨，很高兴见到你！",
        "你好，今天过得怎么样？",
        "哈喽，有什么我可以帮你的吗？"
    )

    /** 人脸跟踪结果回调（在分析线程），后续在此接入 UDP 发送 */
    private fun handleFaceUpdate(faces: List<FaceInfo>) {
        if (faces.isNotEmpty()) {
            val sb = StringBuilder("检测到 ${faces.size} 张人脸:")
            faces.forEach { face ->
                sb.append(" [id=${face.trackingId} x=${"%.2f".format(face.offsetX)} y=${"%.2f".format(face.offsetY)} d=${"%.2f".format(face.distanceMeters)}m]")
            }
            Log.d(TAG, sb.toString())
        }
        // 选择 ID 最小的脸作为主目标发送给 X5（无人脸时传 null → t=0）
        val primaryFace = faces.minByOrNull { it.trackingId }
        udpClient.updateFaceTrack(primaryFace)
    }

    /** 人脸出现事件：触发打招呼 */
    private fun onFaceAppeared(faces: List<FaceInfo>) {
        val now = System.currentTimeMillis()

        // 检查是否正在打招呼
        if (isGreeting) {
            Log.d(TAG, "正在打招呼，跳过")
            return
        }

        // 检查冷却时间
        if (now - lastGreetingCompleteTimeMs < greetingCooldownMs) {
            Log.d(TAG, "打招呼冷却中，跳过")
            return
        }

        Log.d(TAG, "🟢 触发打招呼，人脸数=${faces.size}")
        triggerGreeting()
    }

    /** 人脸消失事件：恢复默认表情 + 重置打招呼状态 */
    private fun onFaceLost() {
        Log.d(TAG, "🔴 人脸消失，重置打招呼状态")
        isGreeting = false
        // ★ 对话进行中时不强制切表情，由 TTS 回调的 restoreExpression 自然恢复
        if (!orchestrator.isConversationActive()) {
            runOnUiThread {
                robotViewModel.updateStatus(Ordinary)
            }
        } else {
            Log.d(TAG, "  对话进行中，跳过强制切表情")
        }
    }

    /** 触发打招呼：通过 orchestrator 播放（表情 + TTS + 打断检测统一由 orchestrator 管理） */
    private fun triggerGreeting() {
        isGreeting = true
        val session = ++greetingSession

        // 随机选择招呼语
        val greeting = greetings.random()
        Log.d(TAG, "打招呼 TTS (session=$session): $greeting")

        // 通过 orchestrator 播放：先保存当前表情 → 切 Happiness → TTS → 恢复
        orchestrator.playAnnouncement(greeting, expression = Happiness) {
            // TTS 播放完毕或被用户打断
            onGreetingComplete(session)
        }
    }

    /** 打招呼完成后的清理（表情已由 orchestrator 恢复） */
    private fun onGreetingComplete(session: Long) {
        isGreeting = false
        lastGreetingCompleteTimeMs = System.currentTimeMillis()
        Log.d(TAG, "打招呼完成 (session=$session)")
    }



    // ==================== 生命周期 ====================

    override fun onDestroy() {
        super.onDestroy()
        configServer.stop()
        orchestrator.destroy()
        audioRecorder.stopRecording()
        asrClient.disconnect()
        faceVisionManager.destroy()

        udpClient.destroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

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
                    // 眼睛
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .scale(1.5f)
                    ) {
                        Robot(robotViewModel)
                    }

                    // 表情按钮
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
                            Text(
                                text = state::class.simpleName ?: "",
                                color = if (isSelected) Color.White else Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .background(
                                        if (isSelected) Color.Cyan.copy(alpha = 0.5f) else Color.DarkGray,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .clickable { robotViewModel.updateStatus(state) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // HTTP 配置服务器:启动 NanoHTTPd（接收平板 Web 页面的角色配置）
        try {
            configServer.start()
            Log.d(TAG, "HTTP 配置服务器已启动，端口: ${ConfigServer.DEFAULT_PORT}")
        } catch (e: IOException) {
            Log.e(TAG, "启动 HTTP 配置服务器失败: ${e.message}")
        }

        // UDP 联调:启动收发 + 陀螺仪上报(无需权限,与音频权限门解耦)
        udpClient.start()

        // 权限检查 → 启动 ASR + 录音
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

        // 权限检查 → 启动视觉模块
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Camera permission already granted, starting vision")
            faceVisionManager.start()
        } else {
            Log.d(TAG, "Requesting CAMERA permission")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("QwenASR", "Permission granted, starting ASR")
                    asrClient.connect()
                    audioRecorder.startRecording()
                } else {
                    Log.e("QwenASR", "Permission denied")
                }
            }
            REQUEST_CAMERA -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Camera permission granted, starting vision")
                    faceVisionManager.start()
                } else {
                    Log.e(TAG, "Camera permission denied")
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val REQUEST_CAMERA = 1002
    }
}
