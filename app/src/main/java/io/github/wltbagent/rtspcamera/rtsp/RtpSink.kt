package io.github.wltbagent.rtspcamera.rtsp

import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Outbound RTP/RTCP path for one session track: TCP interleaved or UDP. */
interface RtpSink {
    fun send(packet: ByteArray)
    fun sendRtcp(packet: ByteArray)
}

/**
 * RTP over RTSP (RFC 2326 §10.12): packets framed as `$` + channel + length.
 * The output stream is shared with RTSP responses, so writes synchronize on it.
 */
class TcpInterleavedSink(
    private val output: OutputStream,
    private val rtpChannel: Int,
    private val rtcpChannel: Int,
) : RtpSink {

    override fun send(packet: ByteArray) = writeInterleaved(rtpChannel, packet)

    override fun sendRtcp(packet: ByteArray) = writeInterleaved(rtcpChannel, packet)

    private fun writeInterleaved(channel: Int, data: ByteArray) {
        synchronized(output) {
            output.write(INTERLEAVED_MAGIC)
            output.write(channel)
            output.write((data.size ushr 8) and 0xFF)
            output.write(data.size and 0xFF)
            output.write(data)
            output.flush()
        }
    }

    companion object {
        const val INTERLEAVED_MAGIC = 0x24 // '$'
    }
}

/** RTP over UDP to the client ports negotiated in SETUP. */
class UdpSink(
    private val rtpSocket: DatagramSocket,
    private val rtpDestination: InetSocketAddress,
    private val rtcpSocket: DatagramSocket,
    private val rtcpDestination: InetSocketAddress,
) : RtpSink {

    override fun send(packet: ByteArray) {
        rtpSocket.send(DatagramPacket(packet, packet.size, rtpDestination))
    }

    override fun sendRtcp(packet: ByteArray) {
        rtcpSocket.send(DatagramPacket(packet, packet.size, rtcpDestination))
    }
}
