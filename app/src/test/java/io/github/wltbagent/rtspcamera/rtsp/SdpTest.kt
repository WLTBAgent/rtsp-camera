package io.github.wltbagent.rtspcamera.rtsp

import io.github.wltbagent.rtspcamera.rtp.Rtcp
import io.github.wltbagent.rtspcamera.rtp.Rtp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpTest {

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())

    @Test
    fun `video-only sdp contains rtpmap and fmtp with sprop`() {
        val sdp = Sdp.build("live", "track0", sps, pps, audio = null)

        assertTrue(sdp.contains("m=video 0 RTP/AVP 96"))
        assertTrue(sdp.contains("a=rtpmap:96 H264/90000"))
        assertTrue(sdp.contains("a=control:track0"))
        assertTrue(sdp.contains("packetization-mode=1"))
        // java.util.Base64: 0x67,0x42,0xC0,0x1F → "Z0LAHw=="
        assertTrue(sdp.contains("Z0LAHw=="))
        // No audio track when audio is disabled.
        assertTrue(!sdp.contains("m=audio"))
    }

    @Test
    fun `audio track is described when enabled`() {
        val audio = AudioTrackInfo(48_000, 1, byteArrayOf(0x11, 0x88.toByte()))
        val sdp = Sdp.build("live", "track0", sps, pps, audio, "track1")

        assertTrue(sdp.contains("m=audio 0 RTP/AVP 97"))
        assertTrue(sdp.contains("a=rtpmap:97 mpeg4-generic/48000/1"))
        assertTrue(sdp.contains("mode=AAC-hbr"))
        assertTrue(sdp.contains("config=1188"))
        assertTrue(sdp.contains("a=control:track1"))
        assertTrue(sdp.contains("m=video"))
    }

    @Test
    fun `profile level id comes from sps bytes 1 to 3`() {
        assertEquals("42C01F", Sdp.profileLevelId(sps))
    }

    @Test
    fun `profile level id falls back for short sps`() {
        assertEquals("42E01F", Sdp.profileLevelId(byteArrayOf(0x67)))
    }

    @Test
    fun `extracts sps and pps from codec config buffer`() {
        val config = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps

        val result = Sdp.extractParameterSets(config)

        assertNotNull(result)
        assertArrayEquals(sps, result!!.first)
        assertArrayEquals(pps, result.second)
    }

    @Test
    fun `returns null when config lacks sps or pps`() {
        val idrOnly = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2)
        assertEquals(null, Sdp.extractParameterSets(idrOnly))
    }
}

class RtcpTest {

    @Test
    fun `sender report has correct shape`() {
        val sr = Rtcp.senderReport(
            ssrc = 0x11223344L,
            rtpTimestamp = 90_000L,
            packetCount = 42L,
            octetCount = 1_000_000L,
            nowMs = 1_700_000_000_000L,
        )

        assertEquals(28, sr.size)
        assertEquals(0x80, sr[0].toInt() and 0x80) // V=2
        assertEquals(0, sr[0].toInt() and 0x1F)    // RC=0
        assertEquals(200, sr[1].toInt() and 0xFF)  // PT=SR
        assertEquals(6, sr[3].toInt() and 0xFF)    // length words
        assertEquals(0x11223344L, Rtp.getInt(sr, 4))
        assertEquals(90_000L, Rtp.getInt(sr, 16))
        assertEquals(42L, Rtp.getInt(sr, 20))
        assertEquals(1_000_000L, Rtp.getInt(sr, 24))

        // NTP seconds: epoch 1900 offset applied.
        val ntpSeconds = Rtp.getInt(sr, 8)
        assertEquals(1_700_000_000L + 2_208_988_800L, ntpSeconds)
    }
}
