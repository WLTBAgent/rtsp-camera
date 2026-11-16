package io.github.wltbagent.rtspcamera.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AacRtpPacketizerTest {

    private val ts = 48_000L // one second at the 48 kHz audio clock

    private fun markerOf(packet: ByteArray): Boolean = (packet[1].toInt() and 0x80) != 0

    private fun seqOf(packet: ByteArray): Int =
        ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)

    private fun payloadOf(packet: ByteArray): ByteArray = packet.copyOfRange(12, packet.size)

    @Test
    fun `one access unit becomes one rfc 3640 packet`() {
        val packetizer = AacRtpPacketizer(ssrc = 7L)
        val au = ByteArray(300) { it.toByte() }

        val packets = packetizer.packetize(listOf(au), ts)

        assertEquals(1, packets.size)
        val packet = packets[0]
        assertEquals(97, packet[1].toInt() and 0x7F) // PT
        assertTrue(markerOf(packet))
        assertEquals(ts, Rtp.getInt(packet, 4))
        assertEquals(7L, Rtp.getInt(packet, 8))

        val payload = payloadOf(packet)
        // AU-headers-length: 16 bits (one 2-byte AU header).
        assertEquals(0x00, payload[0].toInt() and 0xFF)
        assertEquals(0x10, payload[1].toInt() and 0xFF)
        // AU header: 13-bit size in the top bits, index 0 in the low 3 bits.
        val auSize = ((payload[2].toInt() and 0xFF) shl 5) or ((payload[3].toInt() and 0xFF) ushr 3)
        assertEquals(300, auSize)
        // AU data follows.
        assertArrayEquals(au, payload.copyOfRange(4, payload.size))
    }

    @Test
    fun `multiple access units get sequential seq and marker on last`() {
        val packetizer = AacRtpPacketizer(ssrc = 7L)
        val aus = listOf(ByteArray(50), ByteArray(60), ByteArray(70))

        val packets = packetizer.packetize(aus, ts)

        assertEquals(3, packets.size)
        assertTrue(packets.dropLast(1).none { markerOf(it) })
        assertTrue(markerOf(packets.last()))
        for (i in 1 until packets.size) {
            assertEquals(seqOf(packets[i - 1]) + 1, seqOf(packets[i]))
        }
    }

    @Test
    fun `implements the packetizer interface`() {
        val packetizer: RtpPacketizer = AacRtpPacketizer(ssrc = 7L)
        assertEquals(7L, packetizer.ssrc)
    }
}
