package com.minimal.hifi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TransportAndSeekbarDeck(
    state: TransportUiState,
    onSeekToMs: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var isDraggingSeek by remember { mutableStateOf(false) }
    var dragPreviewMs by remember { mutableLongStateOf(0L) }

    val displayedPositionMs = if (isDraggingSeek) dragPreviewMs else state.positionMs
    val totalMs = state.durationMs.coerceAtLeast(1L)
    val remainingMs = (totalMs - displayedPositionMs).coerceAtLeast(0L)

    Surface(
        color = Color(0xFF12141C),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().border(1.dp, Color(0xFF1E2230), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.activeTrack?.title ?: "NO TRACK LOADED",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.activeTrack?.performer?.takeIf { it.isNotEmpty() }
                            ?: state.activeTrack?.format?.codecLabel ?: "READY",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
                if (state.queueSize > 0) {
                    Text(
                        text = "${state.queueIndex + 1} / ${state.queueSize}",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column {
                Slider(
                    value = displayedPositionMs.toFloat(),
                    onValueChange = { newPos ->
                        isDraggingSeek = true
                        dragPreviewMs = newPos.toLong()
                    },
                    onValueChangeFinished = {
                        isDraggingSeek = false
                        onSeekToMs(dragPreviewMs)
                    },
                    valueRange = 0f..totalMs.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF38BDF8),
                        inactiveTrackColor = Color(0xFF1E2230)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = formatMsToMmSs(displayedPositionMs),
                        color = if (isDraggingSeek) Color(0xFF4ADE80) else Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "-${formatMsToMmSs(remainingMs)}",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SHUF",
                    color = if (state.shuffleEnabled) Color.Black else Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (state.shuffleEnabled) Color(0xFF38BDF8) else Color(0xFF181B26))
                        .clickable(onClick = onToggleShuffle)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )

                OutlinedButton(
                    onClick = onPrevious,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("⏮", color = Color.White, fontSize = 14.sp)
                }

                Button(
                    onClick = onPlayPause,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isPlaying) Color(0xFF38BDF8) else Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = if (state.isPlaying) "PAUSE" else "PLAY",
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onNext,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("⏭", color = Color.White, fontSize = 14.sp)
                }

                val repeatLabel = when (state.repeatMode) {
                    RepeatMode.OFF -> "RPT:OFF"
                    RepeatMode.REPEAT_ALL -> "RPT:ALL"
                    RepeatMode.REPEAT_ONE -> "RPT:1"
                }
                val repeatActive = state.repeatMode != RepeatMode.OFF
                Text(
                    text = repeatLabel,
                    color = if (repeatActive) Color.Black else Color.Gray,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (repeatActive) Color(0xFF4ADE80) else Color(0xFF181B26))
                        .clickable(onClick = onCycleRepeat)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            Divider(color = Color(0xFF1E2230))

            val isBitPerfectSpeed = state.playbackSpeed == 1.0f
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TEMPO: ${"%.2f".format(state.playbackSpeed)}x  •  PITCH LOCKED (Δ 0.0 st)",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isBitPerfectSpeed) "1.0x BIT-PERFECT BYPASS" else "WSOLA STEREO LOCK",
                        color = if (isBitPerfectSpeed) Color(0xFF4ADE80) else Color(0xFF38BDF8),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E2230))
                            .clickable { onSpeedChange(state.playbackSpeed - 0.05f) }
                    ) {
                        Text("-", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }

                    val speedPresets = listOf(0.75f, 0.90f, 1.00f, 1.25f, 1.50f)
                    speedPresets.forEach { preset ->
                        val active = kotlin.math.abs(state.playbackSpeed - preset) < 0.01f
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (active) Color(0xFF38BDF8) else Color(0xFF181B26))
                                .clickable { onSpeedChange(preset) }
                                .padding(vertical = 6.dp)
                        ) {
                            Text(
                                text = "${"%.2f".format(preset)}x",
                                color = if (active) Color.Black else Color.LightGray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E2230))
                            .clickable { onSpeedChange(state.playbackSpeed + 0.05f) }
                    ) {
                        Text("+", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun formatMsToMmSs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSec / 60L, totalSec % 60L)
}
