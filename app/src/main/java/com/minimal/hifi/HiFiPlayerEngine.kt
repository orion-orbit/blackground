package com.minimal.hifi

import android.content.Context
import android.media.AudioFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

@OptIn(UnstableApi::class)
class HiFiPlayerEngine(
    context: Context,
    private val dacManager: BitPerfectDacManager
) {
    private val eqProcessor = HiFiEqAudioProcessor()

    private val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(true)
                .setEnableAudioTrackPlaybackParams(true)
                .setAudioProcessors(arrayOf(eqProcessor))
                .build()
        }
    }

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context, renderersFactory).build().apply {
        addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                audioFormat?.let { lockHardwareSampleRate(it) }
            }
        })
    }

    private fun lockHardwareSampleRate(format: Format) {
        val rate = format.sampleRate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && rate > 0) {
            val enc = when (format.pcmEncoding) {
                C.ENCODING_PCM_24BIT -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                C.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
                C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
                else -> AudioFormat.ENCODING_PCM_16BIT
            }
            dacManager.enableNativeBitPerfectMode(rate, enc)
        }
    }

    fun playVirtualCueQueue(tracks: List<VirtualCueTrack>, startIndex: Int = 0) {
        val mediaItems = tracks.map { track ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(track.startMs)
                .setEndPositionMs(if (track.endMs == Long.MAX_VALUE) C.TIME_END_OF_SOURCE else track.endMs)
                .build()
            MediaItem.Builder()
                .setUri(track.audioUri)
                .setMediaId("${track.audioUri}#${track.trackNumber}")
                .setClippingConfiguration(clipping)
                .build()
        }
        exoPlayer.setMediaItems(mediaItems, startIndex, 0L)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun release() {
        exoPlayer.release()
    }
}
