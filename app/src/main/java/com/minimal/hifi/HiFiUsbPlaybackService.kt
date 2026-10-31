package com.minimal.hifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class UsbConnectionStatus(
    val isConnected: Boolean = false,
    val dacProductName: String = "No USB DAC",
    val usbFileDescriptor: Int? = null
)

class HiFiUsbPlaybackService : Service() {
    inner class LocalBinder : Binder() { val service: HiFiUsbPlaybackService get() = this@HiFiUsbPlaybackService }
    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var dacManager: BitPerfectDacManager
    private var mediaSession: MediaSession? = null

    private val _dacStatus = MutableStateFlow(UsbConnectionStatus())
    val dacStatus: StateFlow<UsbConnectionStatus> = _dacStatus
    var onEmergencyDisconnect: (() -> Unit)? = null

    private val hotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> dacManager.findUsbAudioDac()?.let { handleDacAttached(it) }
                UsbManager.ACTION_USB_DEVICE_DETACHED, AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    UsbNativeBridge.stopUsbEngine()
                    onEmergencyDisconnect?.invoke()
                    releaseWakeLock()
                    _dacStatus.value = UsbConnectionStatus(false, "DAC Disconnected")
                    updateNotification("USB DAC Unplugged — Playback Paused")
                }
                BitPerfectDacManager.ACTION_USB_PERMISSION -> dacManager.findUsbAudioDac()?.let { openAndLockDac(it) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dacManager = BitPerfectDacManager(this)
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(BitPerfectDacManager.ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hotplugReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(hotplugReceiver, filter)
        }
        dacManager.findUsbAudioDac()?.let { handleDacAttached(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification("Blackground UAC2 Engine Ready")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1001, notif)
        }
        acquireWakeLock()
        return START_STICKY
    }

    fun attachUnifiedMediaSession(exoPlayer: Player, queueManager: GaplessQueueManager) {
        if (mediaSession != null) return
        val forwarding = object : ForwardingPlayer(exoPlayer) {
            override fun play() { queueManager.togglePlayPause() }
            override fun pause() { queueManager.togglePlayPause() }
            override fun seekTo(positionMs: Long) { queueManager.seekToMs(positionMs) }
            override fun seekToNext() { queueManager.skipToNext(_dacStatus.value.usbFileDescriptor) }
            override fun seekToPrevious() { queueManager.skipToPrevious(_dacStatus.value.usbFileDescriptor) }
            override fun setPlaybackSpeed(speed: Float) { queueManager.setPlaybackSpeed(speed) }
        }
        mediaSession = MediaSession.Builder(this, forwarding).setId("BlackgroundSession").build()
    }

    private fun handleDacAttached(device: UsbDevice) {
        val fd = dacManager.openRawUsbFileDescriptor(device)
        if (fd != null) openAndLockDac(device, fd) else dacManager.requestUsbPermission(device)
    }

    private fun openAndLockDac(device: UsbDevice, existingFd: Int? = null) {
        val fd = existingFd ?: dacManager.openRawUsbFileDescriptor(device) ?: return
        val name = device.productName ?: "UAC2 Hardware DAC"
        _dacStatus.value = UsbConnectionStatus(true, name.trim(), fd)
        acquireWakeLock()
        updateNotification("Locked: $name")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Blackground::UsbWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("hifi_usb_channel", "Bit-Perfect USB Playback", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "hifi_usb_channel") else Notification.Builder(this)
        return b.setContentTitle("Blackground • UAC2 Direct").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(pi).setOngoing(true).build()
    }

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1001, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        mediaSession?.release()
        unregisterReceiver(hotplugReceiver)
        UsbNativeBridge.stopUsbEngine()
        releaseWakeLock()
        super.onDestroy()
    }
}
