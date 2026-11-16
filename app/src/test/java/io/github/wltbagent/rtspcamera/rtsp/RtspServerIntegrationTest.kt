package io.github.wltbagent.rtspcamera.rtsp

import android.media.MediaCodec
import io.github.wltbagent.rtspcamera.encode.EncodedFrame
import io.github.wltbagent.rtspcamera.rtp.Rtp
import io.github.wltbagent.rtspcamera.stream.RtspStreamHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * JVM end-to-end smoke test for the RTSP stack: drives the real
 * [RtspStreamHub] over a raw TCP socket like a client (ffmpeg/OBS style) and
 * validates SDP, transport negotiation, and interleaved RTP framing.
 */
class RtspServerIntegrationTest {

    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21, 0xA0.toByte())
    private val keyframePtsUs = 1_000_000L

    private fun annexB(vararg nalus: ByteArray): ByteArray = nalus.fold(
        ByteArray(0),
    ) { acc, nalu -> acc + byteArrayOf(0, 0, 0, 1) + nalu }

    @Test
    fun `full handshake serves sdp and interleaved rtp`() {
        val hub = RtspStreamHub(port = 0, streamName = "live")
        hub.start()
        try {
            // Wait for the accept thread to bind.
            val port = waitForBound(hub)
            assertTrue("ephemeral port expected", port > 0)

            // Encoder start produces a codec-config frame with SPS+PPS.
            hub.onVideoFrame(
                EncodedFrame(
                    annexB(sps, pps),
                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                    presentationTimeUs = 0,
                ),
            )

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                // OPTIONS
                val optionsResponse = request(
                    output, input,
                    "OPTIONS rtsp://127.0.0.1:$port/live RTSP/1.0",
                    listOf("CSeq: 1"),
                )
                assertEquals("RTSP/1.0 200 OK", optionsResponse.first)
                assertTrue(optionsResponse.second["public"]!!.contains("DESCRIBE"))

                // DESCRIBE — SDP must carry live parameter sets.
                val describe = request(
                    output, input,
                    "DESCRIBE rtsp://127.0.0.1:$port/live RTSP/1.0",
                    listOf("CSeq: 2", "Accept: application/sdp"),
                )
                assertEquals("RTSP/1.0 200 OK", describe.first)
                val sdp = describe.third
                assertTrue(sdp.contains("m=video 0 RTP/AVP 96"))
                assertTrue(sdp.contains("a=rtpmap:96 H264/90000"))
                assertTrue(sdp.contains("sprop-parameter-sets="))
                assertTrue(sdp.contains("a=control:track0"))

                // SETUP — interleaved TCP.
                val setup = request(
                    output, input,
                    "SETUP rtsp://127.0.0.1:$port/live/track0 RTSP/1.0",
                    listOf("CSeq: 3", "Transport: RTP/AVP/TCP;unicast;interleaved=0-1"),
                )
                assertEquals("RTSP/1.0 200 OK", setup.first)
                assertTrue(setup.second["transport"]!!.contains("interleaved=0-1"))
                val sessionHeader = setup.second["session"]!!
                val sessionId = sessionHeader.substringBefore(';')
                assertTrue(sessionId.isNotBlank())

                // PLAY
                val play = request(
                    output, input,
                    "PLAY rtsp://127.0.0.1:$port/live RTSP/1.0",
                    listOf("CSeq: 4", "Session: $sessionId"),
                )
                assertEquals("RTSP/1.0 200 OK", play.first)
                assertTrue(play.second["rtp-info"]!!.contains("url=rtsp://127.0.0.1:$port/live/track0"))

                // Encoded keyframe arrives after PLAY (hub prepends SPS/PPS).
                hub.onVideoFrame(EncodedFrame(annexB(idr), MediaCodec.BUFFER_FLAG_KEY_FRAME, keyframePtsUs))

                val records = listOf(readInterleavedRecord(input), readInterleavedRecord(input), readInterleavedRecord(input))
                assertEquals(listOf(0, 0, 0), records.map { it.first })

                val expectedTimestamp = Rtp.ptsUsToTimestamp(keyframePtsUs)
                // SPS packet
                validateRtp(records[0].second, expectedTimestamp, marker = false, firstNaluByte = 0x67)
                // PPS packet
                validateRtp(records[1].second, expectedTimestamp, marker = false, firstNaluByte = 0x68)
                // IDR packet — last of the access unit → marker set.
                validateRtp(records[2].second, expectedTimestamp, marker = true, firstNaluByte = 0x65)

                // TEARDOWN
                val teardown = request(
                    output, input,
                    "TEARDOWN rtsp://127.0.0.1:$port/live RTSP/1.0",
                    listOf("CSeq: 5", "Session: $sessionId"),
                )
                assertEquals("RTSP/1.0 200 OK", teardown.first)
            }
        } finally {
            hub.stop()
        }
    }

    @Test
    fun `describe before encoder config responds 503`() {
        val hub = RtspStreamHub(port = 0, streamName = "live")
        hub.start()
        try {
            val port = waitForBound(hub)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val response = request(
                    socket.getOutputStream(), socket.getInputStream(),
                    "DESCRIBE rtsp://127.0.0.1:$port/live RTSP/1.0",
                    listOf("CSeq: 1"),
                )
                assertEquals("RTSP/1.0 503 Service Unavailable", response.first)
            }
        } finally {
            hub.stop()
        }
    }

    // --- helpers ---

    private fun waitForBound(hub: RtspStreamHub): Int {
        repeat(100) {
            hub.boundPort?.let { return it }
            Thread.sleep(20)
        }
        error("RTSP server did not bind a port")
    }

    /** Sends one RTSP request, returns (status line, headers, body). */
    private fun request(
        output: java.io.OutputStream,
        input: InputStream,
        requestLine: String,
        headers: List<String>,
    ): Triple<String, Map<String, String>, String> {
        val req = buildString {
            append(requestLine).append("\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        output.write(req.encodeToByteArray())
        output.flush()

        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            head.append(input.read().toChar())
        }
        val lines = head.toString().trim().split("\r\n")
        val parsed = lines.drop(1).associate { line ->
            val (key, value) = line.split(":", limit = 2)
            key.trim().lowercase() to value.trim()
        }
        val bodyLength = parsed["content-length"]?.toIntOrNull() ?: 0
        val body = if (bodyLength > 0) {
            val buf = ByteArray(bodyLength)
            readFully(input, buf)
            String(buf)
        } else {
            ""
        }
        return Triple(lines[0], parsed, body)
    }

    /** Reads one interleaved record: `$` + channel + length + data. */
    private fun readInterleavedRecord(input: InputStream): Pair<Int, ByteArray> {
        assertEquals(0x24, input.read()) // '$'
        val channel = input.read()
        val length = (input.read() shl 8) or input.read()
        val data = ByteArray(length)
        readFully(input, data)
        return channel to data
    }

    private fun validateRtp(packet: ByteArray, timestamp: Long, marker: Boolean, firstNaluByte: Int) {
        assertEquals(0x80, packet[0].toInt() and 0x80) // V=2
        assertEquals(if (marker) 0x80 else 0, packet[1].toInt() and 0x80) // marker bit
        assertEquals(Rtp.PAYLOAD_TYPE_H264, packet[1].toInt() and 0x7F)
        assertEquals(timestamp, Rtp.getInt(packet, 4))
        assertTrue(packet.size > Rtp.RTP_HEADER_SIZE)
        assertEquals(firstNaluByte, packet[Rtp.RTP_HEADER_SIZE].toInt() and 0xFF)
    }

    private fun readFully(input: InputStream, dest: ByteArray) {
        var offset = 0
        while (offset < dest.size) {
            val n = input.read(dest, offset, dest.size - offset)
            assertNotNull("stream ended early", n)
            offset += n
        }
    }
}
