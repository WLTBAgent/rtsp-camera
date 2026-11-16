package io.github.wltbagent.rtspcamera.rtsp

import io.github.wltbagent.rtspcamera.rtp.AnnexB
import io.github.wltbagent.rtspcamera.rtp.Rtp
import java.util.Base64

/** Audio track descriptor for SDP generation. */
data class AudioTrackInfo(
    val sampleRate: Int,
    val channels: Int,
    /** AudioSpecificConfig bytes (from the AAC encoder's codec-config frame). */
    val config: ByteArray,
)

/** SDP (RFC 4566) description served on DESCRIBE. */
object Sdp {

    /**
     * Builds the SDP: video track always, audio track when [audio] is set.
     * [sps]/[pps] come from the live encoder so clients can start decoding
     * immediately without waiting for in-band parameter sets.
     */
    fun build(
        streamName: String,
        videoTrackName: String,
        sps: ByteArray,
        pps: ByteArray,
        audio: AudioTrackInfo?,
        audioTrackName: String = "track1",
        videoPayloadType: Int = Rtp.PAYLOAD_TYPE_H264,
        audioPayloadType: Int = Rtp.PAYLOAD_TYPE_AAC,
    ): String = buildString {
        appendLine("v=0")
        appendLine("o=- ${sessionCandidate()} 1 IN IP4 0.0.0.0")
        appendLine("s=$streamName")
        appendLine("c=IN IP4 0.0.0.0")
        appendLine("t=0 0")
        appendLine("m=video 0 RTP/AVP $videoPayloadType")
        appendLine("a=rtpmap:$videoPayloadType H264/90000")
        appendLine(
            "a=fmtp:$videoPayloadType packetization-mode=1;" +
                "profile-level-id=${profileLevelId(sps)};sprop-parameter-sets=${sprop(sps, pps)}",
        )
        appendLine("a=control:$videoTrackName")
        if (audio != null) {
            appendLine("m=audio 0 RTP/AVP $audioPayloadType")
            appendLine("a=rtpmap:$audioPayloadType mpeg4-generic/${audio.sampleRate}/${audio.channels}")
            appendLine(
                "a=fmtp:$audioPayloadType streamtype=5;profile-level-id=1;mode=AAC-hbr;" +
                    "sizelength=13;indexlength=3;indexdeltalength=3;config=${hex(audio.config)}",
            )
            appendLine("a=control:$audioTrackName")
        }
    }

    /**
     * profile-level-id is the first three bytes of the SPS NAL header/body:
     * profile_idc, constraint flags, level_idc.
     */
    fun profileLevelId(sps: ByteArray): String {
        if (sps.size < 4) return "42E01F" // baseline 3.1 fallback
        return "%02X%02X%02X".format(
            sps[1].toInt() and 0xFF,
            sps[2].toInt() and 0xFF,
            sps[3].toInt() and 0xFF,
        )
    }

    private fun sprop(sps: ByteArray, pps: ByteArray): String = listOf(sps, pps).joinToString(",") {
        Base64.getEncoder().encodeToString(it)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

    /** Extracts the SPS/PPS pair from an Annex-B codec-config buffer, if present. */
    fun extractParameterSets(annexB: ByteArray): Pair<ByteArray, ByteArray>? {
        val nalus = AnnexB.split(annexB)
        val sps = nalus.firstOrNull { AnnexB.nalType(it) == AnnexB.TYPE_SPS } ?: return null
        val pps = nalus.firstOrNull { AnnexB.nalType(it) == AnnexB.TYPE_PPS } ?: return null
        return sps to pps
    }

    private fun sessionCandidate(): String = System.nanoTime().toString()
}
