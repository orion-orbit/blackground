package com.minimal.hifi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SleepTimerPreset(val label: String, val durationSeconds: Int) {
    OFF("OFF", 0), MIN_15("15m", 900), MIN_30("30m", 1800), MIN_60("60m", 3600), END_OF_TRACK("END OF TRACK", -1)
}

data class SleepTimerState(
    val activePreset: SleepTimerPreset = SleepTimerPreset.OFF,
    val remainingSeconds: Int = 0,
    val isFadingOut: Boolean = false
)

class SleepTimerManager(
    private val scope: CoroutineScope,
    private val queueManager: GaplessQueueManager,
    private val volumeManager: HardwareVolumeManager,
    private val onReleaseUsbAndWakeLock: () -> Unit
) {
    private var timerJob: Job? = null
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state

    fun setTimer(preset: SleepTimerPreset) {
        timerJob?.cancel()
        if (preset == SleepTimerPreset.OFF) {
            _state.value = SleepTimerState(SleepTimerPreset.OFF, 0, false)
            return
        }
        val preFadeDb = volumeManager.state.value.currentDb
        if (preset == SleepTimerPreset.END_OF_TRACK) {
            val initialTrack = queueManager.uiState.value.activeTrack
            timerJob = scope.launch(Dispatchers.Main) {
                while (isActive) {
                    val ui = queueManager.uiState.value
                    val remMs = (ui.durationMs - ui.positionMs).coerceAtLeast(0L)
                    val remSec = (remMs / 1000L).toInt()
                    _state.value = SleepTimerState(preset, remSec, remSec in 1..10)
                    if (remSec in 1..10 && ui.durationMs > 15_000L) {
                        UsbNativeBridge.setHardwareVolumeDb((preFadeDb - (10 - remSec) * 1.5f).coerceAtLeast(volumeManager.state.value.minDb))
                    }
                    if ((ui.durationMs > 0L && remMs <= 450L) || ui.activeTrack != initialTrack) {
                        shutdown(preFadeDb); break
                    }
                    delay(250L)
                }
            }
        } else {
            _state.value = SleepTimerState(preset, preset.durationSeconds, false)
            timerJob = scope.launch(Dispatchers.Main) {
                var rem = preset.durationSeconds
                while (isActive && rem > 0) {
                    delay(1000L); rem--
                    val fading = rem in 1..60
                    if (fading) {
                        UsbNativeBridge.setHardwareVolumeDb((preFadeDb - (60 - rem) * 0.5f).coerceAtLeast(volumeManager.state.value.minDb))
                    }
                    _state.value = SleepTimerState(preset, rem, fading)
                }
                if (isActive) shutdown(preFadeDb)
            }
        }
    }

    private fun shutdown(restoredDb: Float) {
        if (queueManager.uiState.value.isPlaying) queueManager.togglePlayPause()
        UsbNativeBridge.stopUsbEngine()
        onReleaseUsbAndWakeLock()
        UsbNativeBridge.setHardwareVolumeDb(restoredDb)
        volumeManager.setExactDb(restoredDb)
        _state.value = SleepTimerState(SleepTimerPreset.OFF, 0, false)
    }
}
