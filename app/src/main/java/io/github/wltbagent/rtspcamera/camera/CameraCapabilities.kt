package io.github.wltbagent.rtspcamera.camera

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaCodecInfo.VideoCapabilities
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector

/**
 * Static per-device camera facts used to build the capability-driven UI.
 * Every control in the app is shown only when the device reports support,
 * and the queries live here.
 */
object CameraCapabilities {

    enum class Facing { BACK, FRONT, EXTERNAL, UNKNOWN }

    /** One selectable camera on the device. */
    data class CameraEntry(
        val info: CameraInfo,
        val facing: Facing,
        /** Camera2 camera id — useful for debugging vendor quirks. */
        val cameraId: String,
        val label: String,
    )

    /**
     * Label every camera the device exposes. Secondary back cameras
     * (ultrawide, macro, telephoto exposed as separate ids by some OEMs)
     * show up here as additional BACK entries.
     */
    fun enumerate(infos: List<CameraInfo>): List<CameraEntry> {
        val perFacingCount = mutableMapOf<Facing, Int>()
        return infos.map { info ->
            val facing = facingOf(info)
            val id = try {
                Camera2CameraInfo.from(info).cameraId
            } catch (_: IllegalArgumentException) {
                "?"
            }
            val n = perFacingCount.merge(facing, 1, Int::plus) ?: 1
            CameraEntry(
                info = info,
                facing = facing,
                cameraId = id,
                label = labelFor(facing, n, id, isMacroCapable(info)),
            )
        }
    }

    /**
     * Streamable output sizes for [info]: camera-supported SurfaceTexture sizes
     * intersected with what the default AVC encoder can encode.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun encoderCompatibleSizes(info: CameraInfo): List<android.util.Size> {
        val cameraSizes = try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                ?.toList()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val videoCaps = avcVideoCapabilities()
        return cameraSizes
            .filter { videoCaps == null || videoCaps.isSizeSupported(it.width, it.height) }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.width.toLong() * it.height }
    }

    /**
     * Manual-focus range in diopters (0 = infinity … min focus distance), or
     * null when the device doesn't support AF-off / reports no focus distance.
     * Diopters grow as the camera focuses closer, so macro capability shows
     * up as a large upper bound.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun focusRange(info: CameraInfo): ClosedFloatingPointRange<Float>? {
        val characteristics = try {
            Camera2CameraInfo.from(info)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val afModes = characteristics.getCameraCharacteristic(
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES,
        ) ?: return null
        if (!afModes.contains(android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)) return null
        val minFocusDistance = characteristics.getCameraCharacteristic(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE,
        ) ?: return null
        if (minFocusDistance <= 0f) return null
        return 0f..minFocusDistance
    }

    /**
     * Heuristic: cameras focusing at ≤ ~20 cm report ≥ 5 diopters. Used to
     * annotate the camera picker; actual range always comes from [focusRange].
     */
    fun isMacroCapable(info: CameraInfo): Boolean =
        focusRange(info)?.let { it.endInclusive >= MACRO_DIOPTERS } ?: false

    /** Exposure compensation index range and step, or null when not adjustable. */
    fun exposureCompensation(info: CameraInfo): Pair<IntRange, Float>? {
        val state = info.exposureState
        val range = state.exposureCompensationRange
        if (range.lower == range.upper) return null
        val step = state.exposureCompensationStep.toFloat()
        if (step <= 0f) return null
        return range.lower..range.upper to step
    }

    /** Current exposure compensation index for [info]. */
    fun currentExposureIndex(info: CameraInfo): Int = info.exposureState.exposureCompensationIndex

    private fun avcVideoCapabilities(): VideoCapabilities? = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && it.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC) }
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            ?.videoCapabilities
    } catch (_: Exception) {
        null
    }

    private fun facingOf(info: CameraInfo): Facing = when (info.lensFacing) {
        CameraSelector.LENS_FACING_BACK -> Facing.BACK
        CameraSelector.LENS_FACING_FRONT -> Facing.FRONT
        CameraSelector.LENS_FACING_EXTERNAL -> Facing.EXTERNAL
        else -> Facing.UNKNOWN
    }

    private fun labelFor(facing: Facing, occurrence: Int, id: String, macro: Boolean): String {
        val base = when (facing) {
            Facing.BACK -> "Back"
            Facing.FRONT -> "Front"
            Facing.EXTERNAL -> "External"
            Facing.UNKNOWN -> "Camera"
        }
        val suffix = if (occurrence == 1) "" else " #$occurrence"
        val macroTag = if (macro) " — macro" else ""
        return "$base$suffix$macroTag (id $id)"
    }

    private const val MACRO_DIOPTERS = 5f // ≈ 20 cm minimum focus distance
}
