package com.popkter.robotface.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 角色配置数据类
 *
 * 从平板 Web 页面接收的角色个性化配置，用于：
 * 1. 动态构建 LLM 系统提示词
 * 2. 切换 TTS 音色
 * 3. 设置初始表情
 * 4. 生成个性化开场白
 */
@Serializable
data class CharacterConfig(
    /** 机器人名字（如"小宝"） */
    val robotName: String = "",
    /** 对体验者的称呼（如"小明"） */
    val visitorName: String = "",
    /** 机器人身份/背景描述 */
    val identity: String = "",
    /** 外观主题 ID（对应 APPEARANCE_OPTIONS.id: happiness/sunglasses/sparkle/ordinary/football/focus） */
    val appearance: String = "ordinary",
    /** 对应 RobotStatus 表情名（如 Happiness/Ordinary/Focus） */
    val expression: String = "Ordinary",
    /** CosyVoice 音色 ID（如 longhua_v3/longxing_v3） */
    val voice: String = "longcheng_v3",
    /** 性格 ID（lively/gentle/calm/humorous/custom） */
    val personality: String = "lively",
    /** 自定义性格提示词（仅 personality=custom 时有效） */
    val customPrompt: String = "",
    /** 预设性格的系统提示词片段 */
    val personalityPromptHint: String = ""
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 从 JSON 字符串反序列化 */
        fun fromJson(jsonStr: String): CharacterConfig {
            return json.decodeFromString<CharacterConfig>(jsonStr)
        }

        /** 构建默认配置 */
        fun default() = CharacterConfig()
    }

    /**
     * 构建 LLM 系统提示词
     *
     * 将角色信息、性格提示词组合成完整的系统提示词，
     * 替换 ChatClient 中原来的硬编码提示词
     */
    fun buildSystemPrompt(): String {
        val sb = StringBuilder()

        // 基础身份
        sb.appendLine("你是${robotName}，${identity}。")

        // 性格设定
        if (personality == "custom" && customPrompt.isNotBlank()) {
            sb.appendLine(customPrompt)
        } else if (personalityPromptHint.isNotBlank()) {
            sb.appendLine(personalityPromptHint)
        }

        // 称呼设定
        if (visitorName.isNotBlank()) {
            sb.appendLine("你正在和${visitorName}对话，请用这个名字称呼对方。")
        }

        // 回复风格
        sb.appendLine("回复要简短自然，每次回复都必须调用 set_expression 工具。")

        // 可用表情
        sb.appendLine(
            "可用表情：Ordinary(默认)、Sadness(伤心)、Happiness(开心)、Music(音乐)、" +
                    "Coldness(冷)、Speechless(无语)、Angry(生气)、Think(思考)、Talk(说话)、" +
                    "Singing(唱歌)、SparkLight(灵感)、Coffee(咖啡)、Focus(专注)。"
        )

        return sb.toString()
    }

    /**
     * 构建个性化开场白
     */
    fun buildGreeting(): String {
        if (visitorName.isNotBlank() && robotName.isNotBlank()) {
            return "你好${visitorName}！我是${robotName}，${identity}！"
        }
        if (robotName.isNotBlank()) {
            return "你好！我是${robotName}，${identity}！"
        }
        return "你好！我是${identity}！"
    }
}
