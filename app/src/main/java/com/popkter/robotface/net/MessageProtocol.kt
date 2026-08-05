package com.popkter.robotface.net

import com.popkter.robotface.vision.FaceInfo
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * UDP 协议契约 —— 与 x5_server 的 protocol/framing.py + protocol/message.py 严格对称。
 *
 * 帧格式(8 字节头 + payload,大端):
 *   偏移  长度  字段
 *   0     4     magic   固定 "RBX5"
 *   4     1     type    负载类型(TYPE_JSON=0x01)
 *   5     1     flags   保留,v0 恒为 0
 *   6     2     seq     大端 uint16,发送方自增序号
 *   8     N     payload UTF-8 JSON(信封 {"type":..,"data":..})
 *
 * 详见 robot_shared/PROTOCOL.md。
 */
object MessageProtocol {

    // ==================== 帧常量 ====================
    val MAGIC = byteArrayOf(0x52, 0x42, 0x58, 0x35) // "RBX5"
    const val HEADER_LEN = 8

    // 负载类型
    const val TYPE_JSON = 0x01   // payload = UTF-8 JSON
    const val TYPE_VIDEO = 0x10  // JPEG(v1,暂不用)
    const val TYPE_AUDIO = 0x11  // PCM(v1,暂不用)

    // ==================== 消息类型(信封 type 字段) ====================
    // 上行(robot_face → x5)

    const val T_HEARTBEAT = "heartbeat"
    const val T_ROBOT_CMD = "robot_cmd"
    const val T_FACE_TRACK = "face_track"
    // 下行(x5 → robot_face)
    const val T_EXPRESSION = "expression"
    const val T_MOTOR_STATUS = "motor_status"
    const val T_HEARTBEAT_ACK = "heartbeat_ack"
    const val T_HEAD_STATUS = "head_status"

    /** 拆包结果:帧头字段 + payload */
    data class Frame(val type: Int, val seq: Int, val payload: ByteArray)

    // ==================== 帧打包/拆包 ====================

    /** 打包一个 UDP 帧:8 字节头 + payload(大端)。对应 framing.py 的 pack_packet。 */
    fun pack(type: Int, seq: Int, payload: ByteArray, flags: Int = 0): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_LEN + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(MAGIC)
        buf.put((type and 0xFF).toByte())
        buf.put((flags and 0xFF).toByte())
        buf.putShort((seq and 0xFFFF).toShort())
        buf.put(payload)
        return buf.array()
    }

    /**
     * 拆包。对应 framing.py 的 parse_packet。
     * @param len 有效字节数(DatagramPacket.length)
     * @return 魔数不符/长度不足返回 null
     */
    fun unpack(data: ByteArray, len: Int): Frame? {
        if (len < HEADER_LEN) return null
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) return null
        }
        val buf = ByteBuffer.wrap(data, 0, len).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val type = buf.get().toInt() and 0xFF
        buf.get() // flags,保留
        val seq = buf.short.toInt() and 0xFFFF
        val payload = data.copyOfRange(HEADER_LEN, len)
        return Frame(type, seq, payload)
    }

    // ==================== JSON 构建器(上行) ====================

    /** 统一信封 {"type":..,"data":..} → UTF-8 bytes。对应 message.py 的 build_message。 */
    private fun buildMessage(type: String, data: JSONObject): ByteArray {
        val envelope = JSONObject().apply {
            put("type", type)
            put("data", data)
        }
        return envelope.toString().toByteArray(Charsets.UTF_8)
    }

    /** 心跳:timestamp 毫秒 */
    fun buildHeartbeat(timestamp: Long): ByteArray =
        buildMessage(T_HEARTBEAT, JSONObject().apply {
            put("timestamp", timestamp)
        })



    /** 机器人动作指令:category 分类 + action 动作名 */
    fun buildRobotCmd(category: String, action: String): ByteArray =
        buildMessage(T_ROBOT_CMD, JSONObject().apply{
            put("category", category)
            put("action", action)
        })

    /**
     * 人脸跟踪数据:传主目标脸(最小ID)的坐标和距离。
     * face 为 null 时发送 t=0, x=0, y=0, d=0，X5 端恒定 25Hz 接收。
     */
    fun buildFaceTrack(face: FaceInfo?): ByteArray =
        buildMessage(T_FACE_TRACK, JSONObject().apply {
            if (face != null) {
                put("t", 1)
                put("x", (face.offsetX * 100).toInt() / 100.0)
                put("y", (face.offsetY * 100).toInt() / 100.0)
                put("d", (face.distanceMeters * 100).toInt() / 100.0)
            } else {
                put("t", 0)
                put("x", 0)
                put("y", 0)
                put("d", 0)
            }
        })

    // ==================== JSON 解析(下行) ====================

    /**
     * 解析 payload 信封。对应 message.py 的 parse_message。
     * @return (type, data) —— 解析失败返回 null
     */
    fun parse(payload: ByteArray): Pair<String, JSONObject>? {
        return try {
            val obj = JSONObject(String(payload, Charsets.UTF_8))
            val type = obj.optString("type", "")
            if (type.isEmpty()) return null
            val data = obj.optJSONObject("data") ?: JSONObject()
            type to data
        } catch (e: Exception) {
            null
        }
    }
}
