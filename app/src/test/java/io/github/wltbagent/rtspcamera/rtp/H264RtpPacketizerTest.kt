package io.github.wltbagent.rtspcamera.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264RtpPacketizerTest {

    private val ts = 123456L

    private fun headerOf(packet: ByteArray): ByteArray = packet.copyOfRange(0, 12)
    private fun payloadOf(packet: ByteArray): ByteArray = packet.copyOfRange(12, packet.size)

    private fun seqOf(packet: ByteArray): Int =
        ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)

    private fun markerOf(packet: ByteArray): Boolean = (packet[1].toInt() and 0x80) != 0

    @Test
    fun `single small nalu becomes one packet with marker`() {
        val packetizer = H264RtpPacketizer(ssrc = 0x11223344L)
        val nalu = byteArrayOf(0x41, 1, 2, 3, 4, 5)

        val packets = packetizer.packetize(listOf(nalu), ts)

        assertEquals(1, packets.size)
        val header = headerOf(packets[0])
        assertEquals(0x80, header[0].toInt() and 0x80) // V=2, no P/X/CC
        assertTrue(markerOf(packets[0]))               // last packet of the AU
        assertEquals(Rtp.PAYLOAD_TYPE_H264, header[1].toInt() and 0x7F)
        assertEquals(ts, Rtp.getInt(header, 4))
        assertEquals(0x11223344L, Rtp.getInt(header, 8))
        assertArrayEquals(nalu, payloadOf(packets[0]))
    }

    @Test
    fun `large nalu is fragmented with fu-a and reassembles`() {
        val packetizer = H264RtpPacketizer(ssrc = 1L, maxPayloadSize = 100)
        // 500-byte IDR NALU (NRI=3) → chunks of 98 bytes → 6 packets.
        val nalu = byteArrayOf(0x65.toByte()) + ByteArray(499) { it.toByte() }

        val packets = packetizer.packetize(listOf(nalu), ts)

        val expectedChunks = 6
        assertEquals(expectedChunks, packets.size)

        // Reassemble from FU payloads.
        val fuIndicator = payloadOf(packets[0])[0].toInt() and 0xFF
        assertEquals(0x7C, fuIndicator) // F=0, NRI=3 (0x60), type 28 → 0x7C
        val originalType = nalu[0].toInt() and 0x1F

        val reassembled = ArrayList<Byte>()
        reassembled.add(((fuIndicator and 0xE0) or originalType).toByte())
        packets.forEachIndexed { index, packet ->
            val fuHeader = payloadOf(packet)[1].toInt() and 0xFF
            if (index == 0) {
                assertTrue((fuHeader and 0x80) != 0) // start bit
                assertTrue((fuHeader and 0x40) == 0)
            } else if (index == packets.size - 1) {
                assertTrue((fuHeader and 0x80) == 0)
                assertTrue((fuHeader and 0x40) != 0) // end bit
            } else {
                assertTrue((fuHeader and 0xC0) == 0)
            }
            assertEquals(originalType, fuHeader and 0x1F)
            reassembled.addAll(payloadOf(packet).drop(2))
        }

        assertArrayEquals(nalu, reassembled.toByteArray())

        // Marker only on the last packet.
        assertTrue(packets.dropLast(1).none { markerOf(it) })
        assertTrue(markerOf(packets.last()))
    }

    @Test
    fun `sequence numbers increment mod 65536 across packets`() {
        val packetizer = H264RtpPacketizer(ssrc = 1L, maxPayloadSize = 50)
        val nalu = ByteArray(300) { it.toByte() }

        val packets = packetizer.packetize(listOf(nalu), ts)

        for (i in 1 until packets.size) {
            assertEquals(seqOf(packets[i - 1]) + 1, seqOf(packets[i]))
        }
    }

    @Test
    fun `access unit marker lands only on last nalu`() {
        val packetizer = H264RtpPacketizer(ssrc = 1L)
        val sps = byteArrayOf(0x67, 1)
        val pps = byteArrayOf(0x68, 2)
        val idr = byteArrayOf(0x65, 3, 4)

        val packets = packetizer.packetize(listOf(sps, pps, idr), ts)

        assertEquals(3, packets.size)
        assertTrue(packets.dropLast(1).none { markerOf(it) })
        assertTrue(markerOf(packets.last()))
        // Same timestamp across the access unit.
        packets.forEach { assertEquals(ts, Rtp.getInt(headerOf(it), 4)) }
    }

    @Test
    fun `sequence wraps around at 16 bits`() {
        val packetizer = H264RtpPacketizer(ssrc = 1L, initialSequenceNumber = 0)
        val nalu = byteArrayOf(0x41, 1)
        repeat(0xFFFF) { packetizer.packetize(listOf(nalu), ts) }

        val first = packetizer.packetize(listOf(nalu), ts)
        val second = packetizer.packetize(listOf(nalu), ts)
        assertEquals(0xFFFF, seqOf(first[0]))
        assertEquals(0, seqOf(second[0])) // wrapped
    }

    @Test
    fun `pts microseconds convert to 90k clock`() {
        assertEquals(0L, Rtp.ptsUsToTimestamp(0))
        assertEquals(90000L, Rtp.ptsUsToTimestamp(1_000_000)) // 1 s
        // Integer µs truncate toward zero: 33 333 µs is 2 999 ticks, the next
        // representable microsecond boundary rounds to exactly 3 000 (30 fps).
        assertEquals(2999L, Rtp.ptsUsToTimestamp(33_333))
        assertEquals(3000L, Rtp.ptsUsToTimestamp(33_334))
    }
}
