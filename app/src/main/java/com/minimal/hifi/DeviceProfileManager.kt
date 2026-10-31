package com.minimal.hifi

import android.content.Context
import android.hardware.usb.UsbDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class DeviceAudioProfile(
    val deviceKey: String = KEY_INTERNAL_OS,
    val deviceDisplayName: String = "Phone / OS Output",
    val hardwareVolumeDb: Float = -20.0f,
    val eqEnabled: Boolean = false,
    val selectedPresetIndex: Int = 0,
    val bands: List<EqBandState> = GOD_TIER_PRESETS[0].bands,
    val loudnessMode: LoudnessMode = LoudnessMode.TRACK_GAIN,
    val dsdEngineMode: DsdEngineMode = DsdEngineMode.NATIVE_DOP
) {
    companion object {
        const val KEY_INTERNAL_OS = "OS_INTERNAL_DEFAULT"
        fun buildUsbKey(device: UsbDevice): String = "USB_%04X_%04X".format(device.vendorId, device.productId)
    }
}

class DeviceProfileManager(
    context: Context,
    private val scope: CoroutineScope,
    private val volumeManager: HardwareVolumeManager
) {
    private val prefs = context.getSharedPreferences("hifi_dac_profiles", Context.MODE_PRIVATE)
    private val _activeProfile = MutableStateFlow(loadProfile(DeviceAudioProfile.KEY_INTERNAL_OS, "Phone / OS Output"))
    val activeProfile: StateFlow<DeviceAudioProfile> = _activeProfile

    fun onOutputDeviceChanged(usbDevice: UsbDevice?) {
        val key = usbDevice?.let { DeviceAudioProfile.buildUsbKey(it) } ?: DeviceAudioProfile.KEY_INTERNAL_OS
        val name = usbDevice?.productName?.trim()?.takeIf { it.isNotEmpty() } ?: "Phone / OS Output"
        val profile = loadProfile(key, name)
        _activeProfile.value = profile

        scope.launch {
            if (usbDevice != null) {
                val safeInitialDb = minOf(profile.hardwareVolumeDb, -28.0f)
                UsbNativeBridge.setHardwareVolumeDb(safeInitialDb)
                volumeManager.syncFromDac()
            }
            UsbNativeBridge.setDsdPcmFallbackMode(profile.dsdEngineMode.isFallback, profile.dsdEngineMode.cutoffIdx)
            val maxBoost = profile.bands.filter { it.enabled }.maxOfOrNull { it.gainDb } ?: 0f
            val autoPreampDb = if (maxBoost > 0f) -(maxBoost + 0.3f) else 0f
            UsbNativeBridge.setEqEnabled(profile.eqEnabled)
            UsbNativeBridge.setEqPreamp(autoPreampDb.toDouble())
            profile.bands.forEachIndexed { idx, b ->
                UsbNativeBridge.updateEqBand(idx, b.type, b.freq.toDouble(), b.gainDb.toDouble(), b.q.toDouble(), b.enabled)
            }
            if (usbDevice != null) {
                var curDb = minOf(profile.hardwareVolumeDb, -28.0f)
                while (curDb < profile.hardwareVolumeDb) {
                    curDb = (curDb + 2.0f).coerceAtMost(profile.hardwareVolumeDb)
                    UsbNativeBridge.setHardwareVolumeDb(curDb)
                    delay(25)
                }
                volumeManager.setExactDb(profile.hardwareVolumeDb)
            }
        }
    }

    fun updateActiveProfile(transform: (DeviceAudioProfile) -> DeviceAudioProfile) {
        val updated = transform(_activeProfile.value)
        _activeProfile.value = updated
        saveProfile(updated)
    }

    private fun saveProfile(profile: DeviceAudioProfile) {
        val bandsArr = JSONArray()
        profile.bands.forEach { b ->
            bandsArr.put(JSONObject().apply {
                put("type", b.type); put("freq", b.freq.toDouble())
                put("gain", b.gainDb.toDouble()); put("q", b.q.toDouble()); put("en", b.enabled)
            })
        }
        val root = JSONObject().apply {
            put("name", profile.deviceDisplayName); put("volDb", profile.hardwareVolumeDb.toDouble())
            put("eqEn", profile.eqEnabled); put("presetIdx", profile.selectedPresetIndex)
            put("loudness", profile.loudnessMode.name); put("dsdMode", profile.dsdEngineMode.name)
            put("bands", bandsArr)
        }
        prefs.edit().putString(profile.deviceKey, root.toString()).apply()
    }

    private fun loadProfile(deviceKey: String, fallbackName: String): DeviceAudioProfile {
        val raw = prefs.getString(deviceKey, null) ?: return DeviceAudioProfile(deviceKey, fallbackName)
        return try {
            val obj = JSONObject(raw)
            val bandsArr = obj.optJSONArray("bands")
            val parsedBands = mutableListOf<EqBandState>()
            if (bandsArr != null) {
                for (i in 0 until bandsArr.length()) {
                    val b = bandsArr.getJSONObject(i)
                    parsedBands.add(EqBandState(b.optInt("type", 0), b.optDouble("freq", 1000.0).toFloat(), b.optDouble("gain", 0.0).toFloat(), b.optDouble("q", 0.71).toFloat(), b.optBoolean("en", true)))
                }
            }
            DeviceAudioProfile(
                deviceKey, obj.optString("name", fallbackName), obj.optDouble("volDb", -20.0).toFloat(),
                obj.optBoolean("eqEn", false), obj.optInt("presetIdx", 0),
                if (parsedBands.size == 5) parsedBands else GOD_TIER_PRESETS[0].bands,
                LoudnessMode.valueOf(obj.optString("loudness", LoudnessMode.TRACK_GAIN.name)),
                DsdEngineMode.valueOf(obj.optString("dsdMode", DsdEngineMode.NATIVE_DOP.name))
            )
        } catch (e: Exception) { DeviceAudioProfile(deviceKey, fallbackName) }
    }
}
