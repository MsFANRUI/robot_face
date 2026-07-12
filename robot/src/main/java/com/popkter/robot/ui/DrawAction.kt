package com.popkter.robot.ui

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.popkter.robot.ext.calculateRotateCenterPivot
import com.popkter.robot.ext.generateTransition
import com.popkter.robot.status.RobotStatus


@Composable
fun RobotStatus.DrawAction(
    modifier: Modifier,
    finiteTransition: Transition<RobotStatus>,
    textMeasurer: TextMeasurer
) {
    val rotate by actionRotate.generateTransition(finiteTransition)
    val scale by actionScale.generateTransition(finiteTransition)
    val scaleX by actionScaleX.generateTransition(finiteTransition)
    val scaleY by actionScaleY.generateTransition(finiteTransition)
    val horizontalTransition by actionHorizontalTransition.generateTransition(finiteTransition)
    val verticalTransition by actionVerticalTransition.generateTransition(finiteTransition)

    val sampleRotate by actionSampleRotate.generateTransition(finiteTransition)
    if (actionSample.sample.isEmpty()) return


    Canvas(
        modifier = modifier
    ) {
        val textStyle = TextStyle(
            fontSize = 40.sp * scale,
            color = Color.White,
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.ExtraBold,
        )

        val textLayoutResult = textMeasurer.measure(
            text = actionSample.sample,
            style = textStyle
        )

        translate(left = horizontalTransition, top = verticalTransition) {
            /**
             * action画布旋转角度
             */
            rotate(
                degrees = rotate,
                pivot = actionRotate.centerPivotLevel.calculateRotateCenterPivot(center, size.width / 2)
            ) {
                /**
                 * Sample自身旋转角度
                 */
                rotate(
                    degrees = sampleRotate,
                    pivot = center
                ) {
                    scale(scaleX, scaleY) {
                        drawText(
                            textLayoutResult = textLayoutResult,
                            topLeft = Offset(
                                center.x - (textLayoutResult.size.width) / 2,
                                center.y - (textLayoutResult.size.height) / 2
                            ),
                            drawStyle = Stroke(width = 3F)
                        )
                    }
                }
            }
        }
    }
}
