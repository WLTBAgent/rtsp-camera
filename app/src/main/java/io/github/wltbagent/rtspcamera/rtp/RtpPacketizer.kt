package io.github.wltbagent.rtspcamera.rtp

/**
 * Turns codec access units into RTP packets. Implemented per codec:
 * [H264RtpPacketizer] (RFC 6184) and [AacRtpPacketizer] (RFC 3640).
 */
interface RtpPacketizer {
    val ssrc: Long
    val nextSequenceNumber: Int

    /**
     * Packetizes one access unit; the marker bit lands on the last packet.
     * [data] is codec-specific: NAL units for H.264, one AAC access unit for AAC.
     */
    fun packetize(data: List<ByteArray>, timestamp: Long): List<ByteArray>
}
