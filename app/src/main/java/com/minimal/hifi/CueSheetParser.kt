package com.minimal.hifi

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

data class VirtualCueTrack(
    val trackNumber: Int,
    val title: String,
    val performer: String,
    val albumTitle: String,
    val audioUri: Uri,
    val format: ProbedAudioFormat,
    val startMs: Long,
    val endMs: Long,
    val dsdStartByteOffset: Long,
    val dsdEndByteOffset: Long,
    val replayGain: ReplayGainMetadata
) {
    val durationMs: Long
        get() = if (endMs != Long.MAX_VALUE && endMs > startMs) endMs - startMs
        else (format.totalDurationMs - startMs).coerceAtLeast(0L)
}

object CueSheetParser {
    private val QUOTED = Regex(""""([^"]+)"""")

    fun parseCueSheet(context: Context, cueUri: Uri, folderFilesByName: Map<String, Uri>): List<VirtualCueTrack> {
        val lines = context.contentResolver.openInputStream(cueUri)?.use {
            BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readLines()
        } ?: return emptyList()

        var albumTitle = "Unknown Album"; var albumPerformer = ""; var albumGain: Float? = null; var curFile: String? = null
        data class Raw(val num: Int, val file: String, var title: String = "", var perf: String = "", var idxMs: Long = 0L, var tg: Float? = null)
        val raws = mutableListOf<Raw>()
        var cur: Raw? = null

        for (rawLine in lines) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            val up = line.uppercase()
            when {
                up.startsWith("FILE ") -> curFile = QUOTED.find(line)?.groupValues?.get(1) ?: line.substringAfter("FILE ").substringBeforeLast(" ").trim()
                up.startsWith("TRACK ") && up.contains("AUDIO") -> {
                    cur?.let { raws.add(it) }
                    val num = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: (raws.size + 1)
                    cur = Raw(num, curFile ?: "", perf = albumPerformer)
                }
                up.startsWith("TITLE ") -> {
                    val v = QUOTED.find(line)?.groupValues?.get(1) ?: line.substringAfter("TITLE").trim()
                    if (cur == null) albumTitle = v else cur.title = v
                }
                up.startsWith("PERFORMER ") -> {
                    val v = QUOTED.find(line)?.groupValues?.get(1) ?: line.substringAfter("PERFORMER").trim()
                    if (cur == null) albumPerformer = v else cur.perf = v
                }
                up.startsWith("INDEX 01 ") -> cur?.idxMs = parseCueTimeToMs(line.substringAfter("INDEX 01 ").trim())
                up.startsWith("REM REPLAYGAIN_ALBUM_GAIN ") -> albumGain = line.substringAfter("REM REPLAYGAIN_ALBUM_GAIN ").lowercase().replace("db", "").replace("+", "").trim().toFloatOrNull()
                up.startsWith("REM REPLAYGAIN_TRACK_GAIN ") -> cur?.tg = line.substringAfter("REM REPLAYGAIN_TRACK_GAIN ").lowercase().replace("db", "").replace("+", "").trim().toFloatOrNull()
            }
        }
        cur?.let { raws.add(it) }

        val fmtCache = mutableMapOf<Uri, ProbedAudioFormat>()
        return raws.mapIndexedNotNull { i, r ->
            val uri = resolveUri(r.file, folderFilesByName) ?: return@mapIndexedNotNull null
            val fmt = fmtCache.getOrPut(uri) { AudioFormatProbe.probeUri(context, uri) }
            val nextSame = raws.getOrNull(i + 1)?.takeIf { it.file == r.file }
            val startMs = r.idxMs
            val endMs = nextSame?.idxMs ?: Long.MAX_VALUE
            val dsdStart = if (fmt.isDsd) msToDsfOffset(startMs, fmt) else 0L
            val dsdEnd = if (fmt.isDsd && endMs != Long.MAX_VALUE) msToDsfOffset(endMs, fmt) else fmt.dsdDataEndOffset
            VirtualCueTrack(
                r.num, r.title.ifEmpty { "Track ${r.num}" }, r.perf, albumTitle,
                uri, fmt, startMs, endMs, dsdStart, dsdEnd, ReplayGainMetadata(r.tg, albumGain)
            )
        }
    }

    private fun parseCueTimeToMs(s: String): Long {
        val p = s.split(":")
        if (p.size != 3) return 0L
        return (((p[0].toLongOrNull() ?: 0L) * 60L + (p[1].toLongOrNull() ?: 0L)) * 1000L) + (((p[2].toLongOrNull() ?: 0L) * 1000L) / 75L)
    }

    private fun msToDsfOffset(ms: Long, fmt: ProbedAudioFormat): Long {
        if (ms <= 0L) return fmt.dsdDataStartOffset
        val samplesPerBlock = fmt.dsdBlockSizePerChannel * 8L
        val blockIdx = ((ms * fmt.sampleRate) / 1000L) / samplesPerBlock
        return fmt.dsdDataStartOffset + blockIdx * (fmt.dsdBlockSizePerChannel * 2L)
    }

    private fun resolveUri(name: String, map: Map<String, Uri>): Uri? {
        map[name.lowercase()]?.let { return it }
        val base = name.substringBeforeLast(".").lowercase()
        return map.entries.firstOrNull { it.key.substringBeforeLast(".") == base && !it.key.endsWith(".cue") }?.value
    }
}
