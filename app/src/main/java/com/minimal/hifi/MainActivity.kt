package com.minimal.hifi

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class HiFiTab(val title: String, val badge: String) {
    SIGNAL_DECK("SIGNAL / DECK", "UAC2"),
    LIBRARY_CUE("LIBRARY / CUE", "SAF"),
    PARAMETRIC_EQ("64-BIT PEQ", "DSP")
}

class MainActivity : ComponentActivity() {

    private lateinit var dacManager: BitPerfectDacManager
    private lateinit var playerEngine: HiFiPlayerEngine
    private lateinit var volumeManager: HardwareVolumeManager
    private lateinit var persistenceManager: PlaybackPersistenceManager
    private lateinit var queueManager: GaplessQueueManager
    private lateinit var profileManager: DeviceProfileManager
    private lateinit var sleepTimerManager: SleepTimerManager
    private lateinit var favoritesManager: FavoritesM3u8Manager

    private var usbService: HiFiUsbPlaybackService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? HiFiUsbPlaybackService.LocalBinder ?: return
            val service = localBinder.service
            usbService = service
            isServiceBound = true

            service.attachUnifiedMediaSession(playerEngine.exoPlayer, queueManager)
            service.onEmergencyDisconnect = {
                if (queueManager.uiState.value.isPlaying) queueManager.togglePlayPause()
                profileManager.onOutputDeviceChanged(null)
            }

            lifecycleScope.launch {
                service.dacStatus.collectLatest { status ->
                    val activeUsbDevice = if (status.isConnected) dacManager.findUsbAudioDac() else null
                    profileManager.onOutputDeviceChanged(activeUsbDevice)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            usbService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dacManager = BitPerfectDacManager(this)
        playerEngine = HiFiPlayerEngine(this, dacManager)
        volumeManager = HardwareVolumeManager(lifecycleScope)
        persistenceManager = PlaybackPersistenceManager(this)
        queueManager = GaplessQueueManager(this, lifecycleScope, playerEngine, persistenceManager)
        profileManager = DeviceProfileManager(this, lifecycleScope, volumeManager)
        favoritesManager = FavoritesM3u8Manager(this, lifecycleScope)

        sleepTimerManager = SleepTimerManager(
            scope = lifecycleScope,
            queueManager = queueManager,
            volumeManager = volumeManager,
            onReleaseUsbAndWakeLock = {
                usbService?.updateNotification("Sleep Timer Completed • USB Clock Idle")
            }
        )

        val serviceIntent = Intent(this, HiFiUsbPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        persistenceManager.restoreState()?.let { restored ->
            if (restored.shuffleEnabled) queueManager.toggleShuffle()
            while (queueManager.uiState.value.repeatMode != restored.repeatMode) {
                queueManager.cycleRepeatMode()
            }
            queueManager.setPlaybackSpeed(restored.playbackSpeed)
            queueManager.loadQueue(
                tracks = restored.queue,
                startIndex = restored.currentIndex,
                startPositionMs = restored.positionMs,
                autoPlay = false,
                usbFd = dacManager.findUsbAudioDac()?.let { dacManager.openRawUsbFileDescriptor(it) }
            )
        }

        getSharedPreferences("hifi_browser", Context.MODE_PRIVATE)
            .getString("root_tree_uri", null)
            ?.let { Uri.parse(it) }
            ?.let { favoritesManager.loadFromSafRoot(it) }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NotificationPermissionEffect()

                MainHiFiScaffold(
                    playerEngine = playerEngine,
                    queueManager = queueManager,
                    volumeManager = volumeManager,
                    profileManager = profileManager,
                    sleepTimerManager = sleepTimerManager,
                    favoritesManager = favoritesManager,
                    usbServiceProvider = { usbService }
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    volumeManager.onVolumeKeyStep(+1)
                    profileManager.updateActiveProfile { it.copy(hardwareVolumeDb = volumeManager.state.value.currentDb) }
                }
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    volumeManager.onVolumeKeyStep(-1)
                    profileManager.updateActiveProfile { it.copy(hardwareVolumeDb = volumeManager.state.value.currentDb) }
                }
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onStop() {
        super.onStop()
        queueManager.saveSnapshot()
    }

    override fun onDestroy() {
        queueManager.saveSnapshot()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        playerEngine.release()
        super.onDestroy()
    }

    @Composable
    private fun NotificationPermissionEffect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {}
            LaunchedEffect(Unit) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}

@Composable
fun MainHiFiScaffold(
    playerEngine: HiFiPlayerEngine,
    queueManager: GaplessQueueManager,
    volumeManager: HardwareVolumeManager,
    profileManager: DeviceProfileManager,
    sleepTimerManager: SleepTimerManager,
    favoritesManager: FavoritesM3u8Manager,
    usbServiceProvider: () -> HiFiUsbPlaybackService?
) {
    var selectedTab by remember { mutableStateOf(HiFiTab.SIGNAL_DECK) }

    val transportState by queueManager.uiState.collectAsState()
    val volumeState by volumeManager.state.collectAsState()
    val activeProfile by profileManager.activeProfile.collectAsState()
    val sleepState by sleepTimerManager.state.collectAsState()

    val usbService = usbServiceProvider()
    val dacStatusState = usbService?.dacStatus?.collectAsState()
    val dacStatus = dacStatusState?.value ?: UsbConnectionStatus()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0E))) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    HiFiTab.SIGNAL_DECK -> {
                        SignalAndTransportScreen(
                            dacStatus = dacStatus,
                            activeProfile = activeProfile,
                            sleepState = sleepState,
                            transportState = transportState,
                            onSelectSleepPreset = { sleepTimerManager.setTimer(it) },
                            onLoudnessModeChange = { mode ->
                                profileManager.updateActiveProfile { it.copy(loudnessMode = mode) }
                            },
                            onSeekToMs = { queueManager.seekToMs(it) },
                            onPlayPause = { queueManager.togglePlayPause() },
                            onPrevious = { queueManager.skipToPrevious(dacStatus.usbFileDescriptor) },
                            onNext = { queueManager.skipToNext(dacStatus.usbFileDescriptor) },
                            onToggleShuffle = { queueManager.toggleShuffle() },
                            onCycleRepeat = { queueManager.cycleRepeatMode() },
                            onSpeedChange = { queueManager.setPlaybackSpeed(it) },
                            onSessionArtworkLoaded = { jpegBytes ->
                                if (jpegBytes != null) {
                                    val currentItem = playerEngine.exoPlayer.currentMediaItem
                                    if (currentItem != null) {
                                        val updatedMeta = currentItem.mediaMetadata.buildUpon()
                                            .setArtworkData(jpegBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                            .build()
                                        playerEngine.exoPlayer.replaceMediaItem(
                                            playerEngine.exoPlayer.currentMediaItemIndex,
                                            currentItem.buildUpon().setMediaMetadata(updatedMeta).build()
                                        )
                                    }
                                }
                            }
                        )
                    }

                    HiFiTab.LIBRARY_CUE -> {
                        FolderCueBrowserScreen(
                            favoritesManager = favoritesManager,
                            onEnqueueTracks = { tracks, startIdx ->
                                queueManager.loadQueue(
                                    tracks = tracks,
                                    startIndex = startIdx,
                                    startPositionMs = 0L,
                                    autoPlay = true,
                                    usbFd = dacStatus.usbFileDescriptor
                                )
                                selectedTab = HiFiTab.SIGNAL_DECK
                            }
                        )
                    }

                    HiFiTab.PARAMETRIC_EQ -> {
                        val effectiveSampleRate = transportState.activeTrack?.format?.sampleRate ?: 96000
                        ParametricEqScreen(
                            sampleRate = effectiveSampleRate,
                            initialProfile = activeProfile,
                            onProfileUpdated = { updatedProfile ->
                                profileManager.updateActiveProfile { updatedProfile }
                            }
                        )
                    }
                }
            }

            MinimalBottomNavBar(
                selectedTab = selectedTab,
                isEqActive = activeProfile.eqEnabled,
                isDacLocked = dacStatus.isConnected,
                onSelectTab = { selectedTab = it }
            )
        }

        HardwareVolumeHudOverlay(
            state = volumeState,
            onSliderChange = { newDb ->
                volumeManager.setExactDb(newDb)
                profileManager.updateActiveProfile { it.copy(hardwareVolumeDb = newDb) }
            },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp, start = 16.dp, end = 16.dp)
        )
    }
}

@Composable
private fun SignalAndTransportScreen(
    dacStatus: UsbConnectionStatus,
    activeProfile: DeviceAudioProfile,
    sleepState: SleepTimerState,
    transportState: TransportUiState,
    onSelectSleepPreset: (SleepTimerPreset) -> Unit,
    onLoudnessModeChange: (LoudnessMode) -> Unit,
    onSeekToMs: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSessionArtworkLoaded: (ByteArray?) -> Unit
) {
    val activeTrack = transportState.activeTrack
    val effectiveRgDb = activeTrack?.replayGain?.resolveGainDb(activeProfile.loudnessMode) ?: 0.0f
    val isBitPerfectOrDop = !activeProfile.eqEnabled || (activeTrack?.format?.isDsd == true && !activeProfile.dsdEngineMode.isFallback)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ClockLockTelemetryHud(dacStatus = dacStatus)
        ProfileAndSleepTimerBar(
            activeProfile = activeProfile,
            sleepState = sleepState,
            onSelectSleepPreset = onSelectSleepPreset
        )
        ArtworkAndLyricsDeck(
            activeTrack = activeTrack,
            positionMs = transportState.positionMs,
            onSeekToMs = onSeekToMs,
            onSessionArtworkLoaded = onSessionArtworkLoaded
        )
        DrAndDsdControlCard()
        GaplessAndLoudnessBar(
            currentGainDb = effectiveRgDb,
            trackPeak = activeTrack?.replayGain?.trackPeak ?: 1.0f,
            isBitPerfectOrDop = isBitPerfectOrDop,
            nextTrackTitle = transportState.nextTrackTitle,
            selectedMode = activeProfile.loudnessMode,
            onModeChange = onLoudnessModeChange
        )
        TransportAndSeekbarDeck(
            state = transportState,
            onSeekToMs = onSeekToMs,
            onPlayPause = onPlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
            onSpeedChange = onSpeedChange
        )
    }
}

@Composable
private fun MinimalBottomNavBar(
    selectedTab: HiFiTab,
    isEqActive: Boolean,
    isDacLocked: Boolean,
    onSelectTab: (HiFiTab) -> Unit
) {
    Surface(
        color = Color(0xFF0E1017),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF1E2230), androidx.compose.ui.graphics.RectangleShape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HiFiTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                val badgeColor = when (tab) {
                    HiFiTab.SIGNAL_DECK -> if (isDacLocked) Color(0xFF4ADE80) else Color(0xFF64748B)
                    HiFiTab.LIBRARY_CUE -> Color(0xFF38BDF8)
                    HiFiTab.PARAMETRIC_EQ -> if (isEqActive) Color(0xFF38BDF8) else Color(0xFF64748B)
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF1A2030) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E2230),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 8.dp, horizontal = 6.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = tab.badge,
                            color = badgeColor,
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = tab.title,
                            color = if (isSelected) Color.White else Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HardwareVolumeHudOverlay(
    state: HardwareVolumeState,
    onSliderChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.hudVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = Color(0xF212141E),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "UAC2 HARDWARE ATTENUATOR",
                            color = Color(0xFF38BDF8),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "32-BIT DAC REGISTER  |  STEP: ${"%.2f".format(state.stepDb)} dB",
                            color = Color.Gray,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "${"%.1f".format(state.currentDb)} dB",
                        color = if (state.currentDb > -3.0f) Color(0xFFF87171) else Color.White,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Slider(
                    value = state.currentDb.coerceIn(state.minDb, state.maxDb),
                    onValueChange = onSliderChange,
                    valueRange = state.minDb..state.maxDb,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF38BDF8),
                        inactiveTrackColor = Color(0xFF1E293B)
                    )
                )
            }
        }
    }
}
