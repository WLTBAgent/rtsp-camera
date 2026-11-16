package io.github.wltbagent.rtspcamera.rtp

import kotlin.random.Random

/**
 * AAC over RTP per RFC 3640, "AAC-hbr" mode with 13-bit AU sizes.
 * One AAC access unit (1024 samples) fits one RTP packet at our bitrates,
 * so each packet carries a single AU and always carries the marker bit.
 */
class AacRtpPacketizer(
    override val ssrc: Long = Random.nextInt().toLong() and 0x7FFFFFFFL,
    initialSequenceNumber: Int = Random.nextInt(0, 0xFFFF),
) : RtpPacketizer {

    private var seq = initialSequenceNumber and 0xFFFF

    override val nextSequenceNumber: Int get() = seq

    override fun packetize(data: List<ByteArray>, timestamp: Long): List<ByteArray> {
        val packets = ArrayList<ByteArray>(data.size)
        for ((index, au) in data.withIndex()) {
            val marker = index == data.lastIndex
            packets.add(buildPacket(au, timestamp, marker))
        }
        return packets
    }

    /** Payload: 16-bit AU-headers-length + AU header (13-bit size, 3-bit index) + AU data. */
    private fun buildPacket(au: ByteArray, timestamp: Long, marker: Boolean): ByteArray {
        val auHeaderBytes = 2
        val auSize = au.size and 0x1FFF // 13 bits; AUs are far below 8192 bytes here

        val payload = ByteArray(AU_HEADERS_LENGTH_SIZE + auHeaderBytes + au.size)
        // AU-headers-length (16 bits): number of bits used by AU headers.
        payload[0] = 0x00
        payload[1] = (auHeaderBytes * 8).toByte()
        // AU header: size in the top 13 bits, index in the low 3 bits (0).
        payload[2] = (auSize ushr 5).toByte()
        payload[3] = ((auSize shl 3) and 0xFF).toByte()
        System.arraycopy(au, 0, payload, AU_HEADERS_LENGTH_SIZE + auHeaderBytes, au.size)

        return Rtp.buildHeader(marker, Rtp.PAYLOAD_TYPE_AAC, nextSeq(), timestamp, ssrc) + payload
    }

    private fun nextSeq(): Int {
        val current = seq
        seq = (seq + 1) and 0xFFFF
        return current
    }

    private companion object {
        const val AU_HEADERS_LENGTH_SIZE = 2
    }
}
