package com.minimal.hifi

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

data class SyncedLyricLine(val timestampMs: Long, val text: String)

object LrcLyricsParser {
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    suspend fun loadLyricsForTrack(context: Context, track: VirtualCueTrack, externalLrcUri: Uri? = null): List<SyncedLyricLine> =
        withContext(Dispatchers.IO) {
            val raw = externalLrcUri?.let {
                context.contentResolver.openInputStream(it)?.use { s -> BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).readText() }
            } ?: return@withContext emptyList()
            parseLrcString(raw, track.startMs)
        }

    fun parseLrcString(rawLrc: String, cueStartMs: Long = 0L): List<SyncedLyricLine> {
        val entries = mutableListOf<SyncedLyricLine>()
        for (rawLine in rawLrc.lines()) {
            val line = rawLine.trim()
            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val frac = (m.groupValues.getOrNull(3) ?: "0").padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                entries.add(SyncedLyricLine((min * 60_000L + sec * 1000L + frac).coerceAtLeast(0L), text))
            }
        }
        entries.sortBy { it.timestampMs }
        return entries
    }

    fun findActiveLineIndex(lines: List<SyncedLyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0; var high = lines.lastIndex; var best = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timestampMs <= positionMs + 150L) { best = mid; low = mid + 1 } else high = mid - 1
        }
        return best
    }
}
