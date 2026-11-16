package io.github.wltbagent.rtspcamera.rtp

/**
 * RTP constants and header building (RFC 3550). We emit the minimal header:
 * no padding, no extensions, no CSRC list.
 */
object Rtp {

    /** Dynamic payload types declared in the SDP. */
    const val PAYLOAD_TYPE_H264 = 96
    const val PAYLOAD_TYPE_AAC = 97

    const val RTP_HEADER_SIZE = 12

    /** MediaCodec presentation time (µs) → RTP timestamp at the 90 kHz clock. */
    fun ptsUsToTimestamp(ptsUs: Long): Long = ptsUs * 9L / 100L

    fun buildHeader(
        marker: Boolean,
        payloadType: Int,
        sequenceNumber: Int,
        timestamp: Long,
        ssrc: Long,
    ): ByteArray {
        val b = ByteArray(RTP_HEADER_SIZE)
        // Byte 0: V=2, no padding, no extension, no CSRC.
        // Byte 1: marker flag (high bit) | payload type (7 bits).
        b[0] = 0x80.toByte()
        b[1] = ((if (marker) 0x80 else 0x00) or payloadType).toByte()
        b[2] = (sequenceNumber ushr 8).toByte()
        b[3] = sequenceNumber.toByte()
        putInt(b, 4, timestamp)
        putInt(b, 8, ssrc)
        return b
    }

    fun putInt(dest: ByteArray, offset: Int, value: Long) {
        dest[offset] = (value ushr 24).toByte()
        dest[offset + 1] = (value ushr 16).toByte()
        dest[offset + 2] = (value ushr 8).toByte()
        dest[offset + 3] = value.toByte()
    }

    fun getInt(src: ByteArray, offset: Int): Long =
        ((src[offset].toLong() and 0xFF) shl 24) or
            ((src[offset + 1].toLong() and 0xFF) shl 16) or
            ((src[offset + 2].toLong() and 0xFF) shl 8) or
            (src[offset + 3].toLong() and 0xFF)
}
