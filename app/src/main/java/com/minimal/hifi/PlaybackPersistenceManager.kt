package com.minimal.hifi

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RestoredSession(
    val queue: List<VirtualCueTrack>,
    val currentIndex: Int,
    val positionMs: Long,
    val playbackSpeed: Float,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode
)

class PlaybackPersistenceManager(context: Context) {
    private val prefs = context.getSharedPreferences("hifi_playback_state", Context.MODE_PRIVATE)

    fun saveState(queue: List<VirtualCueTrack>, currentIndex: Int, positionMs: Long, speed: Float, shuffle: Boolean, repeat: RepeatMode) {
        val arr = JSONArray()
        queue.forEach { t ->
            arr.put(JSONObject().apply {
                put("num", t.trackNumber); put("title", t.title); put("perf", t.performer)
                put("album", t.albumTitle); put("uri", t.audioUri.toString())
                put("codec", t.format.codecLabel); put("rate", t.format.sampleRate)
                put("bits", t.format.bitDepth); put("dur", t.format.totalDurationMs)
                put("isDsd", t.format.isDsd); put("startMs", t.startMs); put("endMs", t.endMs)
                put("dsdStart", t.dsdStartByteOffset); put("dsdEnd", t.dsdEndByteOffset)
                put("rgTrack", t.replayGain.trackGainDb?.toDouble() ?: Double.NaN)
            })
        }
        prefs.edit()
            .putString("queue_json", arr.toString())
            .putInt("current_index", currentIndex)
            .putLong("position_ms", positionMs)
            .putFloat("playback_speed", speed)
            .putBoolean("shuffle_enabled", shuffle)
            .putString("repeat_mode", repeat.name)
            .apply()
    }

    fun restoreState(): RestoredSession? {
        val raw = prefs.getString("queue_json", null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val tracks = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val rg = o.optDouble("rgTrack", Double.NaN)
                val fmt = ProbedAudioFormat(
                    o.optString("codec", "FLAC"), o.optInt("rate", 44100),
                    o.optInt("bits", 24), o.optLong("dur", 0L), o.optBoolean("isDsd", false)
                )
                VirtualCueTrack(
                    o.optInt("num", i + 1), o.optString("title", "Track"), o.optString("perf", ""),
                    o.optString("album", ""), Uri.parse(o.getString("uri")), fmt,
                    o.optLong("startMs", 0L), o.optLong("endMs", Long.MAX_VALUE),
                    o.optLong("dsdStart", 92L), o.optLong("dsdEnd", Long.MAX_VALUE),
                    ReplayGainMetadata(if (!rg.isNaN()) rg.toFloat() else null)
                )
            }
            if (tracks.isEmpty()) return null
            RestoredSession(
                tracks, prefs.getInt("current_index", 0), prefs.getLong("position_ms", 0L),
                prefs.getFloat("playback_speed", 1.0f), prefs.getBoolean("shuffle_enabled", false),
                RepeatMode.valueOf(prefs.getString("repeat_mode", "OFF") ?: "OFF")
            )
        } catch (e: Exception) { null }
    }
}
