package com.popkter.robot.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.popkter.robot.status.Blink
import com.popkter.robot.status.Ordinary
import com.popkter.robot.status.RobotStatus
import com.popkter.robot.status.RobotStatus.Companion.canBlinkState
import com.popkter.robot.status.Think
import com.popkter.robot.status.transitionMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RobotViewModel : ViewModel() {

    companion object {
        private const val TAG = "RobotViewModel"
    }

    // 使用官方 viewModelScope（自动跟随 Activity 生命周期取消）
    // 不再自定义 CoroutineScope

    private val mRobotStatusFlow = MutableStateFlow<RobotStatus>(Ordinary)
    private val _status = MutableStateFlow<RobotStatus>(Ordinary)
    val robotStatus: StateFlow<RobotStatus> = _status.asStateFlow()

    private val _statusRound = MutableStateFlow<Pair<RobotStatus, Int>>(Ordinary to 0)
    val robotStatusRound: StateFlow<Pair<RobotStatus, Int>> = _statusRound.asStateFlow()

    private var blinkJob: Job? = null
    private var roundCalculationJob: Job? = null
    private var round = 0

    fun updateStatus(status: RobotStatus) {
        viewModelScope.launch {
            mRobotStatusFlow.emit(status)
        }
    }

    fun updateRound(status: RobotStatus) {
        roundCalculationJob?.cancel()
        round = 0
        roundCalculationJob = viewModelScope.launch {
            val duration = status.transitionMap.map { it.value }.maxOfOrNull { it.duration } ?: 200L
            val isInfinite = status.transitionMap.map { it.value }.any { it.infinite }
            if (isInfinite) {
                while (true) {
                    delay(duration.toLong())
                    ++round
                    _statusRound.emit(status to round)
                }
            } else {
                delay(duration.toLong())
                _statusRound.emit(status to 1)
            }
        }
    }

    init {
        viewModelScope.launch {
            mRobotStatusFlow.collectLatest {
                Log.d(TAG, "mRobotStatusFlow: $it canBlinkState: ${it.canBlinkState()}")
                if (isActive) {
                    _status.emit(it)
                    blinkJob?.cancel()
                    if (it.canBlinkState()) {
                        blinkJob = viewModelScope.launch {
                            val rng = (240..3000)
                            while (isActive && _status.value.canBlinkState()) {
                                delay(rng.random().toLong())
                                if (!_status.value.canBlinkState()) break
                                _status.emit(Blink)
                                delay(400)
                                if (!_status.value.canBlinkState()) break
                                _status.emit(Ordinary)
                            }
                        }
                    }
                }
            }
        }
    }
}
