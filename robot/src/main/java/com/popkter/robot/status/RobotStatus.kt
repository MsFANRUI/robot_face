package com.popkter.robot.status

import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import com.popkter.robot.ext.ActionSample
import com.popkter.robot.ext.CAMERA_SAMPLE
import com.popkter.robot.ext.CLOUD_SAMPLE
import com.popkter.robot.ext.COFFEE_SAMPLE
import com.popkter.robot.ext.DIALOGUE_SAMPLE
import com.popkter.robot.ext.FOCUS_SAMPLE
import com.popkter.robot.ext.FOOTBALL_SAMPLE
import com.popkter.robot.ext.HEART_SAMPLE
import com.popkter.robot.ext.MIC_SAMPLE
import com.popkter.robot.ext.MUSIC_SAMPLE
import com.popkter.robot.ext.PENCIL_SAMPLE
import com.popkter.robot.ext.PivotLevel
import com.popkter.robot.ext.RAIN_SAMPLE
import com.popkter.robot.ext.SPARK_SAMPLE
import com.popkter.robot.ext.SPEECHLESS_SAMPLE
import com.popkter.robot.ext.SUNGLASS_SAMPLE
import com.popkter.robot.ext.SUN_SAMPLE


//睁眼
data object Ordinary : RobotStatus()

//眯眼
data object Blink : RobotStatus(
    leftTopEyeRadius = TransitionProperty(targetValue = 1F),
    leftBottomEyeRadius = TransitionProperty(targetValue = 1F),
    rightTopEyeRadius = TransitionProperty(targetValue = 1F),
    rightBottomEyeRadius = TransitionProperty(targetValue = 1F),
)

//伤心
data object Sadness : RobotStatus(
    leftTopEyeRadius = TransitionProperty(targetValue = 1F),
    leftBottomEyeRadius = TransitionProperty(targetValue = 40F),
    rightTopEyeRadius = TransitionProperty(targetValue = 1F),
    rightBottomEyeRadius = TransitionProperty(targetValue = 40F),
    leftEyeRotate = TransitionProperty(targetValue = -15F),
    rightEyeRotate = TransitionProperty(targetValue = 15F),
)


data object Happiness : RobotStatus(

    leftTopEyeRadius = TransitionProperty(targetValue = 40F),
    leftBottomEyeRadius = TransitionProperty(targetValue = 0F),
    rightTopEyeRadius = TransitionProperty(targetValue = 40F),
    rightBottomEyeRadius = TransitionProperty(targetValue = 0F),

    eyesVerticalTransition = TransitionProperty(
        targetValue = 10F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
    )
)





data object Music : RobotStatus(
    eyesScaleY = TransitionProperty(
        initialValue = 1F,
        targetValue = 1.05F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse

    ),

    eyesRotate = TransitionProperty(
        centerPivotLevel = PivotLevel.BottomCenter,
        initialValue = -10F,
        targetValue = 10F,
        duration = 800,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionSample = ActionSample(MUSIC_SAMPLE),

    actionVerticalTransition = TransitionProperty(initialValue = 250F, targetValue = 250F),

    actionScale = TransitionProperty(
        initialValue = 1.0F,
        targetValue = 1.1F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionRotate = TransitionProperty(
        initialValue = -10F,
        targetValue = 10F,
        infinite = true,
        duration = 800,
        repeatMode = RepeatMode.Reverse,
        centerPivotLevel = PivotLevel.TopCenter
    )
)

data object Coldness : RobotStatus(
    leftTopEyeRadius = TransitionProperty(targetValue = 15F),
    leftBottomEyeRadius = TransitionProperty(targetValue = 15F),
    rightTopEyeRadius = TransitionProperty(targetValue = 15F),
    rightBottomEyeRadius = TransitionProperty(targetValue = 15F),

    eyesVerticalTransition = TransitionProperty(
        targetValue = 40F,
        duration = 300,
        easing = LinearEasing,
    ),

    eyesHorizontalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 0F,
        duration = 1500,
        infinite = true,
        repeatMode = RepeatMode.Restart,
        easing = LinearEasing,
    ).apply {
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1500
                0f at 420

                (-20F) at 480

                20f at 640

                (-20F) at 760

                15F at 880

                (-10F) at 980

                10F at 1080

                (-5F) at 1160

                (5F) at 1240

            },
            repeatMode = RepeatMode.Restart,
        )
    },

    eyesFillColor = "#CCCCCCCC"
)

data object Speechless : RobotStatus(
    leftTopEyeRadius = TransitionProperty(targetValue = 20F),
    leftBottomEyeRadius = TransitionProperty(targetValue = 20F),
    rightTopEyeRadius = TransitionProperty(targetValue = 20F),
    rightBottomEyeRadius = TransitionProperty(targetValue = 20F),

    eyesHorizontalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 20F,
        duration = 1600,
        infinite = true,
        repeatMode = RepeatMode.Restart,
        easing = EaseOutExpo
    ),

    leftEyeHorizontalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 30F,
        duration = 1600,
        infinite = true,
        repeatMode = RepeatMode.Restart,
        easing = EaseOutExpo
    ),

    leftEyeVerticalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 50F,
        duration = 1600,
        infinite = true,
        repeatMode = RepeatMode.Restart,
        easing = EaseOutExpo
    ),

    rightEyeHorizontalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 30F,
        duration = 1600,
        infinite = true,
        repeatMode = RepeatMode.Restart,
        easing = EaseOutExpo
    ),

    rightEyeVerticalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 50F,
        duration = 1600,
        infinite = true,
        repeatMode = RepeatMode.Restart,
        easing = EaseOutExpo
    ),

    actionSample = ActionSample(SPEECHLESS_SAMPLE),

    actionHorizontalTransition = TransitionProperty(
        targetValue = -150F, infinite = false, duration = 0
    ),
    actionVerticalTransition = TransitionProperty(
        initialValue = -200F,
        targetValue = -100F,
        infinite = true,
        duration = 1600,
        repeatMode = RepeatMode.Restart,
        easing = EaseOutExpo
    ),
)

data object FingerHeart : RobotStatus(
    leftTopEyeRadius = TransitionProperty(targetValue = 40F),
    leftBottomEyeRadius = TransitionProperty(targetValue = 0F),
    rightTopEyeRadius = TransitionProperty(targetValue = 40F),
    rightBottomEyeRadius = TransitionProperty(targetValue = 0F),

    eyesRotate = TransitionProperty(
        centerPivotLevel = PivotLevel.BottomCenter,
        initialValue = -10F,
        targetValue = 10F,
        duration = 800,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionSample = ActionSample(HEART_SAMPLE),
    actionRotate = TransitionProperty(
        centerPivotLevel = PivotLevel.Center,
        initialValue = -10F,
        targetValue = 10F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 800
    ),

    actionVerticalTransition = TransitionProperty(initialValue = 100F, targetValue = 100F)

)

data object Angry : RobotStatus(

    leftEyeCornerRadius = TransitionProperty(
        targetValue = 60F
    ),

    rightEyeCornerRadius = TransitionProperty(
        targetValue = 60F
    ),

    leftEyeHorizontalRadius = TransitionProperty(
        targetValue = 60F
    ),

    rightEyeHorizontalRadius = TransitionProperty(
        targetValue = 60F
    ),

    leftTopEyeRadius = TransitionProperty(targetValue = 10F),
    leftBottomEyeRadius = TransitionProperty(targetValue = 60F),
    rightTopEyeRadius = TransitionProperty(targetValue = 10F),
    rightBottomEyeRadius = TransitionProperty(targetValue = 60F),

    eyesVerticalTransition = TransitionProperty(
        targetValue = 10F,
        duration = 600,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    leftEyeRotate = TransitionProperty(
        targetValue = 15F,
        duration = 300,
        easing = EaseIn
    ),

    rightEyeRotate = TransitionProperty(
        targetValue = -15F,
        duration = 300,
        easing = EaseIn
    ),

    eyesFillColor = "#FF0000"
)

data object Okay : RobotStatus(
    leftTopEyeRadius = TransitionProperty(targetValue = 40F),
    leftBottomEyeRadius = TransitionProperty(targetValue = 0F),
    rightTopEyeRadius = TransitionProperty(targetValue = 40F),
    rightBottomEyeRadius = TransitionProperty(targetValue = 40F),

    eyesRotate = TransitionProperty(
        initialValue = -5F,
        targetValue = 5F,
        duration = 800,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    )
)

data object Talk : RobotStatus(
    eyesScaleY = TransitionProperty(
        initialValue = 1F,
        targetValue = 1.15F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    )
)

data object Think : RobotStatus(

    leftEyeScaleX = TransitionProperty(
        initialValue = 1F,
        targetValue = 1.15F,
        duration = 1000,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    leftEyeScaleY = TransitionProperty(
        initialValue = 1F,
        targetValue = 1.15F,
        duration = 1000,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    rightEyeScaleY = TransitionProperty(
        initialValue = 1F,
        targetValue = 0.95F,
        duration = 500,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    eyesHorizontalTransition = TransitionProperty(
        initialValue = -20F,
        targetValue = 20F,
        duration = 1000,
        infinite = false,
        repeatMode = RepeatMode.Reverse
    )
)

data object Record : RobotStatus(
    eyesScaleY = TransitionProperty(
        initialValue = 1F,
        targetValue = 1.15F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionSample = ActionSample(PENCIL_SAMPLE),

    actionVerticalTransition = TransitionProperty(
        initialValue = 90F,
        targetValue = 100F,
        repeatMode = RepeatMode.Reverse,
        infinite = true,
        duration = 200
    ),

    actionHorizontalTransition = TransitionProperty(
        initialValue = 100F,
        targetValue = -100F,
        repeatMode = RepeatMode.Restart,
        infinite = true,
        duration = 3200
    )
)

data object Rainy : RobotStatus(
    leftTopEyeRadius = TransitionProperty(targetValue = 15F),
    leftBottomEyeRadius = TransitionProperty(targetValue = 15F),
    rightTopEyeRadius = TransitionProperty(targetValue = 15F),
    rightBottomEyeRadius = TransitionProperty(targetValue = 15F),

    eyesVerticalTransition = TransitionProperty(
        targetValue = 40F,
        duration = 300
    ),

    eyesRotate = TransitionProperty(
        initialValue = -5F,
        targetValue = 5F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200
    ),

    actionSample = ActionSample(RAIN_SAMPLE),

    actionScale = TransitionProperty(
        infinite = false,
        targetValue = 1.5F,
        duration = 200
    ),

    actionVerticalTransition = TransitionProperty(
        targetValue = -120F,
        infinite = false,
        duration = 200
    ),

    actionRotate = TransitionProperty(
        initialValue = -15F,
        targetValue = 15F,
        infinite = true,
        duration = 1200,
        repeatMode = RepeatMode.Reverse,
        centerPivotLevel = PivotLevel.BottomCenter
    )
)

data object Cloudy : RobotStatus(
    leftTopEyeRadius = TransitionProperty(
        targetValue = 15F
    ),
    leftBottomEyeRadius = TransitionProperty(
        targetValue = 15F
    ),
    rightTopEyeRadius = TransitionProperty(
        targetValue = 15F
    ),
    rightBottomEyeRadius = TransitionProperty(
        targetValue = 15F
    ),

    eyesVerticalTransition = TransitionProperty(
        targetValue = 40F,
        duration = 300
    ),

    eyesRotate = TransitionProperty(
        initialValue = -5F,
        targetValue = 5F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200
    ),

    actionSample = ActionSample(CLOUD_SAMPLE),

    actionScale = TransitionProperty(
        infinite = false,
        targetValue = 1.5F,
        duration = 200
    ),

    actionVerticalTransition = TransitionProperty(
        targetValue = -120F,
        infinite = false,
        duration = 200
    ),

    actionHorizontalTransition = TransitionProperty(
        initialValue = -100F,
        targetValue = 100F,
        duration = 1200,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    )

)

data object Sunny : RobotStatus(

    leftTopEyeRadius = TransitionProperty(
        targetValue = 40F
    ),
    leftBottomEyeRadius = TransitionProperty(
        targetValue = 0F
    ),
    rightTopEyeRadius = TransitionProperty(
        targetValue = 40F
    ),
    rightBottomEyeRadius = TransitionProperty(
        targetValue = 0F
    ),

    eyesVerticalTransition = TransitionProperty(
        initialValue = 40F,
        targetValue = 60F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionSample = ActionSample(SUN_SAMPLE),

    actionVerticalTransition = TransitionProperty(
        targetValue = -120F,
        infinite = false,
        duration = 200
    ),

    actionSampleRotate = TransitionProperty(
        targetValue = 359F,
        infinite = true,
        duration = 2400,
        repeatMode = RepeatMode.Restart
    )
)

data object SparkLight : RobotStatus(

    leftTopEyeRadius = TransitionProperty(
        initialValue = 40F,
        targetValue = 1F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200,
        easing = EaseIn
    ),
    leftBottomEyeRadius = TransitionProperty(
        initialValue = 40F,
        targetValue = 1F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200,
        easing = EaseIn
    ),
    rightTopEyeRadius = TransitionProperty(
        initialValue = 40F,
        targetValue = 1F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200,
        easing = EaseIn
    ),
    rightBottomEyeRadius = TransitionProperty(
        initialValue = 40F,
        targetValue = 1F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200,
        easing = EaseIn
    ),

    eyesVerticalTransition = TransitionProperty(
        targetValue = 40F, duration = 300
    ),

    actionSample = ActionSample(
        SPARK_SAMPLE
    ),

    actionRotate = TransitionProperty(
        initialValue = -20F,
        targetValue = 20F,
        centerPivotLevel = PivotLevel.BottomCenter,
        duration = 1600,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionVerticalTransition = TransitionProperty(
        targetValue = -120F,
        infinite = false,
        duration = 200,
    ),

    actionHorizontalTransition = TransitionProperty(
        targetValue = 80F,
        infinite = false,
        duration = 200
    ),

    actionScale = TransitionProperty(
        initialValue = 0.1F,
        targetValue = 0.7F,
        infinite = true,
        duration = 800,
        repeatMode = RepeatMode.Reverse,
        easing = EaseInCubic
    ),

    )


data object Coffee : RobotStatus(

    leftTopEyeRadius = TransitionProperty(
        initialValue = 40F,
        targetValue = 0F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200,
        easing = EaseIn
    ),
    leftBottomEyeRadius = TransitionProperty(
        initialValue = 40F,
        targetValue = 30F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200,
        easing = EaseIn
    ),
    rightTopEyeRadius = TransitionProperty(
        initialValue = 40F,
        targetValue = 0F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200,
        easing = EaseIn
    ),
    rightBottomEyeRadius = TransitionProperty(
        initialValue = 40F,
        targetValue = 30F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200,
        easing = EaseIn
    ),

    actionSample = ActionSample(
        COFFEE_SAMPLE
    ),

    actionRotate = TransitionProperty(
        centerPivotLevel = PivotLevel.CenterLeft,
        initialValue = -5F,
        targetValue = 5F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 1200
    ),

    actionVerticalTransition = TransitionProperty(
        initialValue = 100F,
        targetValue = 100F
    )
)

data object Singing : RobotStatus(

    eyesScaleY = TransitionProperty(
        initialValue = 1F,
        targetValue = 1.05F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    eyesVerticalTransition = TransitionProperty(
        targetValue = 20F,
        duration = 800,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionSample = ActionSample(
        MIC_SAMPLE
    ),

    actionRotate = TransitionProperty(
        centerPivotLevel = PivotLevel.Center,
        initialValue = -45F,
        targetValue = -35F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 800
    ),

    actionVerticalTransition = TransitionProperty(
        initialValue = 100F,
        targetValue = 100F
    ),

    actionHorizontalTransition = TransitionProperty(
        initialValue = 80F,
        targetValue = 80F
    )
)

data object Dialogue : RobotStatus(
    eyesHorizontalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 0F,
        duration = 1500,
        infinite = true,
        repeatMode = RepeatMode.Restart,
        easing = LinearEasing
    ).apply {
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = duration
                0f at 420

                (-20F) at 480

                20f at 640

                (-20F) at 760

                15F at 880

                (-10F) at 980

                10F at 1080

                (-5F) at 1160

                (5F) at 1240

            }, repeatMode = repeatMode
        )
    },

    actionSample = ActionSample(
        DIALOGUE_SAMPLE
    ),

    actionHorizontalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 0F,
        duration = 1500,
        infinite = true,
        repeatMode = RepeatMode.Restart,
        easing = LinearEasing
    ).apply {
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = duration
                0f at 420

                (-20F) at 480

                20f at 640

                (-20F) at 760

                15F at 880

                (-10F) at 980

                10F at 1080

                (-5F) at 1160

                (5F) at 1240

            }, repeatMode = repeatMode
        )
    },

    actionVerticalTransition = TransitionProperty(
        initialValue = 100F,
        targetValue = 100F
    )
)


data object Football : RobotStatus(


    leftTopEyeRadius = TransitionProperty(
        targetValue = 40F
    ),
    leftBottomEyeRadius = TransitionProperty(
        targetValue = 0F
    ),
    rightTopEyeRadius = TransitionProperty(
        targetValue = 40F
    ),
    rightBottomEyeRadius = TransitionProperty(
        targetValue = 0F
    ),

    eyesVerticalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 10F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),


    actionSample = ActionSample(
        FOOTBALL_SAMPLE
    ),

    actionRotate = TransitionProperty(
        centerPivotLevel = PivotLevel.BottomCenter,
        initialValue = -25F,
        targetValue = 25F,
        infinite = true,
        repeatMode = RepeatMode.Reverse,
        duration = 800,
        easing = EaseInOutCubic
    ),

    actionVerticalTransition = TransitionProperty(
        initialValue = 80F,
        targetValue = 80F
    ),

    actionSampleRotate = TransitionProperty(
        targetValue = 359F,
        duration = 1600,
        infinite = true,
    )
)

data object SunGlasses : RobotStatus(

    leftTopEyeRadius = TransitionProperty(
        targetValue = 40F
    ),
    leftBottomEyeRadius = TransitionProperty(
        targetValue = 0F
    ),
    rightTopEyeRadius = TransitionProperty(
        targetValue = 40F
    ),
    rightBottomEyeRadius = TransitionProperty(
        targetValue = 0F
    ),

    eyesVerticalTransition = TransitionProperty(
        initialValue = 0F,
        targetValue = 10F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionSample = ActionSample(
        SUNGLASS_SAMPLE
    ),

    actionVerticalTransition = TransitionProperty(
        initialValue = -20F,
        targetValue = -30F,
        duration = 400,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionScale = TransitionProperty(
        initialValue = 1F,
        targetValue = 2.8F
    )
)

data object TakePhoto : RobotStatus(

    leftTopEyeRadius = TransitionProperty(
        targetValue = 40F
    ),
    leftBottomEyeRadius = TransitionProperty(
        initialValue = 0F,
        targetValue = 40F,
        infinite = false,
        duration = 400
    ),
    rightTopEyeRadius = TransitionProperty(
        targetValue = 40F
    ),
    rightBottomEyeRadius = TransitionProperty(
        initialValue = 0F,
        targetValue = 40F,
        infinite = false,
        duration = 400
    ),

    eyesVerticalTransition = TransitionProperty(
        initialValue = -10F,
        targetValue = 0F,
        duration = 800,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    actionSample = ActionSample(
        CAMERA_SAMPLE
    ),

    actionVerticalTransition = TransitionProperty(
        initialValue = 150F,
        targetValue = 50F,
        duration = 600,
        infinite = false,
        repeatMode = RepeatMode.Reverse,
        easing = EaseOutQuad
    ),

    actionScale = TransitionProperty(
        initialValue = 1F,
        targetValue = 1.5F
    ),

    actionRotate = TransitionProperty(
        infinite = false,
        duration = 800
    ).apply {
        animationSpec = keyframes {
            durationMillis = duration
            0F at 0

            0F at 600

            10F at 700

            (0F) at 800
        }
    })

data object Focus : RobotStatus(

    leftEyeCornerRadius = TransitionProperty(
        targetValue = 50F
    ),

    rightEyeCornerRadius = TransitionProperty(
        targetValue = 50F
    ),

    leftEyeHorizontalRadius = TransitionProperty(
        targetValue = 50F
    ),

    rightEyeHorizontalRadius = TransitionProperty(
        targetValue = 50F
    ),

    leftTopEyeRadius = TransitionProperty(
        targetValue = 10F
    ),
    leftBottomEyeRadius = TransitionProperty(
        targetValue = 50F
    ),
    rightTopEyeRadius = TransitionProperty(
        targetValue = 10F
    ),
    rightBottomEyeRadius = TransitionProperty(
        targetValue = 50F
    ),

    eyesVerticalTransition = TransitionProperty(
        targetValue = 10F,
        duration = 600,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    ),

    leftEyeRotate = TransitionProperty(
        targetValue = 15F,
        duration = 300,
        easing = EaseIn
    ),

    rightEyeRotate = TransitionProperty(
        targetValue = -15F,
        duration = 300,
        easing = EaseIn
    ),

    eyesFillColor = "#FF0000",

    actionSample = ActionSample(FOCUS_SAMPLE),

    actionHorizontalTransition = TransitionProperty(
        targetValue = -80F
    ),

    actionVerticalTransition = TransitionProperty(
        initialValue = 90F,
        targetValue = 100F,
        duration = 600,
        infinite = true,
        repeatMode = RepeatMode.Reverse
    )

)
