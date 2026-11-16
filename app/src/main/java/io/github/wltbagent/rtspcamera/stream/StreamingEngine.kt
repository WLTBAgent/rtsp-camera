package io.github.wltbagent.rtspcamera.stream

import android.content.Context
import android.util.Size
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import io.github.wltbagent.rtspcamera.camera.CameraController
import io.github.wltbagent.rtspcamera.encode.AudioEncoder
import io.github.wltbagent.rtspcamera.encode.EncodedFrame
import io.github.wltbagent.rtspcamera.encode.VideoEncoder

/**
 * Glue between the camera pipeline and the encoders. Encoded frames fan out
 * to listeners — the RTSP hub consumes both tracks; video is always on,
 * audio is optional and applied on the next stream (re)start.
 */
class StreamingEngine(
    context: Context,
    private val camera: CameraController,
) {

    var frameListener: ((EncodedFrame) -> Unit)? = null
    var audioFrameListener: ((EncodedFrame) -> Unit)? = null
    var errorListener: ((Exception) -> Unit)? = null

    var fps: Int = 30
    var bitrateBps: Int = 6_000_000
    var iFrameIntervalSec: Float = 1f

    /** Include the microphone track; takes effect on the next stream (re)start. */
    var audioEnabled: Boolean = false

    val isStreaming: Boolean get() = camera.isStreaming
    val supportedSizes: List<Size> get() = camera.supportedVideoSizes

    /** Stream output size; null lets CameraX decide (not recommended for streaming). */
    var resolution: Size?
        get() = camera.streamResolution
        set(value) {
            camera.streamResolution = value
        }

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var encoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null

    fun start() {
        camera.streamSurfaceListener = ::onSurfaceRequest
        camera.startStream()
        if (audioEnabled && audioEncoder == null) {
            startAudio()
        }
    }

    fun stop() {
        camera.streamSurfaceListener = null
        camera.stopStream()
        stopEncoder()
        stopAudio()
    }

    /** Live bitrate change; applies to the active encoder without a restart. */
    fun setBitrate(bps: Int) {
        bitrateBps = bps
        encoder?.setBitrate(bps)
    }

    /** Asks the active encoder for an I-frame (e.g. when a new client joins). */
    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    private fun startAudio() {
        val audio = AudioEncoder(
            sampleRate = RtspStreamHub.AUDIO_SAMPLE_RATE,
            channels = RtspStreamHub.AUDIO_CHANNELS,
        )
        audio.frameListener = { frame -> audioFrameListener?.invoke(frame) }
        audio.errorListener = { e -> errorListener?.invoke(e) }
        try {
            audio.start()
            audioEncoder = audio
        } catch (e: Exception) {
            // Audio is optional: keep the video stream alive without a mic track.
            errorListener?.invoke(e)
        }
    }

    private fun stopAudio() {
        audioEncoder?.stop()
        audioEncoder = null
    }

    private fun onSurfaceRequest(request: SurfaceRequest) {
        // Camera rebinds (camera switch, resolution change) deliver a fresh
        // request; the old encoder is torn down along with its surface.
        stopEncoder()
        val enc = VideoEncoder(
            width = request.resolution.width,
            height = request.resolution.height,
            fps = fps,
            bitrateBps = bitrateBps,
            iFrameIntervalSec = iFrameIntervalSec,
        )
        enc.frameListener = { frame -> frameListener?.invoke(frame) }
        enc.errorListener = { e -> errorListener?.invoke(e) }
        try {
            enc.start()
        } catch (e: Exception) {
            errorListener?.invoke(e)
            return
        }
        encoder = enc
        request.provideSurface(enc.inputSurface, mainExecutor) { result ->
            if (result.resultCode != SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                errorListener?.invoke(
                    IllegalStateException("Encoder surface rejected: code ${result.resultCode}"),
                )
            }
        }
    }

    private fun stopEncoder() {
        encoder?.stop()
        encoder = null
    }
}
