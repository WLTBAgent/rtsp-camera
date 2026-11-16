package io.github.wltbagent.rtspcamera.encode

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Microphone capture (AudioRecord) piped into a hardware AAC-LC encoder.
 * Two threads: capture feeds PCM input buffers, drain forwards encoded AUs.
 * Presentation times derive from the sample count, so the RTP clock advances
 * at exactly the sample rate; A/V alignment happens via RTCP sender reports.
 */
class AudioEncoder(
    private val sampleRate: Int = 48_000,
    private val channels: Int = 1,
    private val bitrateBps: Int = 96_000,
) {

    var frameListener: ((EncodedFrame) -> Unit)? = null
    var errorListener: ((Exception) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var captureThread: Thread? = null
    private var drainThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val record = createAudioRecord()
            audioRecord = record
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            codec = enc
            captureThread = thread(name = "audio-capture") { captureLoop(record, enc) }
            drainThread = thread(name = "audio-drain") { drainLoop(enc) }
        } catch (e: Exception) {
            running.set(false)
            errorListener?.invoke(e)
            stop()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        captureThread?.join(1_000)
        drainThread?.join(1_000)
        captureThread = null
        drainThread = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }

    @SuppressLint("MissingPermission") // Caller requests RECORD_AUDIO before enabling audio.
    private fun createAudioRecord(): AudioRecord {
        val channelMask = if (channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 4, READ_BUFFER_BYTES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        return record
    }

    private fun captureLoop(record: AudioRecord, enc: MediaCodec) {
        val readBuffer = ByteArray(READ_BUFFER_BYTES)
        // Accumulate PCM and feed the encoder exactly AAC-frame-sized chunks
        // (1024 samples). Variable-size input buffers make MediaCodec
        // interpolate output timestamps inside a buffer, which collides with
        // the queued pts of the next buffer and produces RTP timestamp jitter.
        val chunkBytes = SAMPLES_PER_AAC_FRAME * 2 * channels
        val chunk = ByteArray(chunkBytes)
        var buffered = 0
        var totalSamples = 0L
        try {
            record.startRecording()
            while (running.get()) {
                val read = record.read(readBuffer, 0, readBuffer.size)
                if (read <= 0) continue
                var offset = 0
                while (offset < read) {
                    val toCopy = minOf(read - offset, chunkBytes - buffered)
                    System.arraycopy(readBuffer, offset, chunk, buffered, toCopy)
                    buffered += toCopy
                    offset += toCopy
                    if (buffered == chunkBytes) {
                        queueChunk(enc, chunk, totalSamples)
                        totalSamples += SAMPLES_PER_AAC_FRAME
                        buffered = 0
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) errorListener?.invoke(e)
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun queueChunk(enc: MediaCodec, chunk: ByteArray, totalSamples: Long) {
        val index = enc.dequeueInputBuffer(10_000)
        if (index < 0) return // Encoder is backed up; drop this chunk, pts stays monotonic.
        val input = enc.getInputBuffer(index) ?: return
        input.clear()
        input.put(chunk)
        val ptsUs = totalSamples * 1_000_000L / sampleRate
        enc.queueInputBuffer(index, 0, chunk.size, ptsUs, 0)
    }

    private fun drainLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val index = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    index >= 0 -> {
                        val buffer = enc.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val out = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.get(out)
                            frameListener?.invoke(EncodedFrame(out, info.flags, info.presentationTimeUs))
                        }
                        enc.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) errorListener?.invoke(e)
        }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 4_096
        const val SAMPLES_PER_AAC_FRAME = 1024
    }
}
