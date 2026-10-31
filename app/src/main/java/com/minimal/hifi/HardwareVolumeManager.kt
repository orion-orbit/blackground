package com.minimal.hifi

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HardwareVolumeState(
    val minDb: Float = -60.0f,
    val maxDb: Float = 0.0f,
    val stepDb: Float = 0.5f,
    val currentDb: Float = -18.0f,
    val hudVisible: Boolean = false
)

class HardwareVolumeManager(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(HardwareVolumeState())
    val state: StateFlow<HardwareVolumeState> = _state
    private var hideHudJob: Job? = null

    fun syncFromDac() {
        val r = UsbNativeBridge.getHardwareVolumeRange()
        if (r.size == 4) _state.value = _state.value.copy(minDb = r[0], maxDb = r[1], stepDb = r[2], currentDb = r[3])
    }

    fun onVolumeKeyStep(direction: Int) {
        val db = UsbNativeBridge.stepHardwareVolume(direction)
        _state.value = _state.value.copy(currentDb = db, hudVisible = true)
        scheduleAutoHide()
    }

    fun setExactDb(targetDb: Float) {
        val db = UsbNativeBridge.setHardwareVolumeDb(targetDb)
        _state.value = _state.value.copy(currentDb = db, hudVisible = true)
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        hideHudJob?.cancel()
        hideHudJob = scope.launch { delay(2200); _state.value = _state.value.copy(hudVisible = false) }
    }
}
