package com.minimal.hifi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

data class EqBandState(
    val type: Int = 0,
    val freq: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 0.71f,
    val enabled: Boolean = true
)

data class ExpertPreset(
    val name: String,
    val tag: String,
    val explainer: String,
    val recommendedFor: String,
    val sampleTracks: List<String>,
    val bands: List<EqBandState>
)

val GOD_TIER_PRESETS = listOf(
    ExpertPreset(
        name = "Harman 2019v2 Reference",
        tag = "IEM / TARGET",
        explainer = "Sub-bass visceral shelf below 105 Hz with a 220 Hz lower-mid tuck for clarity and a 7.5 kHz coupler-resonance notch.",
        recommendedFor = "Neutral/clinical IEMs and closed-back headphones needing punch without mud.",
        sampleTracks = listOf(
            "Massive Attack – Angel (0:00–0:45 sub-bass pressure)",
            "Daft Punk – Giorgio by Moroder (1:55 hi-hat transient cleanliness)"
        ),
        bands = listOf(
            EqBandState(1, 105f, 4.5f, 0.71f),
            EqBandState(0, 220f, -1.2f, 1.20f),
            EqBandState(0, 3100f, 2.2f, 1.40f),
            EqBandState(0, 7500f, -2.5f, 3.50f),
            EqBandState(2, 11500f, 1.5f, 0.71f)
        )
    ),
    ExpertPreset(
        name = "Diffuse Field Mastering",
        tag = "STUDIO / STAGE",
        explainer = "Eliminates 145 Hz mid-bass bloom, aligns 2.85 kHz ear-gain to a calibrated room monitor tilt, and adds 12.5 kHz air.",
        recommendedFor = "Bloated V-shaped headphones, critical mix evaluation, and maximizing 3D soundstage.",
        sampleTracks = listOf(
            "Steely Dan – Aja (4:40 drum solo imaging & cymbal decay)",
            "Yosi Horikawa – Bubbles (Pinpoint transient localization)"
        ),
        bands = listOf(
            EqBandState(1, 60f, 1.5f, 0.71f),
            EqBandState(0, 145f, -2.4f, 1.10f),
            EqBandState(0, 1200f, 0.8f, 1.80f),
            EqBandState(0, 2850f, 1.8f, 1.50f),
            EqBandState(2, 12500f, 2.0f, 0.71f)
        )
    ),
    ExpertPreset(
        name = "ISO-226 Late-Night Contour",
        tag = "LOW VOLUME",
        explainer = "Fletcher-Munson equal-loudness compensation. Restores sub-bass weight and top-octave shimmer lost below 65 dB SPL.",
        recommendedFor = "Quiet late-night sessions where music normally sounds thin and midrange-heavy.",
        sampleTracks = listOf(
            "Billie Eilish – Everything I Wanted (Sub-synth weight at whisper volume)",
            "Miles Davis – So What (Upright bass & ride cymbal articulation)"
        ),
        bands = listOf(
            EqBandState(1, 90f, 5.2f, 0.68f),
            EqBandState(0, 280f, 1.0f, 1.00f),
            EqBandState(0, 3200f, -1.8f, 1.60f),
            EqBandState(0, 6000f, 1.2f, 2.00f),
            EqBandState(2, 10000f, 3.5f, 0.71f)
        )
    ),
    ExpertPreset(
        name = "Planar Slam & De-Glare",
        tag = "PLANAR MAGNETIC",
        explainer = "Injects 65 Hz dynamic punch and 320 Hz body while surgically notching the 6.1 kHz metallic diaphragm resonance.",
        recommendedFor = "Planar magnetic headphones & IEMs (HiFiMAN, Audeze, S12) that feel lean or bright.",
        sampleTracks = listOf(
            "Tool – Invincible (2:15 kick drum chest impact)",
            "Fleetwood Mac – Dreams (Organic vocal timbre without metallic edge)"
        ),
        bands = listOf(
            EqBandState(0, 65f, 3.0f, 1.35f),
            EqBandState(0, 320f, 1.4f, 0.90f),
            EqBandState(0, 1850f, 1.6f, 1.40f),
            EqBandState(0, 6100f, -3.4f, 3.20f),
            EqBandState(2, 14000f, 1.8f, 0.71f)
        )
    ),
    ExpertPreset(
        name = "Triode Warmth & Ribbon Air",
        tag = "SWEETENER",
        explainer = "Pultec-style 45 Hz sub-tidy + 160 Hz tube chest warmth, relaxed 4.2 kHz digital bite, and 15 kHz ribbon tweeter air.",
        recommendedFor = "Fatiguing, heavily compressed modern masters or clinical ESS Sabre DACs.",
        sampleTracks = listOf(
            "Norah Jones – Don't Know Why (Velvety vocal body & breathy air)",
            "Radiohead – Weird Fishes/Arpeggi (Liquid, fatigue-free guitar layering)"
        ),
        bands = listOf(
            EqBandState(1, 45f, -1.5f, 0.71f),
            EqBandState(0, 160f, 2.4f, 0.85f),
            EqBandState(0, 1100f, 1.2f, 1.20f),
            EqBandState(0, 4200f, -2.2f, 1.80f),
            EqBandState(2, 15000f, 3.5f, 0.65f)
        )
    )
)

@Composable
fun ParametricEqScreen(
    sampleRate: Int = 96000,
    initialProfile: DeviceAudioProfile = DeviceAudioProfile(),
    onProfileUpdated: (DeviceAudioProfile) -> Unit = {}
) {
    var eqEnabled by remember(initialProfile.deviceKey) { mutableStateOf(initialProfile.eqEnabled) }
    var selectedPresetIdx by remember(initialProfile.deviceKey) { mutableIntStateOf(initialProfile.selectedPresetIndex) }
    var selectedBandIdx by remember { mutableIntStateOf(0) }
    var showPresetGuide by remember { mutableStateOf(true) }

    val bands = remember(initialProfile.deviceKey) {
        mutableStateListOf(*initialProfile.bands.toTypedArray())
    }

    val autoPreampDb = remember(bands.toList()) {
        val maxBoost = bands.filter { it.enabled }.maxOfOrNull { it.gainDb } ?: 0f
        if (maxBoost > 0f) -(maxBoost + 0.3f) else 0f
    }

    LaunchedEffect(eqEnabled, selectedPresetIdx, autoPreampDb, bands.toList()) {
        UsbNativeBridge.setEqEnabled(eqEnabled)
        UsbNativeBridge.setEqPreamp(autoPreampDb.toDouble())
        bands.forEachIndexed { idx, b ->
            UsbNativeBridge.updateEqBand(
                idx, b.type, b.freq.toDouble(), b.gainDb.toDouble(), b.q.toDouble(), b.enabled
            )
        }
        onProfileUpdated(
            initialProfile.copy(
                eqEnabled = eqEnabled,
                selectedPresetIndex = selectedPresetIdx,
                bands = bands.toList()
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0E))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "64-BIT PARAMETRIC DSP",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (eqEnabled) "AUTO-PREAMP: ${"%.1f".format(autoPreampDb)} dB  |  TPDF DITHER"
                    else "HARDWARE BIT-PERFECT PASSTHROUGH",
                    color = if (eqEnabled) Color(0xFF38BDF8) else Color(0xFF4ADE80),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Switch(
                checked = eqEnabled,
                onCheckedChange = { eqEnabled = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF38BDF8))
            )
        }

        FrequencyResponseGraph(
            bands = bands,
            selectedBandIdx = selectedBandIdx,
            eqEnabled = eqEnabled,
            sampleRate = sampleRate.toDouble()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GOD_TIER_PRESETS.forEachIndexed { index, preset ->
                val isSelected = selectedPresetIdx == index
                Surface(
                    color = if (isSelected) Color(0xFF1E293B) else Color(0xFF121318),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF262730),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            selectedPresetIdx = index
                            preset.bands.forEachIndexed { bIdx, b -> bands[bIdx] = b }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column {
                        Text(
                            text = preset.tag,
                            color = Color(0xFF38BDF8),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = preset.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        val currentPreset = GOD_TIER_PRESETS[selectedPresetIdx.coerceIn(0, GOD_TIER_PRESETS.lastIndex)]
        Surface(
            color = Color(0xFF12141C),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPresetGuide = !showPresetGuide }
                .padding(2.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ACOUSTIC PROFILE: ${currentPreset.name.uppercase()}",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (showPresetGuide) "HIDE ▲" else "INFO ▼",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                AnimatedVisibility(visible = showPresetGuide) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = currentPreset.explainer, color = Color(0xFFE2E8F0), fontSize = 12.sp)
                        Text(text = "Best For: ${currentPreset.recommendedFor}", color = Color(0xFF38BDF8), fontSize = 11.sp)
                        Divider(color = Color(0xFF1E293B), modifier = Modifier.padding(vertical = 2.dp))
                        Text(
                            text = "REFERENCE TEST TRACKS:",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        currentPreset.sampleTracks.forEach { track ->
                            Text(text = "• $track", color = Color(0xFFCBD5E1), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            bands.forEachIndexed { idx, band ->
                val isSelected = selectedBandIdx == idx
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF161822))
                        .clickable { selectedBandIdx = idx }
                        .padding(vertical = 8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "B${idx + 1}",
                            color = if (isSelected) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${band.freq.toInt()}Hz",
                            color = if (isSelected) Color(0xFF0F172A) else Color.Gray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        val activeBand = bands[selectedBandIdx]
        Surface(
            color = Color(0xFF12131A),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val types = listOf("PEAK", "LOW SHELF", "HIGH SHELF")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        types.forEachIndexed { tIdx, label ->
                            val active = activeBand.type == tIdx
                            Text(
                                text = label,
                                color = if (active) Color.Black else Color.Gray,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (active) Color(0xFF4ADE80) else Color(0xFF1E202B))
                                    .clickable { bands[selectedBandIdx] = activeBand.copy(type = tIdx) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                PrecisionParameterSlider(
                    label = "FREQUENCY",
                    valueText = "${activeBand.freq.toInt()} Hz",
                    value = ln(activeBand.freq.coerceIn(20f, 20000f)),
                    valueRange = ln(20f)..ln(20000f),
                    onValueChange = { logVal ->
                        bands[selectedBandIdx] = activeBand.copy(freq = exp(logVal).roundToInt().toFloat())
                    },
                    onStep = { delta ->
                        val next = (activeBand.freq + delta * 10f).coerceIn(20f, 20000f)
                        bands[selectedBandIdx] = activeBand.copy(freq = next)
                    }
                )

                PrecisionParameterSlider(
                    label = "GAIN",
                    valueText = "${if (activeBand.gainDb >= 0) "+" else ""}${"%.1f".format(activeBand.gainDb)} dB",
                    value = activeBand.gainDb,
                    valueRange = -12f..12f,
                    onValueChange = { g ->
                        bands[selectedBandIdx] = activeBand.copy(gainDb = (g * 10f).roundToInt() / 10f)
                    },
                    onStep = { delta ->
                        val next = ((activeBand.gainDb + delta * 0.1f) * 10f).roundToInt().toFloat() / 10f
                        bands[selectedBandIdx] = activeBand.copy(gainDb = next.coerceIn(-12f, 12f))
                    }
                )

                PrecisionParameterSlider(
                    label = "Q-FACTOR (BANDWIDTH)",
                    valueText = "%.2f".format(activeBand.q),
                    value = activeBand.q,
                    valueRange = 0.3f..6.0f,
                    onValueChange = { qVal ->
                        bands[selectedBandIdx] = activeBand.copy(q = (qVal * 100f).roundToInt() / 100f)
                    },
                    onStep = { delta ->
                        val next = ((activeBand.q + delta * 0.05f) * 100f).roundToInt().toFloat() / 100f
                        bands[selectedBandIdx] = activeBand.copy(q = next.coerceIn(0.3f, 6.0f))
                    }
                )
            }
        }
    }
}

@Composable
fun PrecisionParameterSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onStep: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(text = valueText, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E2230))
                    .clickable { onStep(-1) }
            ) {
                Text("-", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color(0xFF38BDF8),
                    inactiveTrackColor = Color(0xFF222634)
                )
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E2230))
                    .clickable { onStep(1) }
            ) {
                Text("+", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun FrequencyResponseGraph(
    bands: List<EqBandState>,
    selectedBandIdx: Int,
    eqEnabled: Boolean,
    sampleRate: Double
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(175.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E1017))
            .border(1.dp, Color(0xFF1E2230), RoundedCornerShape(12.dp))
    ) {
        val w = size.width
        val h = size.height
        val zeroDbY = h / 2f
        val maxDb = 12f

        listOf(-6f, 0f, 6f).forEach { db ->
            val y = zeroDbY - (db / maxDb) * (h * 0.42f)
            drawLine(
                color = if (db == 0f) Color(0xFF334155) else Color(0xFF1E293B),
                start = Offset(0f, y),
                end = Offset(w, y),
                strokeWidth = if (db == 0f) 2f else 1f
            )
        }

        val minLogF = ln(20.0)
        val maxLogF = ln(20000.0)
        listOf(100.0, 1000.0, 10000.0).forEach { freq ->
            val x = ((ln(freq) - minLogF) / (maxLogF - minLogF)).toFloat() * w
            drawLine(
                color = Color(0xFF1E293B),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 1f
            )
        }

        val curvePath = Path()
        val fillPath = Path()
        val steps = 160

        for (i in 0..steps) {
            val normX = i.toDouble() / steps
            val freq = exp(minLogF + normX * (maxLogF - minLogF))
            val totalDb = if (eqEnabled) {
                bands.sumOf { b -> evaluateBiquadMagnitudeDb(b, freq, sampleRate) }.toFloat()
            } else 0f

            val x = (normX * w).toFloat()
            val y = (zeroDbY - (totalDb.coerceIn(-14f, 14f) / maxDb) * (h * 0.42f))

            if (i == 0) {
                curvePath.moveTo(x, y)
                fillPath.moveTo(x, zeroDbY)
                fillPath.lineTo(x, y)
            } else {
                curvePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (i == steps) {
                fillPath.lineTo(x, zeroDbY)
                fillPath.close()
            }
        }

        val accentColor = if (eqEnabled) Color(0xFF38BDF8) else Color(0xFF64748B)
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(colors = listOf(accentColor.copy(alpha = 0.28f), Color.Transparent))
        )
        drawPath(path = curvePath, color = accentColor, style = Stroke(width = 3.dp.toPx()))

        bands.forEachIndexed { idx, band ->
            val bx = (((ln(band.freq.toDouble()) - minLogF) / (maxLogF - minLogF)) * w).toFloat()
            val by = zeroDbY - (band.gainDb.coerceIn(-12f, 12f) / maxDb) * (h * 0.42f)
            val isSelected = idx == selectedBandIdx
            drawCircle(
                color = if (isSelected) Color(0xFF4ADE80) else accentColor,
                radius = if (isSelected) 7.dp.toPx() else 4.5.dp.toPx(),
                center = Offset(bx, by)
            )
        }
    }
}

private fun evaluateBiquadMagnitudeDb(b: EqBandState, f: Double, sampleRate: Double): Double {
    if (!b.enabled || abs(b.gainDb) < 0.01f) return 0.0
    val A = 10.0.pow(b.gainDb / 40.0)
    val w0 = 2.0 * PI * b.freq / sampleRate
    val cosW0 = cos(w0)
    val sinW0 = sin(w0)
    val alpha = sinW0 / (2.0 * b.q.coerceAtLeast(0.1f))

    var b0 = 1.0; var b1 = 0.0; var b2 = 0.0
    var a0 = 1.0; var a1 = 0.0; var a2 = 0.0

    when (b.type) {
        0 -> {
            b0 = 1.0 + alpha * A; b1 = -2.0 * cosW0; b2 = 1.0 - alpha * A
            a0 = 1.0 + alpha / A; a1 = -2.0 * cosW0; a2 = 1.0 - alpha / A
        }
        1 -> {
            val sq = 2.0 * sqrt(A) * alpha
            b0 = A * ((A + 1) - (A - 1) * cosW0 + sq)
            b1 = 2.0 * A * ((A - 1) - (A + 1) * cosW0)
            b2 = A * ((A + 1) - (A - 1) * cosW0 - sq)
            a0 = (A + 1) + (A - 1) * cosW0 + sq
            a1 = -2.0 * ((A - 1) + (A + 1) * cosW0)
            a2 = (A + 1) + (A - 1) * cosW0 - sq
        }
        2 -> {
            val sq = 2.0 * sqrt(A) * alpha
            b0 = A * ((A + 1) + (A - 1) * cosW0 + sq)
            b1 = -2.0 * A * ((A - 1) + (A + 1) * cosW0)
            b2 = A * ((A + 1) + (A - 1) * cosW0 - sq)
            a0 = (A + 1) - (A - 1) * cosW0 + sq
            a1 = 2.0 * ((A - 1) - (A + 1) * cosW0)
            a2 = (A + 1) - (A - 1) * cosW0 - sq
        }
    }
    b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0

    val w = 2.0 * PI * f / sampleRate
    val cosW = cos(w); val cos2W = cos(2.0 * w)
    val sinW = sin(w); val sin2W = sin(2.0 * w)
    val num = (b0 + b1 * cosW + b2 * cos2W).pow(2) + (b1 * sinW + b2 * sin2W).pow(2)
    val den = (1.0 + a1 * cosW + a2 * cos2W).pow(2) + (a1 * sinW + a2 * sin2W).pow(2)
    return 10.0 * log10((num / den).coerceAtLeast(1e-12))
}
