package io.github.wltbagent.rtspcamera.camera

import android.content.Context
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData

/**
 * Owns the CameraX binding. Two Preview use cases may be bound together:
 * one rendering to the on-screen preview, and (while streaming) one feeding
 * the encoder input surface at the chosen resolution. Capability-gated
 * manual controls (focus/exposure) are added in M5.
 */
class CameraController(private val context: Context) {

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var owner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var entries: List<CameraCapabilities.CameraEntry> = emptyList()
    private var activeIndex = 0
    private var streaming = false

    /** Preferred stream output resolution; null lets CameraX decide. */
    var streamResolution: Size? = null
        set(value) {
            field = value
            if (streaming) bind(activeIndex)
        }

    /** Invoked on the main thread whenever the stream Preview gets a new [SurfaceRequest]. */
    var streamSurfaceListener: ((SurfaceRequest) -> Unit)? = null

    val cameras: List<CameraCapabilities.CameraEntry> get() = entries
    val selectedIndex: Int get() = activeIndex
    val isStreaming: Boolean get() = streaming
    val zoomState: LiveData<ZoomState>? get() = camera?.cameraInfo?.zoomState
    val hasFlashUnit: Boolean get() = camera?.cameraInfo?.hasFlashUnit() == true
    val canToggleFacing: Boolean
        get() = entries.any { it.facing == CameraCapabilities.Facing.BACK } &&
            entries.any { it.facing == CameraCapabilities.Facing.FRONT }

    /** Streamable sizes (camera ∩ encoder) for the active camera, largest first. */
    val supportedVideoSizes: List<Size>
        get() = entries.getOrNull(activeIndex)
            ?.let { CameraCapabilities.encoderCompatibleSizes(it.info) }
            ?: emptyList()

    /**
     * Initializes the camera provider and binds the default (primary back)
     * camera to [surfaceProvider].
     */
    suspend fun start(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider) {
        this.owner = owner
        this.surfaceProvider = surfaceProvider
        val p = ProcessCameraProvider.awaitInstance(context)
        provider = p
        entries = CameraCapabilities.enumerate(p.availableCameraInfos)
        bind(defaultIndex())
    }

    /** Rebinds the active use-case set to the camera at [index] in [cameras]. */
    fun bind(index: Int) {
        val p = provider ?: return
        val sp = surfaceProvider ?: return
        val own = owner ?: return
        val entry = entries.getOrNull(index) ?: return
        activeIndex = index
        p.unbindAll()
        val selector = CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { it == entry.info } }
            .build()
        // Bind the stream preview FIRST: CameraX resolves use-case resolutions
        // in bind order, so the stream's selector wins over the display's.
        val useCases: Array<Preview> = if (streaming) {
            arrayOf(buildStreamPreview(), buildDisplayPreview(sp))
        } else {
            arrayOf(buildDisplayPreview(sp))
        }
        camera = p.bindToLifecycle(own, selector, *useCases)
    }

    /** Binds the stream Preview (and the encoder path) alongside the display preview. */
    fun startStream() {
        streaming = true
        bind(activeIndex)
    }

    fun stopStream() {
        streaming = false
        bind(activeIndex)
    }

    /** Quick front/back switch, the common gesture during use. */
    fun toggleFacing() {
        val current = entries.getOrNull(activeIndex) ?: return
        val opposite = if (current.facing == CameraCapabilities.Facing.FRONT) {
            CameraCapabilities.Facing.BACK
        } else {
            CameraCapabilities.Facing.FRONT
        }
        entries.indexOfFirst { it.facing == opposite }.takeIf { it >= 0 }?.let(::bind)
    }

    /** Clamps [ratio] to the active camera's zoom range before applying. */
    fun setZoomRatio(ratio: Float) {
        val state = zoomState?.value ?: return
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    fun setTorch(enabled: Boolean) {
        if (hasFlashUnit) camera?.cameraControl?.enableTorch(enabled)
    }

    /** Manual-focus range (diopters) of the active camera, or null if unsupported. */
    val focusRange: ClosedFloatingPointRange<Float>?
        get() = entries.getOrNull(activeIndex)?.let { CameraCapabilities.focusRange(it.info) }

    /**
     * Manual focus at [diopters] (0 = infinity, larger = closer). Switches the
     * lens to AF-off; [resetFocus] restores continuous autofocus.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun setFocusDistance(diopters: Float) {
        val control = camera?.cameraControl ?: return
        Camera2CameraControl.from(control).setCaptureRequestOptions(
            CaptureRequestOptions.Builder().apply {
                setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF,
                )
                setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
            }.build(),
        )
    }

    /** Restores continuous autofocus, clearing manual-focus/AE interop options. */
    @OptIn(ExperimentalCamera2Interop::class)
    fun resetFocus() {
        val control = camera?.cameraControl ?: return
        Camera2CameraControl.from(control).clearCaptureRequestOptions()
    }

    /** Exposure compensation index range and step for the active camera, or null. */
    val exposureCompensation: Pair<IntRange, Float>?
        get() = entries.getOrNull(activeIndex)?.let { CameraCapabilities.exposureCompensation(it.info) }

    val currentExposureIndex: Int
        get() = camera?.cameraInfo?.let { CameraCapabilities.currentExposureIndex(it) } ?: 0

    fun setExposureCompensation(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun setAeLock(locked: Boolean) {
        val control = camera?.cameraControl ?: return
        Camera2CameraControl.from(control).setCaptureRequestOptions(
            CaptureRequestOptions.Builder().apply {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
            }.build(),
        )
    }

    /**
     * Display preview with an explicit high-res strategy: without it the
     * 640x480 default drags the shared-session composition away from the
     * stream's requested resolution.
     */
    private fun buildDisplayPreview(sp: Preview.SurfaceProvider): Preview =
        Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build(),
            )
            .build()
            .also { it.setSurfaceProvider(sp) }

    private fun buildStreamPreview(): Preview {
        val builder = Preview.Builder()
        builder.setResolutionSelector(
            ResolutionSelector.Builder()
                // Preview defaults to a 4:3 aspect strategy, which would
                // override the size request below — prefer 16:9 for streaming.
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        streamResolution ?: Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build(),
        )
        return builder.build().also { preview ->
            preview.setSurfaceProvider { request ->
                streamSurfaceListener?.invoke(request)
            }
        }
    }

    private fun defaultIndex(): Int {
        entries.firstOrNull { it.facing == CameraCapabilities.Facing.BACK }?.let { return entries.indexOf(it) }
        entries.firstOrNull()?.let { return entries.indexOf(it) }
        return 0
    }
}
