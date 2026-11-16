package io.github.wltbagent.rtspcamera.rtp

/**
 * Splits Annex-B byte streams (00 00 01 / 00 00 00 01 start codes) into
 * individual NAL units. Encoded NAL payloads never contain 00 00 01 sequences
 * (emulation prevention), so a linear scan is unambiguous.
 */
object AnnexB {

    fun split(data: ByteArray): List<ByteArray> {
        if (data.size < 4) return emptyList()

        // Locate every start code; record the index of its first 0x00 byte and
        // the offset just past it (where the NAL header begins).
        val codeStart = ArrayList<Int>()
        val nalStart = ArrayList<Int>()
        var i = 0
        while (i < data.size - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                codeStart.add(if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i)
                nalStart.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        if (codeStart.isEmpty()) return emptyList()

        val out = ArrayList<ByteArray>(codeStart.size)
        for (n in codeStart.indices) {
            val begin = nalStart[n]
            val end = if (n + 1 < codeStart.size) codeStart[n + 1] else data.size
            // Strip trailing_zero_8bits before the next start code.
            var e = end
            while (e > begin && data[e - 1] == 0.toByte()) e--
            if (e > begin) out.add(data.copyOfRange(begin, e))
        }
        return out
    }

    /** NAL header nal_unit_type (RFC 6184 Table 1). */
    fun nalType(nalu: ByteArray): Int = (nalu[0].toInt() and 0x1F)

    const val TYPE_IDR = 5
    const val TYPE_SPS = 7
    const val TYPE_PPS = 8
}
