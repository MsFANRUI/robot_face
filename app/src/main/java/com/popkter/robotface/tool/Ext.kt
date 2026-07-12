package com.popkter.robotface.tool

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * px转dp,sp
 */
data class Px(val size: Int)

inline val Int.px: Px get() = Px(this)
inline val Px.dp: Dp get() = (size / 2.25).dp

inline val Px.sp: TextUnit get() = (size / 2.25).sp