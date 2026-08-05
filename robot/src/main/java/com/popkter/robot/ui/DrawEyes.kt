package com.popkter.robot.ui

import androidx.compose.animation.core.Transition
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.popkter.robot.ext.calculateRotateCenterPivot
import com.popkter.robot.ext.generateTransition
import com.popkter.robot.ext.hexStringToColor
import com.popkter.robot.status.RobotStatus
import kotlin.math.abs

/**
 * draw both eyes
 */
@Composable
fun RobotStatus.DrawEyes(
    modifier: Modifier,
    finiteTransition: Transition<RobotStatus>,
) {
    val leftEyeHorizontalRadius by leftEyeHorizontalRadius.generateTransition(finiteTransition)

    val rightEyeHorizontalRadius by rightEyeHorizontalRadius.generateTransition(finiteTransition)

    val leftUpperEyelidRadius by leftTopEyeRadius.generateTransition(finiteTransition)

    val leftLowerEyelidRadius by leftBottomEyeRadius.generateTransition(finiteTransition)

    val rightUpperEyelidRadius by rightTopEyeRadius.generateTransition(finiteTransition)

    val rightLowerEyelidRadius by rightBottomEyeRadius.generateTransition(finiteTransition)

    val leftEyeCornerRadius by leftEyeCornerRadius.generateTransition(finiteTransition)

    val rightEyeCornerRadius by rightEyeCornerRadius.generateTransition(finiteTransition)

    //both eyes rotate
    val eyesRotatedAngle by eyesRotate.generateTransition(finiteTransition)

    //both eyes horizontal translation
    val eyesHorizontalTransition by eyesHorizontalTransition.generateTransition(finiteTransition)

    //both eyes vertical translation
    val eyesVerticalTransition by eyesVerticalTransition.generateTransition(finiteTransition)

    //both eyes scaleX
    val eyesScaleX by eyesScaleX.generateTransition(finiteTransition)

    //both eyes scaleY
    val eyesScaleY by eyesScaleY.generateTransition(finiteTransition)

    val leftEyeRotatedAngle by leftEyeRotate.generateTransition(finiteTransition)

    val rightEyeRotatedAngle by rightEyeRotate.generateTransition(finiteTransition)

    val leftEyeScaleX by leftEyeScaleX.generateTransition(finiteTransition)

    val rightEyeScaleX by rightEyeScaleX.generateTransition(finiteTransition)

    val leftEyeScaleY by leftEyeScaleY.generateTransition(finiteTransition)

    val rightEyeScaleY by rightEyeScaleY.generateTransition(finiteTransition)

    val leftEyeHorizontalTransition by leftEyeHorizontalTransition.generateTransition(finiteTransition)

    val rightEyeHorizontalTransition by rightEyeHorizontalTransition.generateTransition(finiteTransition)

    val leftEyeVerticalTransition by leftEyeVerticalTransition.generateTransition(finiteTransition)

    val rightEyeVerticalTransition by rightEyeVerticalTransition.generateTransition(finiteTransition)

    val fillColor = hexStringToColor(eyesFillColor)

    Canvas(modifier = modifier) {

        val eyeScale = 2.5f  // 全局眼睛缩放系数
        val unit = minOf(size.width, size.height)
        val leftEyeCenter = Offset(size.width * 0.38f, size.height * 0.5f)
        val rightEyeCenter = Offset(size.width * 0.62f, size.height * 0.5f)
        val eyesCenterPivot = Offset(size.width / 2f, size.height * 0.5f)
        translate(left = eyesHorizontalTransition, top = eyesVerticalTransition) {
            rotate(
                degrees = eyesRotatedAngle,
                pivot = eyesRotate.centerPivotLevel.calculateRotateCenterPivot(center, minOf(size.width, size.height) / 2f)

            ) {
                scale(
                    scaleX = eyesScaleX,
                    scaleY = eyesScaleY,
                    pivot = eyesCenterPivot
                ) {
                    drawEye(
                        center = leftEyeCenter,
                        horizontalRadius = leftEyeHorizontalRadius * eyeScale,
                        upperEyelidRadius = leftUpperEyelidRadius * eyeScale,
                        lowerEyelidRadius = leftLowerEyelidRadius * eyeScale,
                        cornerRadius = leftEyeCornerRadius * eyeScale,
                        horizontalTranslation = leftEyeHorizontalTransition,
                        verticalTranslation = leftEyeVerticalTransition,
                        rotateAngle = leftEyeRotatedAngle,
                        scaleX = leftEyeScaleX,
                        scaleY = leftEyeScaleY,
                        fillColor = fillColor
                    )

                    drawEye(
                        center = rightEyeCenter,
                        horizontalRadius = rightEyeHorizontalRadius * eyeScale,
                        upperEyelidRadius = rightUpperEyelidRadius * eyeScale,
                        lowerEyelidRadius = rightLowerEyelidRadius * eyeScale,
                        cornerRadius = rightEyeCornerRadius * eyeScale,
                        horizontalTranslation = rightEyeHorizontalTransition,
                        verticalTranslation = rightEyeVerticalTransition,
                        rotateAngle = rightEyeRotatedAngle,
                        scaleX = rightEyeScaleX,
                        scaleY = rightEyeScaleY,
                        fillColor = fillColor
                    )
                }
            }
        }
    }

}

/**
 * draw single eye
 * [center] Center Pivot
 * [horizontalRadius] horizontalRadius
 * [upperEyelidRadius] UpperEyelidHeight
 * [lowerEyelidRadius]LowerEyelidHeight
 */
internal fun DrawScope.drawEye(
    center: Offset,
    horizontalRadius: Float,
    upperEyelidRadius: Float,
    lowerEyelidRadius: Float,
    cornerRadius: Float = 0F,
    rotateAngle: Float = 0F,
    horizontalTranslation: Float = 0F,
    verticalTranslation: Float = 0F,
    scaleX: Float = 1F,
    scaleY: Float = 1F,
    fillColor: Color = Color.Transparent
) {

    val (x, y) = center

    val upperCornerRadius = listOf(abs(upperEyelidRadius), abs(cornerRadius), abs(horizontalRadius)).min()
    val lowerCornerRadius = listOf(abs(lowerEyelidRadius), abs(cornerRadius), abs(horizontalRadius)).min()


    val path = Path().apply {

        upperEyelidRadius.takeIf { it > 0 }?.let {
            moveTo(x - horizontalRadius, y)
            relativeLineTo(0F, -(upperEyelidRadius - upperCornerRadius))
            arcTo(
                rect = Rect(
                    center = Offset(x - (horizontalRadius - upperCornerRadius), y - (upperEyelidRadius - upperCornerRadius)),
                    radius = upperCornerRadius
                ),
                startAngleDegrees = 180F,
                sweepAngleDegrees = 90F,
                forceMoveTo = false
            )
            relativeLineTo(horizontalRadius - upperCornerRadius, 0F)
            arcTo(
                rect = Rect(
                    center = Offset(x + (horizontalRadius - upperCornerRadius), y - (upperEyelidRadius - upperCornerRadius)),
                    radius = upperCornerRadius
                ),
                startAngleDegrees = 270F,
                sweepAngleDegrees = 90F,
                forceMoveTo = false
            )
            relativeLineTo(0F, (upperEyelidRadius - upperCornerRadius))
        }

        lowerEyelidRadius.takeIf { it > 0 }?.let {
            moveTo(x - horizontalRadius, y)
            relativeLineTo(0F, (lowerEyelidRadius - lowerCornerRadius))
            arcTo(
                rect = Rect(
                    center = Offset(x - (horizontalRadius - lowerCornerRadius), y + (lowerEyelidRadius - lowerCornerRadius)),
                    radius = lowerCornerRadius
                ),
                startAngleDegrees = 180F,
                sweepAngleDegrees = -90F,
                forceMoveTo = false
            )
            relativeLineTo(horizontalRadius - lowerCornerRadius, 0F)
            arcTo(
                rect = Rect(
                    center = Offset(x + (horizontalRadius - lowerCornerRadius), y + (lowerEyelidRadius - lowerCornerRadius)),
                    radius = lowerCornerRadius
                ),
                startAngleDegrees = 90F,
                sweepAngleDegrees = -90F,
                forceMoveTo = false
            )
            relativeLineTo(0F, -(lowerEyelidRadius - lowerCornerRadius))
        }
    }

    translate(left = horizontalTranslation, top = verticalTranslation) {
        rotate(degrees = rotateAngle, pivot = center) {
            scale(scaleX = scaleX, scaleY = scaleY, pivot = center) {
                if (fillColor != Color.Transparent) {
                    drawPath(
                        path = path,
                        color = fillColor,
                        style = Fill
                    )
                }

                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(width = 10F)
                )
            }
        }
    }
}

