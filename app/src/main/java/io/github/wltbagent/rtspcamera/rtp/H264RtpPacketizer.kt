package io.github.wltbagent.rtspcamera.rtp

import kotlin.math.ceil
import kotlin.random.Random

/**
 * Packetizes H.264 NAL units into RTP packets per RFC 6184.
 *
 * Supported packetization modes: single NAL unit packets and FU-A
 * fragmentation. STAP-A aggregation is intentionally not used — one RTP
 * packet per small NAL unit is fully interoperable with ffmpeg/VLC/OBS and
 * keeps the code simple.
 *
 * One instance per session (it owns the sequence number and SSRC).
 */
class H264RtpPacketizer(
    override val ssrc: Long = Random.nextInt().toLong() and 0x7FFFFFFFL,
    /** Max RTP payload bytes (after the 12-byte header) per packet. */
    private val maxPayloadSize: Int = 1400,
    /** Explicit start sequence number (e.g. resuming after an encoder restart). */
    initialSequenceNumber: Int = Random.nextInt(0, 0xFFFF),
) : RtpPacketizer {

    private var seq = initialSequenceNumber and 0xFFFF

    /** Sequence number that will be used by the next packet (for RTP-Info). */
    override val nextSequenceNumber: Int get() = seq

    /**
     * Packetizes one access unit: [data] holds the NAL units, which are
     * RTP-packetized in order; the marker bit is set on the very last packet
     * of the unit. The returned packets must be sent in order.
     */
    override fun packetize(data: List<ByteArray>, timestamp: Long): List<ByteArray> {
        val nalus = data
        val timestamp90k = timestamp
        val packets = ArrayList<ByteArray>()
        for ((index, nalu) in nalus.withIndex()) {
            val marker = index == nalus.lastIndex
            if (nalu.size <= maxPayloadSize) {
                packets.add(Rtp.buildHeader(marker, Rtp.PAYLOAD_TYPE_H264, nextSeq(), timestamp, ssrc) + nalu)
            } else {
                packets.addAll(fragmentFuA(nalu, timestamp, marker))
            }
        }
        return packets
    }

    /**
     * FU-A fragmentation (RFC 6184 §5.8). The first chunk carries the FU
     * start bit, the last carries the end bit; intermediate chunks carry the
     * original NAL type only.
     */
    private fun fragmentFuA(nalu: ByteArray, timestamp90k: Long, markerOnLast: Boolean): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        val fuIndicator = ((nalu[0].toInt() and 0xE0) or FU_A_TYPE).toByte()
        val nalType = nalu[0].toInt() and 0x1F
        val chunkSize = maxPayloadSize - 2 // FU indicator + FU header live in the payload
        val chunks = ceil((nalu.size - 1).toDouble() / chunkSize.toDouble()).toInt()

        var offset = 1
        var fuHeader: Byte
        for (chunkIndex in 0 until chunks) {
            val isStart = chunkIndex == 0
            val isEnd = chunkIndex == chunks - 1
            fuHeader = (
                (if (isStart) 0x80 else 0x00) or
                    (if (isEnd) 0x40 else 0x00) or
                    nalType
                ).toByte()
            val length = minOf(chunkSize, nalu.size - offset)
            val payload = ByteArray(2 + length)
            payload[0] = fuIndicator
            payload[1] = fuHeader
            System.arraycopy(nalu, offset, payload, 2, length)
            offset += length
            val marker = markerOnLast && isEnd
            packets.add(Rtp.buildHeader(marker, Rtp.PAYLOAD_TYPE_H264, nextSeq(), timestamp90k, ssrc) + payload)
        }
        return packets
    }

    private fun nextSeq(): Int {
        val current = seq
        seq = (seq + 1) and 0xFFFF
        return current
    }

    private companion object {
        const val FU_A_TYPE = 28
    }
}
