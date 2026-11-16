package io.github.wltbagent.rtspcamera.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/** One encoded access unit from [VideoEncoder]. */
class EncodedFrame(
    val data: ByteArray,
    val flags: Int,
    val presentationTimeUs: Long,
) {
    val isKeyFrame: Boolean get() = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
    val isCodecConfig: Boolean get() = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
}

/**
 * Hardware H.264 encoder fed through a CameraX-provided input surface.
 *
 * One instance per stream session; it is recreated whenever the camera
 * rebinds (camera switch, resolution change) because the input surface is
 * tied to the camera pipeline.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
    private val iFrameIntervalSec: Float = 1f,
) {

    /** Called on the encoder drain thread. Keep consumers fast — copy if buffering. */
    var frameListener: ((EncodedFrame) -> Unit)? = null
    var errorListener: ((Exception) -> Unit)? = null

    private val codec: MediaCodec

    /** Surface the camera pipeline must render into. */
    val inputSurface: Surface
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSec)
            // Baseline: no B-frames, which RTP streaming has no buffer to reorder,
            // and the widest decoder compatibility for OBS/ffmpeg.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(
                MediaFormat.KEY_LEVEL,
                if (width.toLong() * height > 1920L * 1080L) {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel51
                } else {
                    MediaCodecInfo.CodecProfileLevel.AVCLevel42
                },
            )
            setInteger(MediaFormat.KEY_LATENCY, 0)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
        running.set(true)
        drainThread = Thread(::drainLoop, "video-encoder-drain").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // A camera-driven stop means the input surface is already dead; EOS then
        // throws, which is fine — the drain loop exits via [running].
        try {
            codec.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        drainThread?.join(1_000)
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
        try {
            inputSurface.release()
        } catch (_: Exception) {
        }
    }

    fun setBitrate(bps: Int) {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            codec.setParameters(params)
        } catch (_: IllegalStateException) {
        }
    }

    fun requestKeyFrame() {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
        } catch (_: IllegalStateException) {
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val out = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(out)
                            frameListener?.invoke(EncodedFrame(out, info.flags, info.presentationTimeUs))
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) errorListener?.invoke(e)
        }
    }

    private companion object {
        const val DRAIN_TIMEOUT_US = 10_000L
    }
}
