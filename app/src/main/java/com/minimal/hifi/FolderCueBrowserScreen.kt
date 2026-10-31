package com.minimal.hifi

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FolderNode(val name: String, val docId: String)

@Composable
fun ProfileAndSleepTimerBar(
    activeProfile: DeviceAudioProfile,
    sleepState: SleepTimerState,
    onSelectSleepPreset: (SleepTimerPreset) -> Unit
) {
    Surface(
        color = Color(0xFF12141C),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF1E2230), RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AUTO-PROFILE: ${activeProfile.deviceDisplayName.uppercase()}",
                        color = Color(0xFF4ADE80),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ID: ${activeProfile.deviceKey}  •  VOL: ${"%.1f".format(activeProfile.hardwareVolumeDb)} dB",
                        color = Color(0xFF94A3B8),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = if (activeProfile.eqEnabled) "EQ: ON" else "BIT-PERFECT",
                    color = if (activeProfile.eqEnabled) Color(0xFF38BDF8) else Color(0xFF4ADE80),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Divider(color = Color(0xFF1E2230))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusText = when {
                    sleepState.activePreset == SleepTimerPreset.OFF -> "SLEEP TIMER: OFF"
                    sleepState.isFadingOut -> "HW FADING (-0.5dB/s): ${sleepState.remainingSeconds}s"
                    else -> "SLEEP IN ${sleepState.remainingSeconds / 60}m ${sleepState.remainingSeconds % 60}s"
                }
                Text(
                    text = statusText,
                    color = if (sleepState.isFadingOut) Color(0xFFFACC15) else Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SleepTimerPreset.entries.forEach { preset ->
                        val selected = sleepState.activePreset == preset
                        val shortLabel = if (preset == SleepTimerPreset.END_OF_TRACK) "EOT" else preset.label
                        Text(
                            text = shortLabel,
                            color = if (selected) Color.Black else Color.LightGray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selected) Color(0xFF38BDF8) else Color(0xFF1E2230))
                                .clickable { onSelectSleepPreset(preset) }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchAndFavoritesFilterBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    showOnlyFavorites: Boolean,
    favoritesCount: Int,
    onToggleFavoritesView: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    text = "Search title, artist, DSD64, 192k...",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            ),
            modifier = Modifier.weight(1f).height(48.dp)
        )

        Surface(
            color = if (showOnlyFavorites) Color(0xFFFACC15) else Color(0xFF161822),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .height(48.dp)
                .border(
                    width = 1.dp,
                    color = if (showOnlyFavorites) Color(0xFFFACC15) else Color(0xFF262936),
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable { onToggleFavoritesView(!showOnlyFavorites) }
                .padding(horizontal = 12.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "★ FAVS ($favoritesCount)",
                    color = if (showOnlyFavorites) Color.Black else Color(0xFFFACC15),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun FolderCueBrowserScreen(
    favoritesManager: FavoritesM3u8Manager,
    onEnqueueTracks: (List<VirtualCueTrack>, Int) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hifi_browser", Context.MODE_PRIVATE) }

    var rootTreeUri by remember {
        mutableStateOf(prefs.getString("root_tree_uri", null)?.let { Uri.parse(it) })
    }
    val breadcrumbs = remember { mutableStateListOf<FolderNode>() }
    var subfolders by remember { mutableStateOf<List<FolderNode>>(emptyList()) }
    var playableTracks by remember { mutableStateOf<List<VirtualCueTrack>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var showOnlyFavorites by remember { mutableStateOf(false) }
    val favoriteKeys by favoritesManager.favoriteKeys.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let { uri ->
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("root_tree_uri", uri.toString()).apply()
            rootTreeUri = uri
            val rootDocId = DocumentsContract.getTreeDocumentId(uri)
            breadcrumbs.clear()
            breadcrumbs.add(FolderNode("ROOT", rootDocId))
            favoritesManager.loadFromSafRoot(uri)
        }
    }

    LaunchedEffect(rootTreeUri) {
        val uri = rootTreeUri ?: return@LaunchedEffect
        if (breadcrumbs.isEmpty()) {
            breadcrumbs.add(FolderNode("ROOT", DocumentsContract.getTreeDocumentId(uri)))
        }
    }

    val currentFolder = breadcrumbs.lastOrNull()
    LaunchedEffect(rootTreeUri, currentFolder) {
        val treeUri = rootTreeUri ?: return@LaunchedEffect
        val folder = currentFolder ?: return@LaunchedEffect
        isScanning = true
        val scanResult = withContext(Dispatchers.IO) {
            scanSafDirectory(context, treeUri, folder.docId)
        }
        subfolders = scanResult.first
        playableTracks = scanResult.second
        isScanning = false
    }

    val displayedTracks = remember(playableTracks, searchQuery, showOnlyFavorites, favoriteKeys) {
        val baseList = if (showOnlyFavorites) favoritesManager.getAllFavoriteTracks() else playableTracks
        if (searchQuery.isBlank()) baseList else {
            val q = searchQuery.trim().lowercase()
            baseList.filter { t ->
                t.title.lowercase().contains(q) ||
                t.performer.lowercase().contains(q) ||
                t.albumTitle.lowercase().contains(q) ||
                t.format.codecLabel.lowercase().contains(q) ||
                (t.format.sampleRate / 1000).toString().contains(q)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0E))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DIRECT STORAGE & CUE ENGINE",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (displayedTracks.isNotEmpty()) "${displayedTracks.size} LOSSLESS TRACKS READY"
                    else "FLAC • DSF (DSD) • WAV • CUE",
                    color = Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            OutlinedButton(
                onClick = { folderPicker.launch(null) },
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("MOUNT DIR", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        SearchAndFavoritesFilterBar(
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            showOnlyFavorites = showOnlyFavorites,
            favoritesCount = favoriteKeys.size,
            onToggleFavoritesView = { showOnlyFavorites = it }
        )

        if (breadcrumbs.isNotEmpty() && !showOnlyFavorites) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF12141C))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                breadcrumbs.forEachIndexed { idx, crumb ->
                    val isLast = idx == breadcrumbs.lastIndex
                    Text(
                        text = crumb.name.uppercase(),
                        color = if (isLast) Color(0xFF38BDF8) else Color.Gray,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clickable {
                            while (breadcrumbs.size > idx + 1) breadcrumbs.removeAt(breadcrumbs.lastIndex)
                        }
                    )
                    if (!isLast) {
                        Text("  /  ", color = Color(0xFF475569), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        if (displayedTracks.isNotEmpty()) {
            Surface(
                color = Color(0xFF162420),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF4ADE80), RoundedCornerShape(8.dp))
                    .clickable { onEnqueueTracks(displayedTracks, 0) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "▶ ENQUEUE ALL (${displayedTracks.size} TRACKS • 0ms GAPLESS)",
                        color = Color(0xFF4ADE80),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = displayedTracks.first().format.codecLabel,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (isScanning) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("PROBING HEADERS & CUE SHEETS...", color = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!showOnlyFavorites && searchQuery.isBlank()) {
                    itemsIndexed(subfolders) { _, folder ->
                        Surface(
                            color = Color(0xFF12131A),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().clickable { breadcrumbs.add(folder) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📁  ${folder.name}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("➔", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }

                itemsIndexed(displayedTracks) { index, track ->
                    TrackRowItem(
                        track = track,
                        isFavorite = favoritesManager.isFavorite(track),
                        onToggleFavorite = { favoritesManager.toggleFavorite(track, rootTreeUri) },
                        onClick = { onEnqueueTracks(displayedTracks, index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRowItem(
    track: VirtualCueTrack,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    val fmt = track.format
    val rateKhz = if (fmt.isDsd) {
        "${"%.1f".format(fmt.sampleRate / 1_000_000.0)} MHz"
    } else {
        "${fmt.bitDepth}/${fmt.sampleRate / 1000.0}k"
    }
    val rgText = track.replayGain.effectiveTrackGainDb.let {
        if (it != 0f) "  •  RG ${if (it > 0) "+" else ""}${"%.1f".format(it)} dB" else ""
    }
    val totalSec = track.durationMs / 1000L
    val durationStr = "%d:%02d".format(totalSec / 60, totalSec % 60)

    Surface(
        color = Color(0xFF12141E),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF1E2230), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "%02d. %s".format(track.trackNumber, track.title),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${fmt.codecLabel} $rateKhz$rgText",
                    color = if (fmt.isDsd) Color(0xFF4ADE80) else Color(0xFF38BDF8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Text(
                text = durationStr,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Text(
                text = if (isFavorite) "★" else "☆",
                color = if (isFavorite) Color(0xFFFACC15) else Color(0xFF64748B),
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleFavorite)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun scanSafDirectory(
    context: Context,
    treeUri: Uri,
    parentDocId: String
): Pair<List<FolderNode>, List<VirtualCueTrack>> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    val folders = mutableListOf<FolderNode>()
    val cueUris = mutableListOf<Uri>()
    val audioFilesByName = linkedMapOf<String, Uri>()

    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            val docId = cursor.getString(0) ?: continue
            val name = cursor.getString(1) ?: continue
            val mime = cursor.getString(2) ?: ""
            val lower = name.lowercase()

            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                folders.add(FolderNode(name, docId))
            } else {
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                when {
                    lower.endsWith(".cue") -> cueUris.add(fileUri)
                    lower.endsWith(".flac") || lower.endsWith(".dsf") || lower.endsWith(".wav") -> {
                        audioFilesByName[lower] = fileUri
                    }
                }
            }
        }
    }

    folders.sortBy { it.name.lowercase() }
    val virtualTracks = mutableListOf<VirtualCueTrack>()
    val consumedMasterUris = mutableSetOf<Uri>()

    for (cueUri in cueUris) {
        val parsed = CueSheetParser.parseCueSheet(context, cueUri, audioFilesByName)
        parsed.forEach { consumedMasterUris.add(it.audioUri) }
        virtualTracks.addAll(parsed)
    }

    val standaloneEntries = audioFilesByName.entries
        .filter { it.value !in consumedMasterUris }
        .sortedBy { it.key }

    standaloneEntries.forEachIndexed { idx, (fileName, fileUri) ->
        val fmt = AudioFormatProbe.probeUri(context, fileUri)
        val rg = ReplayGainParser.parseFromUri(context, fileUri)
        val cleanTitle = fileName.substringBeforeLast(".").replace(Regex("""^\d+[\s._-]+"""), "")
        virtualTracks.add(
            VirtualCueTrack(
                trackNumber = virtualTracks.size + idx + 1,
                title = cleanTitle,
                performer = "",
                albumTitle = "",
                audioUri = fileUri,
                format = fmt,
                startMs = 0L,
                endMs = Long.MAX_VALUE,
                dsdStartByteOffset = fmt.dsdDataStartOffset,
                dsdEndByteOffset = fmt.dsdDataEndOffset,
                replayGain = rg
            )
        )
    }

    return folders to virtualTracks
}
