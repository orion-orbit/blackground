package com.minimal.hifi

import java.nio.ByteBuffer

object UsbNativeBridge {
    init {
        System.loadLibrary("hifi_usb_engine")
    }

    external fun initDirectUsbDac(fd: Int, sampleRate: Int, enableDop: Boolean): Boolean
    external fun pushDsdBlockToDoP(leftBlock: ByteArray, rightBlock: ByteArray, blockSize: Int): Int
    external fun pushDsdBlockAuto(leftBlock: ByteArray, rightBlock: ByteArray, blockSize: Int): Int
    external fun stopUsbEngine()

    external fun setEqEnabled(enabled: Boolean)
    external fun setEqPreamp(preampDb: Double)
    external fun updateEqBand(idx: Int, type: Int, freq: Double, gain: Double, q: Double, enabled: Boolean)
    external fun processFloatPcm(directBuffer: ByteBuffer, offset: Int, numFrames: Int, sampleRate: Int)

    external fun getHardwareVolumeRange(): FloatArray
    external fun setHardwareVolumeDb(targetDb: Float): Float
    external fun stepHardwareVolume(direction: Int): Float

    external fun markNextTrackBoundary(sampleRate: Int, isDop: Boolean, replayGainDb: Float, trackPeak: Float, trackId: Int)
    external fun getClockAndBufferTelemetry(): IntArray
    external fun setDsdPcmFallbackMode(enablePcmFallback: Boolean, cutoffMode: Int)
    external fun getDrTelemetry(): FloatArray

    external fun setPlaybackSpeed(speed: Float)
    external fun nativeSeekFlush(targetPositionMs: Long)
    external fun setUsbPaused(paused: Boolean)
    external fun getCurrentUsbPositionMs(): Long
}
