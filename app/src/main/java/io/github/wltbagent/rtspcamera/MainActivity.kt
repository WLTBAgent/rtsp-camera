package io.github.wltbagent.rtspcamera

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ScaleGestureDetector
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import io.github.wltbagent.rtspcamera.camera.CameraController
import io.github.wltbagent.rtspcamera.databinding.ActivityMainBinding
import io.github.wltbagent.rtspcamera.service.StreamingService
import io.github.wltbagent.rtspcamera.util.QrCode
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: StreamingService? = null
    private var torchOn = false
    private var sliderTracking = false
    private var advancedVisible = false
    private var aeLocked = false

    // Zoom state LiveData changes whenever the active camera changes; track the
    // current one so rebinds replace the observer instead of stacking extras.
    private var zoomLive: LiveData<androidx.camera.core.ZoomState>? = null
    private var zoomObserver: Observer<androidx.camera.core.ZoomState>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                binding.statusText.text = getString(R.string.camera_permission_required)
            }
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                service?.let {
                    it.settings.audioEnabled = true
                    it.applySettings()
                }
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The foreground service runs regardless; this only controls whether
            // the notification is visible.
        }

    private val pinchDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val camera = service?.camera ?: return true
                val state = camera.zoomState?.value ?: return true
                camera.setZoomRatio(state.zoomRatio * detector.scaleFactor)
                return true
            }
        })
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as StreamingService.LocalBinder).service
            onServiceReady()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupZoomSlider()
        setupPinchToZoom()
        setupButtons()

        // Bind (creates the service) and start it so it stays resident even
        // after the activity is destroyed while a stream is active.
        startService(Intent(this, StreamingService::class.java))
        bindService(Intent(this, StreamingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        service?.displaySurfaceProvider = null
        if (service != null) {
            unbindService(serviceConnection)
        }
        service = null
        super.onDestroy()
    }

    private fun onServiceReady() {
        val s = service ?: return
        s.displaySurfaceProvider = binding.previewView.surfaceProvider
        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
        lifecycleScope.launch {
            s.uiState.collect { state -> renderState(state) }
        }
    }

    private fun startCamera() {
        val s = service ?: return
        lifecycleScope.launch {
            try {
                s.ensureCameraStarted()
            } catch (e: Exception) {
                binding.statusText.text = getString(R.string.camera_init_failed, e.message)
                return@launch
            }
            refreshControls()
        }
    }

    private fun renderState(state: StreamingService.StreamUiState) {
        val s = service ?: return
        binding.streamButton.text =
            getString(if (state.isStreaming) R.string.stop_button else R.string.stream_button)
        binding.statusText.text = when {
            state.error != null -> getString(R.string.stream_error, state.error)
            state.isStreaming -> if (state.clientCount > 0) {
                "${state.rtspUrl} — ${state.clientCount} client(s)"
            } else {
                state.rtspUrl
            }

            else -> state.cameraLabel
        }
        binding.lensButton.isVisible = s.camera.cameras.size > 1
        binding.switchButton.isVisible = s.camera.canToggleFacing
        binding.torchButton.isVisible = s.camera.hasFlashUnit
        binding.zoomSlider.isVisible = s.camera.zoomState != null
        binding.qrButton.isVisible = state.isStreaming
        binding.audioButton.text =
            getString(if (s.settings.audioEnabled) R.string.audio_on else R.string.audio_off)
        syncZoomObserver()
        refreshAdvancedControls()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun syncZoomObserver() {
        val target = service?.camera?.zoomState ?: return
        if (target === zoomLive) return
        val observer = zoomObserver ?: Observer<androidx.camera.core.ZoomState> { state ->
            onZoomStateChanged(state)
        }.also { zoomObserver = it }
        zoomLive?.removeObserver(observer)
        zoomLive = target
        target.observe(this, observer)
    }

    private fun onZoomStateChanged(state: androidx.camera.core.ZoomState) {
        val min = state.minZoomRatio
        val max = state.maxZoomRatio
        if (binding.zoomSlider.valueFrom != min) binding.zoomSlider.valueFrom = min
        if (binding.zoomSlider.valueTo != max) binding.zoomSlider.valueTo = max
        if (!sliderTracking) {
            val clamped = state.zoomRatio.coerceIn(min, max)
            if (abs(binding.zoomSlider.value - clamped) > ZOOM_SLIDER_EPSILON) {
                binding.zoomSlider.value = clamped
            }
        }
    }

    private fun setupZoomSlider() {
        binding.zoomSlider.addOnChangeListener { _, value, _ ->
            service?.camera?.setZoomRatio(value)
        }
        binding.zoomSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                sliderTracking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                sliderTracking = false
            }
        })
    }

    private fun setupPinchToZoom() {
        binding.previewView.setOnTouchListener { _, event ->
            pinchDetector.onTouchEvent(event)
            true
        }
    }

    private fun setupButtons() {
        binding.lensButton.setOnClickListener {
            val s = service ?: return@setOnClickListener
            val labels = s.camera.cameras.map { it.label }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_camera)
                .setSingleChoiceItems(labels, s.camera.selectedIndex) { dialog, which ->
                    s.camera.bind(which)
                    dialog.dismiss()
                    refreshControls()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.switchButton.setOnClickListener {
            service?.camera?.toggleFacing()
            refreshControls()
        }

        binding.torchButton.setOnClickListener {
            torchOn = !torchOn
            service?.camera?.setTorch(torchOn)
        }

        binding.streamButton.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.engine.isStreaming) {
                s.stopStream()
            } else {
                requestNotificationPermissionIfNeeded()
                s.startStream()
            }
            refreshControls()
        }

        binding.audioButton.setOnClickListener {
            val s = service ?: return@setOnClickListener
            when {
                s.settings.audioEnabled -> {
                    s.settings.audioEnabled = false
                    s.applySettings()
                }

                hasAudioPermission() -> {
                    s.settings.audioEnabled = true
                    s.applySettings()
                }

                else -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.resButton.setOnClickListener {
            val s = service ?: return@setOnClickListener
            val sizes = s.engine.supportedSizes
            if (sizes.isEmpty()) return@setOnClickListener
            val current = s.engine.resolution
            val labels = sizes.map { "${it.width}x${it.height}" }.toTypedArray()
            val checked = sizes.indexOfFirst { it == current }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_resolution)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    s.settings.resolution = "${sizes[which].width}x${sizes[which].height}"
                    s.engine.resolution = sizes[which]
                    s.applySettings()
                    dialog.dismiss()
                    refreshControls()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        binding.qrButton.setOnClickListener {
            val s = service ?: return@setOnClickListener
            showQrDialog(s.rtspUrl())
        }

        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        binding.advancedButton.setOnClickListener {
            advancedVisible = !advancedVisible
            refreshAdvancedControls()
        }

        binding.focusSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) service?.camera?.setFocusDistance(value)
        }

        binding.focusAutoButton.setOnClickListener {
            service?.camera?.resetFocus()
            aeLocked = false
            binding.aeLockButton.text = getString(R.string.ae_lock)
        }

        binding.exposureSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) service?.camera?.setExposureCompensation(value.roundToInt())
        }

        binding.aeLockButton.setOnClickListener {
            aeLocked = !aeLocked
            service?.camera?.setAeLock(aeLocked)
            binding.aeLockButton.text =
                getString(if (aeLocked) R.string.ae_unlock else R.string.ae_lock)
        }
    }

    /** Capability-gated manual focus / exposure / AE-lock controls. */
    private fun refreshAdvancedControls() {
        val camera = service?.camera ?: return
        val focusRange = camera.focusRange
        val exposure = camera.exposureCompensation
        val hasAdvanced = focusRange != null || exposure != null

        binding.advancedButton.isVisible = hasAdvanced
        binding.advancedPanel.isVisible = hasAdvanced && advancedVisible
        if (!hasAdvanced) return

        if (focusRange != null) {
            binding.focusSlider.valueFrom = focusRange.start
            binding.focusSlider.valueTo = focusRange.endInclusive
            binding.focusSlider.isVisible = advancedVisible
        } else {
            binding.focusSlider.isVisible = false
        }
        binding.focusAutoButton.isVisible = focusRange != null

        binding.exposureLabel.isVisible = exposure != null
        binding.exposureSlider.isVisible = exposure != null
        binding.aeLockButton.isVisible = exposure != null
        if (exposure != null) {
            val (range, step) = exposure
            // Slider requires an integral number of steps across the range;
            // fall back to a continuous slider for odd vendor step values.
            val steps = (range.last - range.first).toFloat() / step
            binding.exposureSlider.valueFrom = range.first.toFloat()
            binding.exposureSlider.valueTo = range.last.toFloat()
            binding.exposureSlider.stepSize =
                if (abs(steps - round(steps)) < 0.01f) step else 0f
            binding.exposureSlider.value = camera.currentExposureIndex
                .toFloat()
                .coerceIn(range.first.toFloat(), range.last.toFloat())
        }
    }

    private fun refreshControls() {
        renderState(service?.uiState?.value ?: return)
    }

    private fun showQrDialog(url: String) {
        val bitmap = QrCode.generate(url, QR_BITMAP_SIZE)
        val view = ImageView(this).apply {
            setImageBitmap(bitmap)
            setPadding(24, 24, 24, 24)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qr_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showSettingsDialog() {
        val s = service ?: return
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val portEdit = view.findViewById<TextInputEditText>(R.id.portEdit)
        val fpsEdit = view.findViewById<TextInputEditText>(R.id.fpsEdit)
        val bitrateEdit = view.findViewById<TextInputEditText>(R.id.bitrateEdit)
        portEdit.setText(s.settings.port.toString())
        fpsEdit.setText(s.settings.fps.toString())
        bitrateEdit.setText(s.settings.bitrateMbps.toString())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                s.settings.port = portEdit.text?.toString()?.toIntOrNull() ?: s.settings.port
                s.settings.fps = fpsEdit.text?.toString()?.toIntOrNull() ?: s.settings.fps
                s.settings.bitrateMbps =
                    bitrateEdit.text?.toString()?.toIntOrNull() ?: s.settings.bitrateMbps
                s.applySettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val ZOOM_SLIDER_EPSILON = 1e-4f
        const val QR_BITMAP_SIZE = 512
    }
}
