package com.popkter.robotface.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * 配置服务器 — 在机器人 Android App 中嵌入 HTTP 服务器
 *
 * 接收来自平板 Web 页面的角色配置请求。
 * 使用 NanoHTTPd（极致轻量 ~30KB），监听指定端口。
 *
 * 端点：
 * - POST /config  → 下发角色配置（JSON body）
 * - POST /reset   → 一键重置，恢复默认
 * - GET  /status  → 查询当前配置状态
 */
class ConfigServer(
    private val port: Int = DEFAULT_PORT,
    private val onConfigReceived: (CharacterConfig) -> Unit,
    private val onReset: () -> Unit,
    private val getCurrentConfig: () -> CharacterConfig?
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ConfigServer"
        const val DEFAULT_PORT = 8080
    }

    override fun serve(session: IHTTPSession): Response {
        // ---- CORS headers for web access ----
        val corsHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type"
        )

        return try {
            when {
                // ---- OPTIONS (CORS preflight) ----
                session.method == Method.OPTIONS -> {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                        .also { corsHeaders.forEach { (k, v) -> it.addHeader(k, v) } }
                }

                // ---- POST /reset ----
                session.method == Method.POST && session.uri == "/reset" -> {
                    Log.d(TAG, "收到重置请求")
                    onReset()
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"status":"ok","message":"已重置"}"""
                    ).also { corsHeaders.forEach { (k, v) -> it.addHeader(k, v) } }
                }

                // ---- POST /config ----
                session.method == Method.POST && session.uri == "/config" -> {
                    val body = parseBody(session)
                    Log.d(TAG, "收到配置请求: ${body.take(200)}")

                    val config = CharacterConfig.fromJson(body)
                    onConfigReceived(config)

                    newFixedLengthResponse(
                        Response.Status.OK,
                        "application/json",
                        """{"status":"ok","robotName":"${config.robotName}","expression":"${config.expression}"}"""
                    ).also { corsHeaders.forEach { (k, v) -> it.addHeader(k, v) } }
                }

                // ---- GET /status ----
                session.method == Method.GET && session.uri == "/status" -> {
                    val config = getCurrentConfig()
                    val json = if (config != null) {
                        """{"configured":true,"robotName":"${config.robotName}","expression":"${config.expression}","voice":"${config.voice}"}"""
                    } else {
                        """{"configured":false,"robotName":null,"expression":null,"voice":null}"""
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", json)
                        .also { corsHeaders.forEach { (k, v) -> it.addHeader(k, v) } }
                }

                // ---- 404 ----
                else -> {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "application/json",
                        """{"error":"not found"}"""
                    ).also { corsHeaders.forEach { (k, v) -> it.addHeader(k, v) } }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理请求失败: ${e.message}", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"${e.message}"}"""
            ).also { corsHeaders.forEach { (k, v) -> it.addHeader(k, v) } }
        }
    }

    /**
     * 解析 HTTP body
     * NanoHTTPd 的 session.parseBody() 会处理 chunked/urlencoded 等格式
     */
    private fun parseBody(session: IHTTPSession): String {
        // 读取 body
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength == 0) {
            throw IOException("空请求体")
        }
        val bodyBytes = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = session.inputStream.read(bodyBytes, offset, contentLength - offset)
            if (read == -1) break
            offset += read
        }
        return String(bodyBytes, Charsets.UTF_8)
    }
}
