package com.popkter.robot

import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.popkter.robot.status.RobotStatus
import com.popkter.robot.viewmodel.RobotViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.collectLatest


@Preview
@Composable
fun RobotMenuPreview() {
    RobotMenu(RobotViewModel())
}

@Composable
fun RobotMenu(viewModel: RobotViewModel) {

    val current by viewModel.robotStatus.collectAsStateWithLifecycle()
    val round by viewModel.robotStatusRound.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        snapshotFlow { round }.collectLatest {
            Log.w("RobotMenu", "status: ${it.first} round: ${it.second}")
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {

        Column(
            modifier = Modifier
                .weight(1F),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "status: ${round.first} round: ${round.second}"
            )

            Robot(viewModel)
        }

        Row(
            modifier = Modifier.weight(1F)
        ) {
            LazyVerticalGrid(
                modifier = Modifier.fillMaxSize(),
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(5.dp)
            ) {
                items(items = RobotStatus.allStates, key = { it.toString() }) { state ->
                    val isSelected = current == state
                    Surface(
                        modifier = Modifier
                            .padding(5.dp)
                            .height(40.dp)
                            .border(
                                width = 1.dp, brush = Brush.verticalGradient(
                                    colors = listOf(Color.Red, Color.White, Color.Gray)
                                ), shape = RoundedCornerShape(5.dp)
                            )
                            .clip(RoundedCornerShape(5.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { viewModel.updateStatus(state) })
                            }
                            .clickable { viewModel.updateStatus(state) },
                        color = if (isSelected) Color.Cyan.copy(alpha = 0.3F) else Color.Gray.copy(alpha = 0.1F),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = state.toString())
                        }
                    }
                }
            }
        }

    }
}


