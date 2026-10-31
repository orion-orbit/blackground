package com.minimal.hifi

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class ProbedAudioFormat(
    val codecLabel: String = "PCM",
    val sampleRate: Int = 44100,
    val bitDepth: Int = 16,
    val totalDurationMs: Long = 0L,
    val isDsd: Boolean = false,
    val dsdBlockSizePerChannel: Int = 4096,
    val dsdDataStartOffset: Long = 92L,
    val dsdDataEndOffset: Long = Long.MAX_VALUE
)

object AudioFormatProbe {
    fun probeUri(context: Context, uri: Uri): ProbedAudioFormat {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(96)
                val read = stream.read(header)
                if (read < 44) return ProbedAudioFormat()
                when (String(header, 0, 4, StandardCharsets.US_ASCII)) {
                    "fLaC" -> parseFlacStreamInfo(header)
                    "DSD " -> parseDsfFmtChunk(header)
                    "RIFF" -> parseWavFmtChunk(header)
                    else -> ProbedAudioFormat()
                }
            } ?: ProbedAudioFormat()
        } catch (e: Exception) {
            ProbedAudioFormat()
        }
    }

    private fun parseFlacStreamInfo(header: ByteArray): ProbedAudioFormat {
        val packed64 = ByteBuffer.wrap(header, 18, 8).order(ByteOrder.BIG_ENDIAN).long
        val sampleRate = ((packed64 ushr 44) and 0xFFFFFL).toInt()
        val bitDepth = (((packed64 ushr 36) and 0x1FL).toInt()) + 1
        val totalSamples = packed64 and 0xFFFFFFFFFL
        val durationMs = if (sampleRate > 0) (totalSamples * 1000L) / sampleRate else 0L
        return ProbedAudioFormat("FLAC", if (sampleRate > 0) sampleRate else 44100, bitDepth, durationMs, false)
    }

    private fun parseDsfFmtChunk(header: ByteArray): ProbedAudioFormat {
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buf.getInt(56)
        val totalSamples = buf.getLong(64)
        val blockSize = buf.getInt(72).coerceAtLeast(4096)
        val dataChunkSize = buf.getLong(84)
        val durationMs = if (sampleRate > 0) (totalSamples * 1000L) / sampleRate else 0L
        val tier = when (sampleRate) { 2822400 -> "DSD64"; 5644800 -> "DSD128"; else -> "DSD" }
        return ProbedAudioFormat(
            tier, sampleRate, 1, durationMs, true, blockSize, 92L,
            if (dataChunkSize > 12L) 80L + dataChunkSize else Long.MAX_VALUE
        )
    }

    private fun parseWavFmtChunk(header: ByteArray): ProbedAudioFormat {
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buf.getInt(24)
        val byteRate = buf.getInt(28)
        val bitDepth = buf.getShort(34).toInt()
        val dataSize = buf.getInt(40).toLong() and 0xFFFFFFFFL
        val durationMs = if (byteRate > 0) (dataSize * 1000L) / byteRate else 0L
        return ProbedAudioFormat("WAV", if (sampleRate > 0) sampleRate else 44100, if (bitDepth > 0) bitDepth else 16, durationMs, false)
    }
}
