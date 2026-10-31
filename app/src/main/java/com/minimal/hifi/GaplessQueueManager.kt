package com.minimal.hifi

import android.content.Context
import androidx.media3.common.PlaybackParameters
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.random.Random

enum class RepeatMode { OFF, REPEAT_ALL, REPEAT_ONE }

data class TransportUiState(
    val activeTrack: VirtualCueTrack? = null,
    val nextTrackTitle: String? = null,
    val queueIndex: Int = 0,
    val queueSize: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF
)

class GaplessQueueManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val playerEngine: HiFiPlayerEngine,
    private val persistenceManager: PlaybackPersistenceManager
) {
    private val originalQueue = mutableListOf<VirtualCueTrack>()
    private val activePlayQueue = mutableListOf<VirtualCueTrack>()
    private var currentIndex = 0
    private var usbStreamingJob: Job? = null
    @Volatile private var pendingUsbSeekMs: Long? = null

    private val _uiState = MutableStateFlow(TransportUiState())
    val uiState: StateFlow<TransportUiState> = _uiState

    init {
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                val track = activePlayQueue.getOrNull(currentIndex)
                if (track != null && _uiState.value.isPlaying) {
                    val livePos = if (track.format.isDsd && usbStreamingJob?.isActive == true) {
                        UsbNativeBridge.getCurrentUsbPositionMs()
                    } else {
                        playerEngine.exoPlayer.currentPosition
                    }
                    _uiState.value = _uiState.value.copy(
                        positionMs = livePos.coerceIn(0L, track.durationMs.coerceAtLeast(1L)),
                        durationMs = track.durationMs
                    )
                }
                delay(200)
            }
        }
    }

    fun loadQueue(tracks: List<VirtualCueTrack>, startIndex: Int = 0, startPositionMs: Long = 0L, autoPlay: Boolean = true, usbFd: Int? = null) {
        if (tracks.isEmpty()) return
        originalQueue.clear(); originalQueue.addAll(tracks)
        rebuildPlayQueue(startIndex)
        val target = activePlayQueue.getOrNull(currentIndex) ?: return
        syncState(startPositionMs, autoPlay)

        if (target.format.isDsd && usbFd != null) {
            playerEngine.exoPlayer.stop()
            startDirectUsbChannelLoop(usbFd, startPositionMs, autoPlay)
        } else {
            UsbNativeBridge.stopUsbEngine()
            playerEngine.playVirtualCueQueue(activePlayQueue, currentIndex)
            if (startPositionMs > 0L) playerEngine.exoPlayer.seekTo(startPositionMs)
            if (!autoPlay) playerEngine.exoPlayer.pause()
        }
        saveSnapshot()
    }

    fun seekToMs(targetMs: Long) {
        val current = activePlayQueue.getOrNull(currentIndex) ?: return
        val clamped = targetMs.coerceIn(0L, current.durationMs.coerceAtLeast(1L))
        if (current.format.isDsd && usbStreamingJob?.isActive == true) {
            pendingUsbSeekMs = clamped
            UsbNativeBridge.nativeSeekFlush(clamped)
        } else {
            playerEngine.exoPlayer.seekTo(clamped)
        }
        _uiState.value = _uiState.value.copy(positionMs = clamped)
        saveSnapshot()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clean = ((speed.coerceIn(0.50f, 2.00f) * 20f).toInt()) / 20f
        UsbNativeBridge.setPlaybackSpeed(clean)
        playerEngine.exoPlayer.playbackParameters = PlaybackParameters(clean, 1.0f)
        _uiState.value = _uiState.value.copy(playbackSpeed = clean)
        saveSnapshot()
    }

    fun togglePlayPause() {
        val next = !_uiState.value.isPlaying
        val current = activePlayQueue.getOrNull(currentIndex) ?: return
        if (current.format.isDsd && usbStreamingJob?.isActive == true) {
            UsbNativeBridge.setUsbPaused(!next)
        } else {
            if (next) playerEngine.exoPlayer.play() else playerEngine.exoPlayer.pause()
        }
        _uiState.value = _uiState.value.copy(isPlaying = next)
        saveSnapshot()
    }

    fun skipToNext(usbFd: Int? = null) {
        if (activePlayQueue.isEmpty()) return
        currentIndex = when (_uiState.value.repeatMode) {
            RepeatMode.REPEAT_ONE -> currentIndex
            RepeatMode.REPEAT_ALL -> (currentIndex + 1) % activePlayQueue.size
            RepeatMode.OFF -> if (currentIndex + 1 < activePlayQueue.size) currentIndex + 1 else return
        }
        loadQueue(activePlayQueue, currentIndex, 0L, true, usbFd)
    }

    fun skipToPrevious(usbFd: Int? = null) {
        if (_uiState.value.positionMs > 3000L) { seekToMs(0L); return }
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        loadQueue(activePlayQueue, currentIndex, 0L, true, usbFd)
    }

    fun toggleShuffle() {
        _uiState.value = _uiState.value.copy(shuffleEnabled = !_uiState.value.shuffleEnabled)
        rebuildPlayQueue(currentIndex)
        saveSnapshot()
    }

    fun cycleRepeatMode() {
        val modes = RepeatMode.entries
        _uiState.value = _uiState.value.copy(repeatMode = modes[(_uiState.value.repeatMode.ordinal + 1) % modes.size])
        saveSnapshot()
    }

    private fun startDirectUsbChannelLoop(usbFd: Int, initialSeekMs: Long, startPlaying: Boolean) {
        usbStreamingJob?.cancel()
        val forcePcm = _uiState.value.playbackSpeed != 1.0f
        if (forcePcm) UsbNativeBridge.setDsdPcmFallbackMode(true, 0)
        if (!UsbNativeBridge.initDirectUsbDac(usbFd, if (forcePcm) 88200 else 176400, !forcePcm)) return
        UsbNativeBridge.setUsbPaused(!startPlaying)
        UsbNativeBridge.nativeSeekFlush(initialSeekMs)

        usbStreamingJob = scope.launch(Dispatchers.IO) {
            var offsetMs = initialSeekMs
            while (isActive && currentIndex < activePlayQueue.size) {
                val track = activePlayQueue[currentIndex]
                syncState(offsetMs, _uiState.value.isPlaying)
                UsbNativeBridge.markNextTrackBoundary(
                    if (_uiState.value.playbackSpeed != 1.0f) 88200 else 176400,
                    _uiState.value.playbackSpeed == 1.0f,
                    track.replayGain.effectiveTrackGainDb, track.replayGain.trackPeak, track.trackNumber
                )
                context.contentResolver.openFileDescriptor(track.audioUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                        val blockSize = track.format.dsdBlockSizePerChannel
                        var curByte = computeAlignedDsfOffset(track, offsetMs)
                        val endByte = track.dsdEndByteOffset.coerceAtMost(ch.size())
                        ch.position(curByte)
                        val lBuf = ByteArray(blockSize); val rBuf = ByteArray(blockSize)
                        val lNio = ByteBuffer.wrap(lBuf); val rNio = ByteBuffer.wrap(rBuf)

                        while (isActive && curByte + (blockSize * 2) <= endByte) {
                            pendingUsbSeekMs?.let { req ->
                                pendingUsbSeekMs = null
                                curByte = computeAlignedDsfOffset(track, req)
                                ch.position(curByte)
                            }
                            lNio.clear(); rNio.clear()
                            if (ch.read(lNio) < blockSize || ch.read(rNio) < blockSize) break
                            curByte += (blockSize * 2)
                            var written = 0
                            while (isActive && written == 0 && pendingUsbSeekMs == null) {
                                written = UsbNativeBridge.pushDsdBlockAuto(lBuf, rBuf, blockSize)
                                if (written == 0) delay(3)
                            }
                        }
                    }
                }
                offsetMs = 0L
                when (_uiState.value.repeatMode) {
                    RepeatMode.REPEAT_ONE -> {}
                    RepeatMode.REPEAT_ALL -> currentIndex = (currentIndex + 1) % activePlayQueue.size
                    RepeatMode.OFF -> if (currentIndex + 1 < activePlayQueue.size) currentIndex++ else break
                }
            }
        }
    }

    private fun computeAlignedDsfOffset(track: VirtualCueTrack, relativeMs: Long): Long {
        if (relativeMs <= 0L) return track.dsdStartByteOffset
        val fmt = track.format
        val blockIdx = ((relativeMs * fmt.sampleRate) / 1000L) / (fmt.dsdBlockSizePerChannel * 8L)
        return (track.dsdStartByteOffset + blockIdx * (fmt.dsdBlockSizePerChannel * 2L)).coerceAtMost(track.dsdEndByteOffset)
    }

    private fun rebuildPlayQueue(keepIndex: Int) {
        val cur = activePlayQueue.getOrNull(keepIndex) ?: originalQueue.getOrNull(keepIndex)
        activePlayQueue.clear(); activePlayQueue.addAll(originalQueue)
        if (_uiState.value.shuffleEnabled && activePlayQueue.size > 1) {
            activePlayQueue.shuffle(Random(System.currentTimeMillis()))
            cur?.let { t ->
                val idx = activePlayQueue.indexOfFirst { it.audioUri == t.audioUri && it.startMs == t.startMs }
                if (idx > 0) activePlayQueue.add(0, activePlayQueue.removeAt(idx))
            }
            currentIndex = 0
        } else {
            currentIndex = keepIndex.coerceIn(0, (activePlayQueue.size - 1).coerceAtLeast(0))
        }
    }

    private fun syncState(posMs: Long, playing: Boolean) {
        val cur = activePlayQueue.getOrNull(currentIndex)
        val next = activePlayQueue.getOrNull(currentIndex + 1)
        _uiState.value = _uiState.value.copy(
            activeTrack = cur, nextTrackTitle = next?.title,
            queueIndex = currentIndex, queueSize = activePlayQueue.size,
            isPlaying = playing, positionMs = posMs, durationMs = cur?.durationMs ?: 0L
        )
    }

    fun saveSnapshot() {
        persistenceManager.saveState(
            originalQueue, currentIndex, _uiState.value.positionMs,
            _uiState.value.playbackSpeed, _uiState.value.shuffleEnabled, _uiState.value.repeatMode
        )
    }
}
