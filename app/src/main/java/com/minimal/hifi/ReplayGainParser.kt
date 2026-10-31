package com.minimal.hifi

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

enum class LoudnessMode { OFF, TRACK_GAIN, ALBUM_GAIN }

data class ReplayGainMetadata(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float = 1.0f,
    val albumPeak: Float = 1.0f
) {
    val effectiveTrackGainDb: Float get() = trackGainDb ?: albumGainDb ?: 0.0f
    fun resolveGainDb(mode: LoudnessMode, fallback: Float = 0.0f): Float = when (mode) {
        LoudnessMode.OFF -> 0.0f
        LoudnessMode.TRACK_GAIN -> trackGainDb ?: albumGainDb ?: fallback
        LoudnessMode.ALBUM_GAIN -> albumGainDb ?: trackGainDb ?: fallback
    }
}

object ReplayGainParser {
    fun parseFromUri(context: Context, uri: Uri): ReplayGainMetadata {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val magic = ByteArray(4)
                if (stream.read(magic) < 4) return ReplayGainMetadata()
                when (String(magic, StandardCharsets.US_ASCII)) {
                    "fLaC" -> parseFlacVorbis(stream)
                    else -> ReplayGainMetadata()
                }
            } ?: ReplayGainMetadata()
        } catch (e: Exception) {
            ReplayGainMetadata()
        }
    }

    private fun parseFlacVorbis(stream: InputStream): ReplayGainMetadata {
        var isLast = false
        val header = ByteArray(4)
        while (!isLast) {
            if (stream.read(header) < 4) break
            val b0 = header[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val type = b0 and 0x7F
            val len = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
            if (type == 4 && len in 8..1_048_576) {
                val payload = ByteArray(len)
                var off = 0
                while (off < len) {
                    val r = stream.read(payload, off, len - off)
                    if (r <= 0) break
                    off += r
                }
                return extractVorbis(payload)
            } else {
                var rem = len.toLong()
                while (rem > 0) { val s = stream.skip(rem); if (s <= 0) break; rem -= s }
            }
        }
        return ReplayGainMetadata()
    }

    private fun extractVorbis(bytes: ByteArray): ReplayGainMetadata {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vLen = buf.int
        if (vLen < 0 || buf.remaining() < vLen + 4) return ReplayGainMetadata()
        buf.position(buf.position() + vLen)
        val count = buf.int
        var tg: Float? = null; var ag: Float? = null; var tp = 1.0f; var ap = 1.0f
        for (i in 0 until count) {
            if (buf.remaining() < 4) break
            val len = buf.int
            if (len <= 0 || buf.remaining() < len) break
            val sBytes = ByteArray(len)
            buf.get(sBytes)
            val entry = String(sBytes, StandardCharsets.UTF_8)
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val k = entry.substring(0, eq).uppercase()
            val v = entry.substring(eq + 1).trim()
            when (k) {
                "REPLAYGAIN_TRACK_GAIN" -> tg = v.lowercase().replace("db", "").replace("+", "").trim().toFloatOrNull()
                "REPLAYGAIN_ALBUM_GAIN" -> ag = v.lowercase().replace("db", "").replace("+", "").trim().toFloatOrNull()
                "REPLAYGAIN_TRACK_PEAK" -> tp = v.toFloatOrNull() ?: 1.0f
                "REPLAYGAIN_ALBUM_PEAK" -> ap = v.toFloatOrNull() ?: 1.0f
                "R128_TRACK_GAIN" -> if (tg == null) tg = v.toIntOrNull()?.let { (it / 256.0f) + 5.0f }
                "R128_ALBUM_GAIN" -> if (ag == null) ag = v.toIntOrNull()?.let { (it / 256.0f) + 5.0f }
            }
        }
        return ReplayGainMetadata(tg, ag, tp, ap)
    }
}
