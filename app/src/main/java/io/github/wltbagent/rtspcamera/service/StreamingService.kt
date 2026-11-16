package io.github.wltbagent.rtspcamera.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import io.github.wltbagent.rtspcamera.R
import io.github.wltbagent.rtspcamera.Settings
import io.github.wltbagent.rtspcamera.camera.CameraController
import io.github.wltbagent.rtspcamera.stream.RtspStreamHub
import io.github.wltbagent.rtspcamera.stream.StreamingEngine
import io.github.wltbagent.rtspcamera.util.NetUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the camera, encoders, and RTSP server so streaming survives the UI
 * going away. Runs as a foreground service with camera/microphone types
 * while a stream is active. The activity binds to it for control and
 * provides the on-screen preview surface.
 */
class StreamingService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1

        /** Long safety timeout; the stream is stopped explicitly, not by the lock. */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        const val ACTION_STOP = "io.github.wltbagent.rtspcamera.action.STOP"
    }

    /** Snapshot of everything the UI needs to render. */
    data class StreamUiState(
        val cameraLabel: String = "",
        val isStreaming: Boolean = false,
        val rtspUrl: String = "",
        val clientCount: Int = 0,
        val error: String? = null,
    )

    inner class LocalBinder : Binder() {
        val service: StreamingService get() = this@StreamingService
    }

    lateinit var camera: CameraController
        private set
    lateinit var engine: StreamingEngine
        private set
    lateinit var settings: Settings
        private set

    private var hub: RtspStreamHub? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Set by the activity; the display preview renders here when attached. */
    var displaySurfaceProvider: Preview.SurfaceProvider? = null

    private val state = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = state

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        camera = CameraController(this)
        engine = StreamingEngine(this, camera)
        engine.frameListener = { frame -> hub?.onVideoFrame(frame) }
        engine.audioFrameListener = { frame -> hub?.onAudioFrame(frame) }
        engine.errorListener = { e -> onError(e) }

        // Refresh client-count/url in the UI state; cheap once a second.
        lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                publishState()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopStream()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopStream()
        displaySurfaceProvider = null
        super.onDestroy()
    }

    /** Initializes the camera provider; caller must hold the CAMERA permission. */
    suspend fun ensureCameraStarted() {
        camera.start(this, ::provideDisplaySurface)
        publishState()
    }

    fun startStream() {
        if (engine.isStreaming) return
        // Promote to a started service so unbinding (activity destroyed)
        // doesn't kill the stream.
        startService(Intent(this, StreamingService::class.java))
        startForegroundNotification()
        acquireWakeLock()

        engine.fps = settings.fps
        engine.bitrateBps = settings.bitrateMbps * 1_000_000
        engine.audioEnabled = settings.audioEnabled
        if (engine.resolution == null) {
            engine.resolution = defaultStreamSize()
        }
        val newHub = RtspStreamHub(settings.port, audioEnabled = { engine.audioEnabled })
        newHub.keyFrameRequester = { engine.requestKeyFrame() }
        newHub.start()
        hub = newHub
        engine.start()
        publishState()
    }

    fun stopStream() {
        if (!engine.isStreaming && hub == null) return
        engine.stop()
        hub?.stop()
        hub = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        publishState()
        // Safe even when bound: the service stays alive until the UI unbinds.
        stopSelf()
    }

    fun applySettings() {
        // Audio toggle and encoder settings need a stream restart to apply.
        if (engine.isStreaming) {
            stopStream()
            startStream()
        } else {
            publishState()
        }
    }

    private fun provideDisplaySurface(request: SurfaceRequest) {
        val provider = displaySurfaceProvider
        if (provider != null) {
            provider.onSurfaceRequested(request)
        } else {
            request.willNotProvideSurface()
        }
    }

    fun rtspUrl(): String {
        val ip = NetUtils.lanIpv4() ?: "<lan-ip>"
        val port = hub?.boundPort ?: settings.port
        return "rtsp://$ip:$port/${RtspStreamHub.DEFAULT_STREAM_NAME}"
    }

    private fun defaultStreamSize(): android.util.Size =
        engine.supportedSizes.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: engine.supportedSizes.firstOrNull()
            ?: android.util.Size(1920, 1080)

    private fun onError(e: Exception) {
        state.value = state.value.copy(error = e.message ?: e.javaClass.simpleName)
    }

    private fun publishState() {
        state.value = StreamUiState(
            cameraLabel = camera.cameras.getOrNull(camera.selectedIndex)?.label ?: "",
            isStreaming = engine.isStreaming,
            rtspUrl = rtspUrl(),
            clientCount = hub?.activeSessionCount ?: 0,
            error = state.value.error,
        )
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, StreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(rtspUrl())
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .build()
        val types = if (settings.audioEnabled) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rtspcamera:stream").also {
            it.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }
}
