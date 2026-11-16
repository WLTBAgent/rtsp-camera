package io.github.wltbagent.rtspcamera.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBTest {

    private val sc4 = byteArrayOf(0, 0, 0, 1)
    private val sc3 = byteArrayOf(0, 0, 1)

    @Test
    fun `splits four byte start codes`() {
        val nalu1 = byteArrayOf(0x67, 1, 2, 3)
        val nalu2 = byteArrayOf(0x68, 4, 5)
        val stream = sc4 + nalu1 + sc4 + nalu2

        val result = AnnexB.split(stream)

        assertEquals(2, result.size)
        assertArrayEquals(nalu1, result[0])
        assertArrayEquals(nalu2, result[1])
    }

    @Test
    fun `splits three byte start codes`() {
        val nalu1 = byteArrayOf(0x67, 9)
        val nalu2 = byteArrayOf(0x68, 8, 7)
        val stream = sc3 + nalu1 + sc3 + nalu2

        val result = AnnexB.split(stream)

        assertEquals(2, result.size)
        assertArrayEquals(nalu1, result[0])
        assertArrayEquals(nalu2, result[1])
    }

    @Test
    fun `strips trailing zero bytes before next start code`() {
        val nalu1 = byteArrayOf(0x67, 1, 2)
        val nalu2 = byteArrayOf(0x68, 3)
        val stream = sc4 + nalu1 + byteArrayOf(0, 0, 0) + sc4 + nalu2

        val result = AnnexB.split(stream)

        assertEquals(2, result.size)
        assertArrayEquals(nalu1, result[0])
        assertArrayEquals(nalu2, result[1])
    }

    @Test
    fun `handles zero byte run as leading of next start code`() {
        // ... NALU | 00 00 00 01 | next NALU — the run belongs to the next code.
        val nalu1 = byteArrayOf(0x67, 1)
        val nalu2 = byteArrayOf(0x65, 9, 9)
        val stream = sc3 + nalu1 + byteArrayOf(0, 0, 0, 0, 1) + nalu2

        val result = AnnexB.split(stream)

        assertEquals(2, result.size)
        assertArrayEquals(nalu1, result[0])
        assertArrayEquals(nalu2, result[1])
    }

    @Test
    fun `returns empty for stream without start codes`() {
        assertTrue(AnnexB.split(byteArrayOf(1, 2, 3, 4, 5)).isEmpty())
        assertTrue(AnnexB.split(byteArrayOf(0, 0, 3, 1, 2)).isEmpty())
        assertTrue(AnnexB.split(ByteArray(0)).isEmpty())
    }

    @Test
    fun `identifies nal types`() {
        assertEquals(AnnexB.TYPE_SPS, AnnexB.nalType(byteArrayOf(0x67, 0)))
        assertEquals(AnnexB.TYPE_PPS, AnnexB.nalType(byteArrayOf(0x68, 0)))
        assertEquals(AnnexB.TYPE_IDR, AnnexB.nalType(byteArrayOf(0x65, 0)))
        assertEquals(1, AnnexB.nalType(byteArrayOf(0x41, 0))) // non-IDR slice
    }
}
