package com.popkter.robotface.net

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

import com.popkter.robotface.vision.FaceInfo

/**
 * UDP 客户端 —— 与 x5_server(0.0.0.0:8765)双向通信。
 *
 * 发送架构:统一消息队列
 *   - 一个 sendLoop 协程按序发送,保证消息顺序
 *   - 周期消息(heartbeat):sendLoop 按 3s 节拍发送
 *   - 事件消息(robot_cmd / expression_state 等):Channel(CONFLATED) 入队


 *
 * 接收架构:独立 receiveLoop 协程
 *
 * 详见 robot_shared/PROTOCOL.md。
 */
class UdpClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 8765,
    private val heartbeatIntervalMs: Long = 3000L,
    private val onExpression: (String) -> Unit = {},
    private val onMotorStatus: (Float, Float) -> Unit = { _: Float, _: Float -> },
    private val onHeartbeatAck: (Long) -> Unit = {}
) {
    companion object {
        private const val TAG = "UdpClient"
        private const val RECV_BUF_SIZE = 64 * 1024
        private const val SEND_TICK_MS = 100L  // 发送循环节拍
        private const val FACE_TRACK_INTERVAL_MS = 40L  // 25Hz = 40ms
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(0)

    @Volatile
    private var running = false
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null

    private var receiveJob: Job? = null
    private var sendJob: Job? = null

    // ==================== 统一发送队列 ====================

    /** 事件消息队列(CONFLATED:同类型只保留最新,防积压) */
    private val sendChannel = Channel<ByteArray>(Channel.CONFLATED)

    // ---- 周期消息:volatile 最新值,sendLoop 按节拍读取发送 ----

    /** 人脸跟踪数据(最新值,sendLoop 按 25Hz 发送) */
    @Volatile
    private var latestFaceTrackPayload: ByteArray = MessageProtocol.buildFaceTrack(null)
    private var lastFaceTrackSendTime = 0L
    // ★ Bug 14 修复：face_track 日志节流（每 5 秒最多打一次）
    private var lastFaceTrackLogTime = 0L

    /** 启动:建 socket + 收循环 + 统一发送循环。 */
    fun start() {
        if (running) return
        try {
            socket = DatagramSocket()
            address = InetAddress.getByName(host)  // DNS 只解析一次
        } catch (e: SocketException) {
            Log.e(TAG, "创建 socket 失败: ${e.message}", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "解析地址失败: ${e.message}", e)
            return
        }
        running = true
        receiveJob = scope.launch { receiveLoop() }
        sendJob = scope.launch { sendLoop() }
        Log.d(TAG, "start → $host:$port (统一发送循环 ${SEND_TICK_MS}ms)")
    }




    // ==================== 上行:事件消息(入队,sendLoop 发送) ====================

    /** 发送机器人动作指令(事件式,入队发送) */
    fun sendRobotCmd(category: String, action: String) {
        if (!running) return
        Log.d(TAG, "↑ sendRobotCmd category=$category action=$action")
        sendChannel.trySend(MessageProtocol.buildRobotCmd(category, action))
    }

    /**
     * 更新人脸跟踪数据(由视觉模块高频调用,sendLoop 按 25Hz 发送)。
     * face 为 null 时发送 t=0 信号，X5 端始终能收到数据。
     */
    fun updateFaceTrack(face: FaceInfo?) {
        if (!running) return
        if (face != null) {
            Log.d(TAG, "updateFaceTrack: id=${face.trackingId} x=${face.offsetX} y=${face.offsetY} d=${face.distanceMeters}")
        } else {
            Log.d(TAG, "updateFaceTrack: null (no face)")
        }
        latestFaceTrackPayload = MessageProtocol.buildFaceTrack(face)
    }


    // ==================== 统一发送循环 ====================

    /**
     * 单一协程按 SEND_TICK_MS(100ms) 节拍发送所有消息。
     * 保证消息顺序,避免并发 launch,集中管理发送节奏。
     */
    private suspend fun sendLoop() {
        var lastHeartbeatTime = 0L

        while (running) {
            val now = System.currentTimeMillis()
            val sock = socket ?: break
            val addr = address ?: break

            try {
                // 1) heartbeat: 每 heartbeatIntervalMs(3s)
                if (now - lastHeartbeatTime >= heartbeatIntervalMs) {
                    sendRaw(sock, addr, MessageProtocol.buildHeartbeat(now))
                    lastHeartbeatTime = now
                }

                // 2) face_track: 25Hz (40ms)，始终发送
                if (now - lastFaceTrackSendTime >= FACE_TRACK_INTERVAL_MS) {
                    sendRaw(sock, addr, latestFaceTrackPayload)
                    lastFaceTrackSendTime = now
                    // ★ Bug 14 修复：日志节流，每 5 秒最多打一次
                    if (now - lastFaceTrackLogTime >= 5000) {
                        Log.d(TAG, "↑ face_track sent: ${String(latestFaceTrackPayload)}")
                        lastFaceTrackLogTime = now
                    }
                }

                // 3) 事件消息:非阻塞消费队列
                while (true) {
                    val payload = sendChannel.tryReceive().getOrNull() ?: break
                    sendRaw(sock, addr, payload)
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendLoop error: ${e.message}")
            }

            delay(SEND_TICK_MS)
        }
    }

    /** 底层发送:打包+UDP(在 sendLoop 协程内同步调用,无并发) */
    private fun sendRaw(sock: DatagramSocket, addr: InetAddress, payload: ByteArray) {
        try {
            val frame = MessageProtocol.pack(
                type = MessageProtocol.TYPE_JSON,
                seq = seq.getAndIncrement() and 0xFFFF,
                payload = payload
            )
            sock.send(DatagramPacket(frame, frame.size, addr, port))
        } catch (e: Exception) {
            Log.w(TAG, "send error: ${e.message}")
        }
    }

    // ==================== 接收 ====================

    private suspend fun receiveLoop() {
        val sock = socket ?: return
        try {
            val buf = ByteArray(RECV_BUF_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    sock.receive(packet)
                    handleDatagram(buf, packet.length)
                } catch (e: SocketException) {
                    if (running) Log.w(TAG, "receive SocketException: ${e.message}")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "receive error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "receiveLoop failed: ${e.message}", e)
        } finally {
            Log.d(TAG, "receiveLoop exit")
        }
    }

    private fun handleDatagram(buf: ByteArray, len: Int) {
        val frame = MessageProtocol.unpack(buf, len)
        if (frame == null) {
            Log.w(TAG, "丢弃坏包(magic 不符或长度不足),len=$len")
            return
        }
        if (frame.type != MessageProtocol.TYPE_JSON) {
            Log.w(TAG, "忽略非 JSON 帧 type=0x${frame.type.toString(16)}")
            return
        }
        val parsed = MessageProtocol.parse(frame.payload)
        if (parsed == null) {
            Log.w(TAG, "JSON 解析失败")
            return
        }
        val (type, data) = parsed
        when (type) {
            MessageProtocol.T_EXPRESSION -> {
                val emotion = data.optString("emotion", "")
                mainHandler.post { onExpression(emotion) }
            }
            MessageProtocol.T_MOTOR_STATUS -> {
                val pitch = data.optDouble("pitch", 0.0).toFloat()
                val yaw = data.optDouble("yaw", 0.0).toFloat()
                mainHandler.post { onMotorStatus(pitch, yaw) }
            }
            MessageProtocol.T_HEARTBEAT_ACK -> {
                val ts = data.optLong("timestamp", 0L)
                mainHandler.post { onHeartbeatAck(ts) }
            }
            MessageProtocol.T_HEAD_STATUS -> {
                val pitch = data.optDouble("pitch", 0.0).toFloat()
                val yaw = data.optDouble("yaw", 0.0).toFloat()
                val enabled = data.optBoolean("enabled", false)
                mainHandler.post { onMotorStatus(pitch, yaw) }
                Log.d(TAG, "head_status pitch=$pitch yaw=$yaw enabled=$enabled")
            }
            else -> Log.w(TAG, "未知下行消息类型: $type")
        }
    }

    // ==================== 生命周期 ====================

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "socket close error: ${e.message}")
        }
        socket = null
        address = null
        receiveJob?.cancel()
        sendJob?.cancel()
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
