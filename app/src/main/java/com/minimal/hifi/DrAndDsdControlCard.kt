package com.minimal.hifi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

data class DrTelemetryState(
    val peakDbfs: Float = -96.0f,
    val rmsDbfs: Float = -96.0f,
    val drScore: Int = 0,
    val clipped: Boolean = false
)

enum class DsdEngineMode(val label: String, val isFallback: Boolean, val cutoffIdx: Int) {
    NATIVE_DOP("DoP64 DIRECT", false, 0),
    PCM_30KHZ("FIR 88.2k • 30kHz", true, 0),
    PCM_50KHZ("FIR 88.2k • 50kHz", true, 1)
}

@Composable
fun DrAndDsdControlCard(modifier: Modifier = Modifier) {
    var drState by remember { mutableStateOf(DrTelemetryState()) }
    var dsdMode by remember { mutableStateOf(DsdEngineMode.NATIVE_DOP) }

    LaunchedEffect(Unit) {
        while (isActive) {
            val raw = UsbNativeBridge.getDrTelemetry()
            if (raw.size == 4) {
                drState = DrTelemetryState(raw[0], raw[1], raw[2].roundToInt(), raw[3] > 0.5f)
            }
            delay(100)
        }
    }

    val drBadgeColor = when {
        drState.drScore >= 12 -> Color(0xFF4ADE80)
        drState.drScore >= 8  -> Color(0xFFFACC15)
        drState.drScore > 0   -> Color(0xFFF87171)
        else                  -> Color(0xFF64748B)
    }

    Surface(
        color = Color(0xFF12141C),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().border(1.dp, Color(0xFF1E2230), RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(drBadgeColor.copy(alpha = 0.18f))
                            .border(1.dp, drBadgeColor, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (drState.drScore > 0) "DR${drState.drScore}" else "DR--",
                            color = drBadgeColor,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text(
                            text = "REAL-TIME CREST FACTOR",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "PASSIVE BIT-PERFECT TAP",
                            color = Color.Gray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "PK: ${"%.1f".format(drState.peakDbfs)} dBFS",
                        color = if (drState.clipped) Color(0xFFF87171) else Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "RMS: ${"%.1f".format(drState.rmsDbfs)} dBFS",
                        color = Color(0xFF38BDF8),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0A0A0E))
            ) {
                val w = size.width
                val h = size.height
                val minDb = -60f
                val rmsNorm = ((drState.rmsDbfs - minDb) / -minDb).coerceIn(0f, 1f)
                val peakNorm = ((drState.peakDbfs - minDb) / -minDb).coerceIn(0f, 1f)

                drawRoundRect(
                    color = Color(0xFF38BDF8).copy(alpha = 0.65f),
                    size = Size(w * rmsNorm, h),
                    cornerRadius = CornerRadius(4f, 4f)
                )
                if (peakNorm > rmsNorm) {
                    drawRect(
                        color = drBadgeColor.copy(alpha = 0.85f),
                        topLeft = Offset(w * rmsNorm, 0f),
                        size = Size(w * (peakNorm - rmsNorm), h)
                    )
                }
            }

            Divider(color = Color(0xFF1E2230))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "DSD64 TRANSPORT / 128-TAP LUT FIR DECIMATOR",
                        color = Color(0xFF94A3B8),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = if (dsdMode.isFallback) "EQ COMPATIBLE" else "RAW 1-BIT",
                        color = if (dsdMode.isFallback) Color(0xFF38BDF8) else Color(0xFF4ADE80),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DsdEngineMode.entries.forEach { mode ->
                        val selected = dsdMode == mode
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) Color(0xFF38BDF8) else Color(0xFF181B26))
                                .clickable {
                                    dsdMode = mode
                                    UsbNativeBridge.setDsdPcmFallbackMode(mode.isFallback, mode.cutoffIdx)
                                }
                                .padding(vertical = 7.dp)
                        ) {
                            Text(
                                text = mode.label,
                                color = if (selected) Color.Black else Color.LightGray,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
