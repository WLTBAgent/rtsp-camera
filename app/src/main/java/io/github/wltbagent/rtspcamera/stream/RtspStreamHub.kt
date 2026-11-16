package io.github.wltbagent.rtspcamera.stream

import io.github.wltbagent.rtspcamera.encode.EncodedFrame
import io.github.wltbagent.rtspcamera.rtp.AacRtpPacketizer
import io.github.wltbagent.rtspcamera.rtp.AnnexB
import io.github.wltbagent.rtspcamera.rtp.H264RtpPacketizer
import io.github.wltbagent.rtspcamera.rtp.Rtp
import io.github.wltbagent.rtspcamera.rtp.RtpPacketizer
import io.github.wltbagent.rtspcamera.rtsp.AudioTrackInfo
import io.github.wltbagent.rtspcamera.rtsp.RtspServer
import io.github.wltbagent.rtspcamera.rtsp.RtspSession
import io.github.wltbagent.rtspcamera.rtsp.Sdp
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Connects encoder output to RTSP clients. Owns the SPS/PPS parameter sets
 * (from the encoder's codec-config frame), the AudioSpecificConfig (when the
 * audio track is on), the SDP, and the set of active per-track client
 * sessions; every encoded access unit is packetized and queued per session.
 */
class RtspStreamHub(
    private val port: Int = DEFAULT_PORT,
    private val streamName: String = DEFAULT_STREAM_NAME,
    private val audioEnabled: () -> Boolean = { false },
) : RtspServer.Listener {

    /** Invoked when a client starts PLAY so a fresh I-frame can be requested. */
    var keyFrameRequester: (() -> Unit)? = null

    private val server = RtspServer(port, streamName, this)
    private val sessions = CopyOnWriteArrayList<RtspSession>()

    @Volatile
    private var sps: ByteArray? = null

    @Volatile
    private var pps: ByteArray? = null

    /** AudioSpecificConfig from the AAC encoder's codec-config frame. */
    @Volatile
    private var audioConfig: ByteArray? = null

    val isRunning: Boolean get() = serverStarted

    /** Actual listen port (after [start]); null until the socket is bound. */
    val boundPort: Int? get() = server.boundPort

    @Volatile
    private var serverStarted = false

    val activeSessionCount: Int get() = sessions.map { it.id }.distinct().count()

    fun start() {
        if (serverStarted) return
        serverStarted = true
        server.start()
    }

    fun stop() {
        server.stop()
        serverStarted = false
        sessions.forEach { it.stopPlayback() }
        sessions.clear()
    }

    /** Entry point for encoded video frames from [StreamingEngine]. */
    fun onVideoFrame(frame: EncodedFrame) {
        if (frame.isCodecConfig) {
            val (spsNalu, ppsNalu) = Sdp.extractParameterSets(frame.data) ?: return
            sps = spsNalu
            pps = ppsNalu
            return
        }
        val videoSessions = sessions.filter { it.trackName == VIDEO_TRACK }
        if (videoSessions.isEmpty()) return
        val nalus = AnnexB.split(frame.data)
        if (nalus.isEmpty()) return

        // Prepend parameter sets to every key frame: lets late joiners and
        // clients riding through an encoder restart (resolution change) stay
        // decodable without a new DESCRIBE.
        val parameterSets = listOfNotNull(
            sps?.takeIf { frame.isKeyFrame },
            pps?.takeIf { frame.isKeyFrame },
        )
        val timestamp = Rtp.ptsUsToTimestamp(frame.presentationTimeUs)
        videoSessions.forEach { session ->
            session.sendAccessUnit(parameterSets + nalus, timestamp)
        }
    }

    /** Entry point for encoded audio frames from [StreamingEngine]. */
    fun onAudioFrame(frame: EncodedFrame) {
        if (frame.isCodecConfig) {
            if (frame.data.isNotEmpty()) audioConfig = frame.data
            return
        }
        val audioSessions = sessions.filter { it.trackName == AUDIO_TRACK }
        if (audioSessions.isEmpty()) return
        // mpeg4-generic RTP clock is the sample rate (48 kHz), not 90 kHz.
        // Round (not floor) so each 1024-sample AU lands exactly on its
        // sample-clock tick; flooring drifts and trips decoder DTS checks.
        val timestamp = (frame.presentationTimeUs * AUDIO_CLOCK_HZ + 500_000L) / 1_000_000L
        audioSessions.forEach { session ->
            session.sendAccessUnit(listOf(frame.data), timestamp)
        }
    }

    // --- RtspServer.Listener ---

    override fun buildSdp(): String? {
        val sps = sps ?: return null
        val pps = pps ?: return null
        val audio = if (audioEnabled()) {
            audioConfig?.let { AudioTrackInfo(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS, it) }
        } else {
            null
        }
        return Sdp.build(streamName, VIDEO_TRACK, sps, pps, audio, AUDIO_TRACK)
    }

    override fun trackUrl(baseUrl: String, trackName: String): String = "$baseUrl/$trackName"

    override fun createPacketizer(trackName: String): RtpPacketizer? = when (trackName) {
        VIDEO_TRACK -> H264RtpPacketizer()
        AUDIO_TRACK -> AacRtpPacketizer().takeIf { audioEnabled() }
        else -> null
    }

    override fun onSessionCreated(session: RtspSession) {
        sessions.add(session)
    }

    override fun onSessionStarted(session: RtspSession) {
        if (session.trackName == VIDEO_TRACK) keyFrameRequester?.invoke()
    }

    override fun onSessionClosed(session: RtspSession) {
        sessions.remove(session)
    }

    companion object {
        const val DEFAULT_PORT = 8554
        const val DEFAULT_STREAM_NAME = "live"
        const val AUDIO_SAMPLE_RATE = 48_000
        const val AUDIO_CHANNELS = 1
        private const val VIDEO_TRACK = "track0"
        private const val AUDIO_TRACK = "track1"
        private const val AUDIO_CLOCK_HZ = AUDIO_SAMPLE_RATE.toLong()
    }
}
