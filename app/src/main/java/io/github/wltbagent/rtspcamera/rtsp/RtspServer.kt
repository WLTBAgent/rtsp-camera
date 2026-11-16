package io.github.wltbagent.rtspcamera.rtsp

import io.github.wltbagent.rtspcamera.rtp.RtpPacketizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Minimal pull-mode RTSP 1.0 server for one stream with an H.264 video track
 * and (optionally served) AAC audio track. Supports the request subset
 * OBS/VLC/ffmpeg need: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN,
 * GET_PARAMETER. Both TCP-interleaved (default) and UDP transports.
 */
class RtspServer(
    private val port: Int,
    private val streamName: String,
    private val listener: Listener,
) {

    /** Callbacks into the stream hub that owns encoder output and sessions. */
    interface Listener {
        /** SDP for DESCRIBE, or null if parameter sets aren't known yet (→ 503). */
        fun buildSdp(): String?

        /** Track URL used in SDP a=control and RTP-Info. */
        fun trackUrl(baseUrl: String, trackName: String): String

        /** Packetizer for a track (null → unknown track → 404). */
        fun createPacketizer(trackName: String): RtpPacketizer?

        fun onSessionCreated(session: RtspSession)
        fun onSessionStarted(session: RtspSession)
        fun onSessionClosed(session: RtspSession)
    }

    private var serverSocket: ServerSocket? = null

    /** Actual port after binding (useful when started with port 0 = ephemeral). */
    @Volatile
    var boundPort: Int? = null
        private set

    @Volatile
    private var running = false
    private val clientSockets = Collections.synchronizedSet(HashSet<Socket>())

    fun start() {
        running = true
        thread(name = "rtsp-accept") {
            try {
                val server = ServerSocket(port)
                serverSocket = server
                boundPort = server.localPort
                while (running) {
                    val client = server.accept()
                    clientSockets.add(client)
                    thread(name = "rtsp-client-${client.inetAddress.hostAddress}") {
                        try {
                            RtspClientHandler(client, listener).run()
                        } finally {
                            clientSockets.remove(client)
                        }
                    }
                }
            } catch (_: IOException) {
                // accept() fails on stop(); normal shutdown path.
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        synchronized(clientSockets) {
            clientSockets.forEach { socket ->
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }
            clientSockets.clear()
        }
    }
}

/** Handles one client connection: request parse/response loop and session setup. */
internal class RtspClientHandler(
    private val socket: Socket,
    private val listener: RtspServer.Listener,
) {

    private val output = BufferedOutputStream(socket.getOutputStream())
    private val input = BufferedInputStream(socket.getInputStream())

    /** Track name → streaming state; all share one RTSP session id. */
    private val sessions = LinkedHashMap<String, RtspSession>()
    private var sessionId: String? = null
    private var streamUrl: String? = null
    private val udpSockets = ArrayList<DatagramSocket>()

    fun run() {
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            socket.tcpNoDelay = true
            requestLoop()
        } catch (_: SocketTimeoutException) {
            // Idle client (no keepalive within timeout): drop it.
        } catch (_: IOException) {
            // Client disconnected abruptly — normal for OBS stops.
        } finally {
            sessions.values.forEach { session ->
                session.stopPlayback()
                listener.onSessionClosed(session)
            }
            sessions.clear()
            udpSockets.forEach { datagramSocket ->
                try {
                    datagramSocket.close()
                } catch (_: Exception) {
                }
            }
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun requestLoop() {
        while (true) {
            val firstByte = input.read()
            if (firstByte == -1) return

            // Interleaved data from the client (e.g. RTCP receiver reports):
            // consume the framed record to stay byte-synced with RTSP responses.
            if (firstByte == TcpInterleavedSink.INTERLEAVED_MAGIC) {
                val channel = input.read()
                val lengthHi = input.read()
                val lengthLo = input.read()
                if (channel == -1 || lengthHi == -1 || lengthLo == -1) return
                val length = (lengthHi shl 8) or lengthLo
                skipFully(length)
                continue
            }

            val requestLine = readRestOfLine(firstByte) ?: return
            val headers = readHeaders()
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) skipFully(contentLength)

            val cseq = headers["cseq"] ?: "0"
            val method = requestLine.substringBefore(' ', "").uppercase()
            val url = requestLine.substringAfter(' ', "").substringBeforeLast(' ', "")
            if (streamUrl == null) {
                // First request establishes the stream base URL (…/live),
                // stripping a track suffix if the client went straight to SETUP.
                streamUrl = url.trimEnd('/').removeSuffix("/track0").removeSuffix("/track1")
            }

            when (method) {
                "OPTIONS" -> respond(
                    cseq,
                    "200 OK",
                    listOf("Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER"),
                )

                "DESCRIBE" -> {
                    val sdp = listener.buildSdp()
                    if (sdp == null) {
                        respond(cseq, "503 Service Unavailable")
                    } else {
                        respondBody(
                            cseq,
                            "200 OK",
                            listOf(
                                "Content-Type: application/sdp",
                                "Content-Base: ${baseUrl()}/",
                            ),
                            sdp,
                        )
                    }
                }

                "SETUP" -> handleSetup(cseq, url, headers["transport"] ?: "")
                "PLAY" -> handlePlay(cseq)
                "TEARDOWN" -> {
                    respond(cseq, "200 OK", listOf("Session: ${sessionId ?: ""}"))
                    return
                }

                "GET_PARAMETER", "SET_PARAMETER" -> respond(cseq, "200 OK")
                else -> respond(cseq, "501 Not Implemented")
            }
        }
    }

    private fun handleSetup(cseq: String, url: String, transportHeader: String) {
        val trackName = resolveTrackName(url)
        val packetizer = listener.createPacketizer(trackName)
        if (packetizer == null) {
            respond(cseq, "404 Not Found")
            return
        }
        val transport = negotiateTransport(transportHeader)
        if (transport == null) {
            respond(cseq, "461 Unsupported Transport")
            return
        }
        // A client re-SETUPping a track replaces the previous state for it.
        sessions[trackName]?.let { old ->
            old.stopPlayback()
            listener.onSessionClosed(old)
        }
        val sid = sessionId ?: newSessionId().also { sessionId = it }
        val session = RtspSession(sid, trackName, listener.trackUrl(baseUrl(), trackName), packetizer)
        sessions[trackName] = session
        session.attachSink(transport.second)
        listener.onSessionCreated(session)
        respond(
            cseq,
            "200 OK",
            listOf(
                "Transport: ${transport.first}",
                "Session: $sid;timeout=${SESSION_TIMEOUT_SECONDS}",
            ),
        )
    }

    private fun handlePlay(cseq: String) {
        val active = sessions.values.toList()
        if (active.isEmpty()) {
            respond(cseq, "455 Method Not Valid In This State")
            return
        }
        active.forEach { session ->
            session.startPlayback()
            listener.onSessionStarted(session)
        }
        val rtpInfoLines = active.map { session ->
            "RTP-Info: url=${session.trackUrl};seq=${session.nextSequenceNumber};" +
                "rtptime=${session.lastTimestamp}"
        }
        respond(
            cseq,
            "200 OK",
            listOf("Session: ${sessionId ?: ""};timeout=${SESSION_TIMEOUT_SECONDS}") + rtpInfoLines,
        )
    }

    /** SETUP URL may name a track explicitly or the stream base (→ video track). */
    private fun resolveTrackName(url: String): String {
        val suffix = url.trimEnd('/').substringAfterLast('/')
        return if (suffix.startsWith("track")) suffix else VIDEO_TRACK
    }

    /**
     * Builds a sink from the client's Transport header.
     * Returns (response transport header, sink) or null if unsupported.
     */
    private fun negotiateTransport(header: String): Pair<String, RtpSink>? {
        val params = header.split(';').map { it.trim() }
        val isTcp = params.firstOrNull()?.contains("/TCP") == true
        return if (isTcp) {
            val channels = params
                .firstOrNull { it.startsWith("interleaved=") }
                ?.substringAfter('=')
                ?.split('-')
                ?.mapNotNull { it.toIntOrNull() }
                ?: listOf(0, 1)
            val rtpChannel = channels.getOrElse(0) { 0 }
            val rtcpChannel = channels.getOrElse(1) { rtpChannel + 1 }
            "RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel" to
                TcpInterleavedSink(output, rtpChannel, rtcpChannel)
        } else {
            val ports = params
                .firstOrNull { it.startsWith("client_port=") }
                ?.substringAfter('=')
                ?.split('-')
                ?.mapNotNull { it.toIntOrNull() }
                ?: return null
            if (ports.size < 1) return null
            val clientRtp = ports[0]
            val clientRtcp = ports.getOrElse(1) { clientRtp + 1 }
            val host = socket.inetAddress.hostAddress ?: return null
            val rtpSocket = DatagramSocket()
            val rtcpSocket = DatagramSocket()
            udpSockets.add(rtpSocket)
            udpSockets.add(rtcpSocket)
            "RTP/AVP;unicast;client_port=$clientRtp-$clientRtcp;" +
                "server_port=${rtpSocket.localPort}-${rtcpSocket.localPort}" to UdpSink(
                rtpSocket,
                InetSocketAddress(host, clientRtp),
                rtcpSocket,
                InetSocketAddress(host, clientRtcp),
            )
        }
    }

    private fun baseUrl(): String = streamUrl ?: "rtsp://unknown/live"

    private fun respond(cseq: String, status: String, extraHeaders: List<String> = emptyList()) {
        respondBody(cseq, status, extraHeaders, null)
    }

    private fun respondBody(cseq: String, status: String, extraHeaders: List<String>, body: String?) {
        synchronized(output) {
            val sb = StringBuilder()
            sb.append("RTSP/1.0 ").append(status).append("\r\n")
            sb.append("CSeq: ").append(cseq).append("\r\n")
            sb.append("Server: ").append(SERVER_ID).append("\r\n")
            extraHeaders.forEach { sb.append(it).append("\r\n") }
            if (body != null) {
                sb.append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
            }
            sb.append("\r\n")
            output.write(sb.toString().encodeToByteArray())
            if (body != null) {
                output.write(body.encodeToByteArray())
            }
            output.flush()
        }
    }

    /** Reads the remainder of a line after [firstByte] was consumed. */
    private fun readRestOfLine(firstByte: Int): String? {
        val sb = StringBuilder().append(firstByte.toChar())
        while (true) {
            val b = input.read()
            when {
                b == -1 -> return null
                b == '\n'.code -> return sb.toString().trimEnd('\r')
                else -> sb.append(b.toChar())
            }
        }
    }

    private fun readHeaders(): Map<String, String> {
        val headers = HashMap<String, String>()
        while (true) {
            val line = readRestOfLine(input.read()) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }
        return headers
    }

    private fun skipFully(count: Int) {
        var remaining = count
        val buffer = ByteArray(4096)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n == -1) throw IOException("EOF while skipping")
            remaining -= n
        }
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 65_000
        const val SESSION_TIMEOUT_SECONDS = 60
        const val SERVER_ID = "rtsp-camera"
        const val VIDEO_TRACK = "track0"

        fun newSessionId(): String = java.lang.Long.toHexString(Random.nextLong()).take(12)
    }
}
