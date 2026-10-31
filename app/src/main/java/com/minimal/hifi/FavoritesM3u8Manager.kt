package com.minimal.hifi

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class FavoritesM3u8Manager(private val context: Context, private val scope: CoroutineScope) {
    private val _favoriteKeys = MutableStateFlow<Set<String>>(emptySet())
    val favoriteKeys: StateFlow<Set<String>> = _favoriteKeys
    private val favoriteTracksMap = linkedMapOf<String, VirtualCueTrack>()

    companion object {
        private const val M3U8_FILENAME = "Favorites.m3u8"
        fun trackKey(t: VirtualCueTrack): String = "${t.audioUri}#${t.startMs}"
    }

    fun isFavorite(t: VirtualCueTrack): Boolean = _favoriteKeys.value.contains(trackKey(t))
    fun getAllFavoriteTracks(): List<VirtualCueTrack> = favoriteTracksMap.values.toList()

    fun toggleFavorite(track: VirtualCueTrack, rootTreeUri: Uri?) {
        val key = trackKey(track)
        if (favoriteTracksMap.containsKey(key)) favoriteTracksMap.remove(key) else favoriteTracksMap[key] = track
        _favoriteKeys.value = favoriteTracksMap.keys.toSet()
        if (rootTreeUri != null) {
            scope.launch(Dispatchers.IO) { writeM3u8(rootTreeUri, favoriteTracksMap.values.toList()) }
        }
    }

    fun loadFromSafRoot(rootTreeUri: Uri) {
        scope.launch(Dispatchers.IO) {
            val m3uUri = findOrCreateM3u8(rootTreeUri, false) ?: return@launch
            context.contentResolver.openInputStream(m3uUri)?.use { s ->
                val lines = BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).readLines()
                var title = "Track"; var perf = ""; var sMs = 0L; var eMs = Long.MAX_VALUE; var dS = 92L; var dE = Long.MAX_VALUE
                for (raw in lines) {
                    val l = raw.trim()
                    if (l.isEmpty() || l == "#EXTM3U") continue
                    when {
                        l.startsWith("#EXTINF:") -> {
                            val m = l.substringAfter(",", "Track")
                            perf = m.substringBefore(" - ", "").trim()
                            title = m.substringAfter(" - ", m).trim()
                        }
                        l.startsWith("#EXTHIFI:") -> {
                            val map = l.removePrefix("#EXTHIFI:").split("|").mapNotNull { kv ->
                                val p = kv.split("="); if (p.size == 2) p[0] to p[1] else null
                            }.toMap()
                            sMs = map["START_MS"]?.toLongOrNull() ?: 0L
                            eMs = map["END_MS"]?.toLongOrNull() ?: Long.MAX_VALUE
                            dS = map["DSD_START"]?.toLongOrNull() ?: 92L
                            dE = map["DSD_END"]?.toLongOrNull() ?: Long.MAX_VALUE
                        }
                        !l.startsWith("#") -> {
                            val uri = Uri.parse(l)
                            val fmt = AudioFormatProbe.probeUri(context, uri)
                            val rg = ReplayGainParser.parseFromUri(context, uri)
                            val t = VirtualCueTrack(favoriteTracksMap.size + 1, title, perf, "Favorites", uri, fmt, sMs, eMs, dS, dE, rg)
                            favoriteTracksMap[trackKey(t)] = t
                            sMs = 0L; eMs = Long.MAX_VALUE
                        }
                    }
                }
                _favoriteKeys.value = favoriteTracksMap.keys.toSet()
            }
        }
    }

    private fun writeM3u8(rootTreeUri: Uri, tracks: List<VirtualCueTrack>) {
        val uri = findOrCreateM3u8(rootTreeUri, true) ?: return
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            OutputStreamWriter(out, StandardCharsets.UTF_8).use { w ->
                w.write("#EXTM3U\n")
                tracks.forEach { t ->
                    val sec = (t.durationMs / 1000L).coerceAtLeast(1L)
                    val art = if (t.performer.isNotEmpty()) "${t.performer} - " else ""
                    w.write("#EXTINF:$sec,$art${t.title}\n")
                    w.write("#EXTHIFI:START_MS=${t.startMs}|END_MS=${t.endMs}|DSD_START=${t.dsdStartByteOffset}|DSD_END=${t.dsdEndByteOffset}\n")
                    w.write("${t.audioUri}\n")
                }
            }
        }
    }

    private fun findOrCreateM3u8(rootTreeUri: Uri, create: Boolean): Uri? {
        val rootId = DocumentsContract.getTreeDocumentId(rootTreeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootTreeUri, rootId)
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.equals(M3U8_FILENAME, true)) return DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, id)
            }
        }
        if (!create) return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(rootTreeUri, rootId)
        return DocumentsContract.createDocument(context.contentResolver, parentUri, "audio/x-mpegurl", M3U8_FILENAME)
    }
}
