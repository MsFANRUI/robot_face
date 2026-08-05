package com.popkter.robot

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.popkter.robot.ui.DrawAction
import com.popkter.robot.ui.DrawEyes
import com.popkter.robot.viewmodel.RobotViewModel


@Preview
@Composable
fun RobotPreview() {
    Robot(RobotViewModel())
}

@Composable
fun Robot(
    viewModel: RobotViewModel
) {

    val robotStatus by viewModel.robotStatus.collectAsStateWithLifecycle()

    val eyeTransitionState = remember { MutableTransitionState(robotStatus) }

    LaunchedEffect(robotStatus) {
        eyeTransitionState.targetState = robotStatus
        viewModel.updateRound(robotStatus)
    }

    val finiteTransition = rememberTransition(transitionState = eyeTransitionState, label = "finiteTransition")

    val textMeasurer = rememberTextMeasurer()

    Box(
        Modifier
            .fillMaxSize()
    ) {
        with(eyeTransitionState.targetState) {
            DrawEyes(
                modifier = Modifier.matchParentSize(),
                finiteTransition = finiteTransition,
            )

            DrawAction(
                modifier = Modifier.matchParentSize(),
                finiteTransition = finiteTransition,
                textMeasurer = textMeasurer
            )
        }
    }
}


