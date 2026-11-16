package io.github.wltbagent.rtspcamera.rtp

import java.util.Date

/**
 * Minimal RTCP support: sender reports only (RFC 3550 §6.4.1). Receiver
 * reports from clients are consumed and ignored.
 */
object Rtcp {

    const val PT_SENDER_REPORT = 200
    const val SR_SIZE = 28

    /** Builds a compound-free sender report (single SR packet, no report blocks). */
    fun senderReport(
        ssrc: Long,
        rtpTimestamp: Long,
        packetCount: Long,
        octetCount: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): ByteArray {
        val b = ByteArray(SR_SIZE)
        b[0] = 0x80.toByte() // V=2, P=0, RC=0
        b[1] = PT_SENDER_REPORT.toByte()
        b[2] = 0x00.toByte()
        b[3] = 0x06.toByte() // length = 6 32-bit words after the first
        Rtp.putInt(b, 4, ssrc)

        // NTP timestamp: seconds since 1900 + fraction (32-bit, from ms).
        val ntpSeconds = nowMs / 1000L + NTP_EPOCH_OFFSET_SECONDS
        val ntpFraction = ((nowMs % 1000L) * 0x100000000L / 1000L)
        Rtp.putInt(b, 8, ntpSeconds)
        Rtp.putInt(b, 12, ntpFraction)

        Rtp.putInt(b, 16, rtpTimestamp)
        Rtp.putInt(b, 20, packetCount)
        Rtp.putInt(b, 24, octetCount)
        return b
    }

    private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L
}
