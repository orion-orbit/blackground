package com.minimal.hifi

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class ClockTelemetrySnapshot(
    val previousRateHz: Int = 0,
    val lockedRateHz: Int = 0,
    val isPllLocked: Boolean = false,
    val isDopMode: Boolean = false,
    val ringBufferFillPercent: Int = 0,
    val xrunCount: Int = 0
)

@Composable
fun ClockLockTelemetryHud(
    dacStatus: UsbConnectionStatus,
    modifier: Modifier = Modifier
) {
    var snapshot by remember { mutableStateOf(ClockTelemetrySnapshot()) }

    LaunchedEffect(dacStatus.isConnected) {
        while (isActive && dacStatus.isConnected) {
            val raw = UsbNativeBridge.getClockAndBufferTelemetry()
            if (raw.size == 6) {
                snapshot = ClockTelemetrySnapshot(raw[0], raw[1], raw[2] == 1, raw[3] == 1, raw[4], raw[5])
            }
            delay(250)
        }
    }

    Surface(
        color = Color(0xFF0E1017),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (snapshot.isPllLocked) Color(0xFF1E3A2F) else Color(0xFF262936),
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !dacStatus.isConnected -> Color(0xFF64748B)
                                    snapshot.isPllLocked -> Color(0xFF4ADE80)
                                    else -> Color(0xFFFACC15)
                                }
                            )
                    )
                    Text(
                        text = dacStatus.dacProductName.uppercase(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = when {
                        !dacStatus.isConnected -> "USB DISCONNECTED"
                        snapshot.isPllLocked -> "UAC2 PLL LOCKED"
                        else -> "PLL RE-LOCKING..."
                    },
                    color = if (snapshot.isPllLocked) Color(0xFF4ADE80) else Color(0xFFFACC15),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            val prevKhz = if (snapshot.previousRateHz > 0) "${snapshot.previousRateHz / 1000.0} kHz ➔ " else ""
            val curKhz = if (snapshot.lockedRateHz > 0) "${snapshot.lockedRateHz / 1000.0} kHz" else "--.- kHz"
            val streamTag = if (snapshot.isDopMode) " [DoP64 DSD]" else " [NATIVE PCM]"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CLOCK: $prevKhz$curKhz$streamTag",
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "XRUNS: ${snapshot.xrunCount}",
                    color = if (snapshot.xrunCount == 0) Color(0xFF94A3B8) else Color(0xFFF87171),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "SPSC FIFO", color = Color.Gray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                LinearProgressIndicator(
                    progress = snapshot.ringBufferFillPercent / 100f,
                    modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF38BDF8),
                    trackColor = Color(0xFF1E2230)
                )
                Text(text = "${snapshot.ringBufferFillPercent}%", color = Color.LightGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun GaplessAndLoudnessBar(
    currentGainDb: Float,
    trackPeak: Float,
    isBitPerfectOrDop: Boolean,
    nextTrackTitle: String?,
    selectedMode: LoudnessMode,
    onModeChange: (LoudnessMode) -> Unit
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
                        text = "REPLAYGAIN 2.0 / R128",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    val routingLabel = when {
                        selectedMode == LoudnessMode.OFF -> "BYPASSED (0.0 dB)"
                        isBitPerfectOrDop -> "UAC2 HW REGISTER (${if (currentGainDb >= 0) "+" else ""}${"%.1f".format(currentGainDb)} dB)"
                        else -> "64-BIT DSP PREAMP (${if (currentGainDb >= 0) "+" else ""}${"%.1f".format(currentGainDb)} dB | PK ${"%.2f".format(trackPeak)})"
                    }
                    Text(
                        text = routingLabel,
                        color = if (selectedMode == LoudnessMode.OFF) Color.Gray else Color(0xFF4ADE80),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LoudnessMode.entries.forEach { mode ->
                        val active = selectedMode == mode
                        Text(
                            text = mode.name.replace("_GAIN", ""),
                            color = if (active) Color.Black else Color.LightGray,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (active) Color(0xFF38BDF8) else Color(0xFF1E2230))
                                .clickable { onModeChange(mode) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Divider(color = Color(0xFF1E2230))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (nextTrackTitle != null) "GAPLESS ARMED ➔ $nextTrackTitle" else "GAPLESS PRE-FETCH: END OF QUEUE",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "0ms SPLICE",
                    color = Color(0xFF38BDF8),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
