package io.github.wltbagent.rtspcamera.rtsp

import io.github.wltbagent.rtspcamera.rtp.Rtcp
import io.github.wltbagent.rtspcamera.rtp.RtpPacketizer
import android.util.Log
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming state for one track of one RTSP client: RTP packetizer, outbound
 * queue with a dedicated writer thread, and the RTCP sender-report loop. The
 * queue is bounded so a slow client causes frame drops, never a stalled encoder.
 *
 * One client session (one RTSP session id) owns one [RtspSession] per track.
 */
class RtspSession(
    val id: String,
    val trackName: String,
    val trackUrl: String,
    private val packetizer: RtpPacketizer,
) {

    private val playing = AtomicBoolean(false)

    /** Bounded queue of complete access units; each entry is a list of RTP packets. */
    private val queue = ArrayBlockingQueue<List<ByteArray>>(MAX_QUEUED_ACCESS_UNITS)

    @Volatile
    private var sink: RtpSink? = null
    private var writerThread: Thread? = null
    private var rtcpThread: Thread? = null

    @Volatile
    var lastTimestamp: Long = 0
        private set

    @Volatile
    var packetsSent: Long = 0
        private set

    @Volatile
    var octetsSent: Long = 0
        private set

    @Volatile
    var droppedAccessUnits: Long = 0
        private set

    val nextSequenceNumber: Int get() = packetizer.nextSequenceNumber

    /** Attaches the transport chosen during SETUP. */
    fun attachSink(sink: RtpSink) {
        this.sink = sink
    }

    /** Sends one access unit at [timestamp] (already in the track's RTP clock). Non-blocking. */
    fun sendAccessUnit(data: List<ByteArray>, timestamp: Long) {
        if (!playing.get() || data.isEmpty()) return
        val packets = packetizer.packetize(data, timestamp)
        if (packets.isEmpty()) return
        lastTimestamp = timestamp
        packetsSent += packets.size
        octetsSent += packets.sumOf { it.size - RTP_HEADER_SIZE }
        if (!queue.offer(packets)) {
            droppedAccessUnits++
            if (droppedAccessUnits % 30L == 1L) {
                Log.d("wltb-session", "queue full; dropped=$droppedAccessUnits packetsSent=$packetsSent")
            }
        }
    }

    fun startPlayback() {
        if (!playing.compareAndSet(false, true)) return
        queue.clear()
        writerThread = Thread(::writerLoop, "rtsp-rtp-$id-$trackName").also { it.start() }
        rtcpThread = Thread(::rtcpLoop, "rtcp-$id-$trackName").also { it.start() }
    }

    fun stopPlayback() {
        if (!playing.compareAndSet(true, false)) return
        queue.clear()
        writerThread?.interrupt()
        rtcpThread?.interrupt()
        writerThread = null
        rtcpThread = null
    }

    private fun writerLoop() {
        val activeSink = sink ?: return
        Log.d("wltb-session", "writer loop started track=$trackName")
        try {
            while (playing.get()) {
                val packets = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                for (packet in packets) {
                    activeSink.send(packet)
                }
            }
        } catch (e: IOException) {
            Log.d("wltb-session", "writer io error track=$trackName: ${e.message}")
            // Client socket died; the handler notices and tears the session down.
            playing.set(false)
        } catch (_: InterruptedException) {
            // Stopped.
        }
    }

    private fun rtcpLoop() {
        while (playing.get()) {
            try {
                Thread.sleep(RTCP_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val activeSink = sink ?: continue
            try {
                activeSink.sendRtcp(
                    Rtcp.senderReport(packetizer.ssrc, lastTimestamp, packetsSent, octetsSent),
                )
            } catch (_: IOException) {
                // Same recovery path as the writer loop.
            }
        }
    }

    private companion object {
        const val MAX_QUEUED_ACCESS_UNITS = 8
        const val RTP_HEADER_SIZE = 12
        const val RTCP_INTERVAL_MS = 5_000L
    }
}
