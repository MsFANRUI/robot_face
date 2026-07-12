package com.popkter.robot.ext

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.popkter.robot.status.RobotStatus
import com.popkter.robot.status.TransitionProperty
import kotlinx.serialization.Serializable
import java.util.UUID


const val HEART_SAMPLE = "♥"
const val FILL_HEART_SAMPLE = "♡"
const val PENCIL_SAMPLE = "✎"
const val MIC_SAMPLE = "\uD83C\uDF99"
const val SPEECHLESS_SAMPLE = "ⲽ"
const val NOTE_SAMPLE = "\uD83D\uDCD1"
const val CLOUD_SAMPLE = "☁"
const val SUN_SAMPLE = "☀"
const val RAIN_SAMPLE = "☔"
const val COFFEE_SAMPLE = "☕"
const val MUSIC_SAMPLE = "🎹"
const val DIALOGUE_SAMPLE = "✆"
const val SPARK_SAMPLE = "✨"
const val FOOTBALL_SAMPLE = "⚽"
const val SUNGLASS_SAMPLE = "\uD83D\uDD76"
const val CAMERA_SAMPLE = "\uD83D\uDCF7"
const val FOCUS_SAMPLE = "\uD83E\uDEF5"

const val PIVOT_OFFSET = 0.707F

/**
 * 计算偏移值
 */
@SuppressLint("UnusedTransitionTargetStateParameter")
@Composable
fun TransitionProperty.generateTransition(
    finiteTransition: Transition<RobotStatus>,
) = with(this) {
    if (infinite) {
        generateInfiniteTransitionWithAnimate()
/*        infiniteTransition.animateFloat(
            initialValue = initialValue,
            targetValue = targetValue,
            animationSpec = animationSpec as InfiniteRepeatableSpec<Float>
        )*/
    } else {
        finiteTransition.animateFloat(
            transitionSpec = { animationSpec as FiniteAnimationSpec<Float> },
            targetValueByState = { targetValue }
        )
    }
}


@Composable
fun TransitionProperty.generateInfiniteTransitionWithAnimate(): State<Float> {
    val anim = remember { Animatable(initialValue) }
    LaunchedEffect(this) {
        anim.snapTo(initialValue)
        anim.animateTo(
            targetValue = targetValue,
            animationSpec = animationSpec as InfiniteRepeatableSpec<Float>
        )
    }
    return anim.asState()
}

@Composable
fun TransitionProperty.generateFiniteTransitionWithAnimate(): State<Float> {
    val anim = remember { Animatable(initialValue) }
    LaunchedEffect(this) {
        anim.snapTo(initialValue)
        anim.animateTo(
            targetValue = targetValue,
            animationSpec = animationSpec as FiniteAnimationSpec<Float>
        )
    }
    return anim.asState()
}


/**
 * 旋转中心点，面部内切水平正方形顶点
 */
@Serializable
sealed class PivotLevel {
    data object TopLeft : PivotLevel()
    data object TopCenter : PivotLevel()
    data object TopRight : PivotLevel()
    data object CenterLeft : PivotLevel()
    data object Center : PivotLevel()
    data object CenterRight : PivotLevel()
    data object BottomLeft : PivotLevel()
    data object BottomCenter : PivotLevel()
    data object BottomRight : PivotLevel()
}

/**
 * 根据PivotLevel计算旋转中心
 */
fun PivotLevel.calculateRotateCenterPivot(center: Offset, radius: Float) = when (this) {
    PivotLevel.BottomCenter -> Offset(center.x, center.y + PIVOT_OFFSET * radius)
    PivotLevel.BottomLeft -> Offset(center.x - PIVOT_OFFSET * radius, center.y + PIVOT_OFFSET * radius)
    PivotLevel.BottomRight -> Offset(center.x + PIVOT_OFFSET * radius, center.y + PIVOT_OFFSET * radius)
    PivotLevel.Center -> Offset(center.x, center.y)
    PivotLevel.CenterLeft -> Offset(center.x - PIVOT_OFFSET * radius, center.y)
    PivotLevel.CenterRight -> Offset(center.x + PIVOT_OFFSET * radius, center.y)
    PivotLevel.TopCenter -> Offset(center.x, center.y - PIVOT_OFFSET * radius)
    PivotLevel.TopLeft -> Offset(center.x - PIVOT_OFFSET * radius, center.y - PIVOT_OFFSET * radius)
    PivotLevel.TopRight -> Offset(center.x + PIVOT_OFFSET * radius, center.y - PIVOT_OFFSET * radius)
}

@Serializable
data class ActionSample(val sample: String = "")


fun isValidHexColor(hex: String): Boolean {
    // 正则表达式匹配 # 开头，后跟 6 或 8 个十六进制字符
    val hexColorRegex = Regex("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$")
    return hexColorRegex.matches(hex)
}

fun hexStringToColor(hex: String): Color {
    if (!isValidHexColor(hex)) {
        throw IllegalArgumentException("Invalid hex color format: $hex")
    }

    // 移除 # 符号
    val colorHex = hex.substring(1)

    // 根据长度解析颜色值
    return when (colorHex.length) {
        6 -> {
            // 解析 #RRGGBB 格式
            val red = colorHex.substring(0, 2).toInt(16)
            val green = colorHex.substring(2, 4).toInt(16)
            val blue = colorHex.substring(4, 6).toInt(16)
            Color(red, green, blue) // 默认 Alpha 为 1F（不透明）
        }

        8 -> {
            // 解析 #AARRGGBB 格式
            val alpha = colorHex.substring(0, 2).toInt(16)
            val red = colorHex.substring(2, 4).toInt(16)
            val green = colorHex.substring(4, 6).toInt(16)
            val blue = colorHex.substring(6, 8).toInt(16)
            Color(red, green, blue, alpha) // 包含 Alpha 通道
        }

        else -> throw IllegalArgumentException("Invalid hex color length: $hex")
    }
}