package com.minimal.hifi

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ArtworkAndLyricsDeck(
    activeTrack: VirtualCueTrack?,
    positionMs: Long,
    fallbackFolderArtUri: Uri? = null,
    externalLrcUri: Uri? = null,
    onSeekToMs: (Long) -> Unit,
    onSessionArtworkLoaded: (ByteArray?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var coverBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var lyrics by remember { mutableStateOf<List<SyncedLyricLine>>(emptyList()) }
    var showFullLyrics by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    LaunchedEffect(activeTrack?.audioUri, activeTrack?.startMs) {
        val track = activeTrack ?: return@LaunchedEffect
        val (bmp, sessionBytes) = CoverArtExtractor.loadArtworkForTrack(
            context = context,
            trackUri = track.audioUri,
            fallbackFolderImageUri = fallbackFolderArtUri
        )
        coverBitmap = bmp
        onSessionArtworkLoaded(sessionBytes)
        lyrics = LrcLyricsParser.loadLyricsForTrack(context, track, externalLrcUri)
    }

    val activeLineIndex = remember(lyrics, positionMs) {
        LrcLyricsParser.findActiveLineIndex(lyrics, positionMs)
    }

    LaunchedEffect(activeLineIndex, showFullLyrics) {
        if (showFullLyrics && activeLineIndex >= 0) {
            listState.animateScrollToItem((activeLineIndex - 1).coerceAtLeast(0))
        }
    }

    Surface(
        color = Color(0xFF12141C),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().border(1.dp, Color(0xFF1E2230), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (lyrics.isNotEmpty()) showFullLyrics = !showFullLyrics },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF181B26))
                        .border(1.dp, Color(0xFF262B3C), RoundedCornerShape(8.dp))
                ) {
                    val bmp = coverBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "Album Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = activeTrack?.format?.codecLabel ?: "HiFi",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeTrack?.albumTitle?.takeIf { it.isNotEmpty() } ?: "DIRECT BIT-PERFECT SOURCE",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val liveLyric = lyrics.getOrNull(activeLineIndex)?.text
                        ?: if (lyrics.isNotEmpty()) "♪ Instrumental / Intro" else "No synced .lrc lyrics found"
                    Text(
                        text = liveLyric,
                        color = if (activeLineIndex >= 0) Color(0xFF4ADE80) else Color.Gray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (lyrics.isNotEmpty()) {
                    Text(
                        text = if (showFullLyrics) "HIDE LRC ▲" else "LRC (${lyrics.size}) ▼",
                        color = if (showFullLyrics) Color.Black else Color(0xFF38BDF8),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (showFullLyrics) Color(0xFF38BDF8) else Color(0xFF181B26))
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            AnimatedVisibility(visible = showFullLyrics && lyrics.isNotEmpty()) {
                Column {
                    Divider(color = Color(0xFF1E2230), modifier = Modifier.padding(vertical = 8.dp))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().height(195.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(lyrics) { index, line ->
                            val isCurrent = index == activeLineIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isCurrent) Color(0xFF1A2636) else Color.Transparent)
                                    .clickable { onSeekToMs(line.timestampMs) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = line.text,
                                    color = if (isCurrent) Color.White else Color(0xFF64748B),
                                    fontSize = if (isCurrent) 13.sp else 12.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "%02d:%02d".format(
                                        (line.timestampMs / 1000L) / 60L,
                                        (line.timestampMs / 1000L) % 60L
                                    ),
                                    color = if (isCurrent) Color(0xFF38BDF8) else Color(0xFF475569),
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
