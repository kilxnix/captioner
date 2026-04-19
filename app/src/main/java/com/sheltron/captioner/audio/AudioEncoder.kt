package com.sheltron.captioner.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Encodes 16-bit PCM mono shorts to AAC-LC inside an .m4a container.
 *
 * Lifecycle: construct → [start] → [encodePcm] N times → [close]. Safe to call [close] at any
 * point; partially-written files are deleted if the muxer never started.
 */
class AudioEncoder(
    private val outputFile: File,
    private val sampleRate: Int = 16_000,
    private val bitRate: Int = 32_000
) : Closeable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var closed = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        codec.start()
    }

    fun encodePcm(shortBuf: ShortArray, count: Int) {
        if (closed || count <= 0) return
        val totalBytes = count * 2
        var fedBytes = 0
        while (fedBytes < totalBytes) {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex < 0) {
                // Encoder busy; drain output and retry so we don't deadlock.
                drainOutput(false)
                continue
            }
            val input = codec.getInputBuffer(inputIndex) ?: continue
            input.clear()
            val writable = min(input.capacity(), totalBytes - fedBytes)
            val tmp = ByteBuffer.allocate(writable).order(ByteOrder.LITTLE_ENDIAN)
            val startShort = fedBytes / 2
            val endShort = startShort + writable / 2
            for (i in startShort until endShort) tmp.putShort(shortBuf[i])
            tmp.flip()
            input.put(tmp)

            val ptsUs = presentationTimeUs
            codec.queueInputBuffer(inputIndex, 0, writable, ptsUs, 0)
            val samplesFed = writable / 2
            presentationTimeUs += 1_000_000L * samplesFed / sampleRate
            fedBytes += writable

            drainOutput(false)
        }
    }

    private fun drainOutput(endOfStream: Boolean) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    // keep polling for final frames
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) return
                    val m = muxer ?: return
                    trackIndex = m.addTrack(codec.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(trackIndex, buf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            val inputIndex = codec.dequeueInputBuffer(100_000)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex, 0, 0, presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                drainOutput(true)
            }
        } catch (_: Throwable) { }
        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        val m = muxer
        if (m != null) {
            if (muxerStarted) {
                try { m.stop() } catch (_: Throwable) {}
            }
            try { m.release() } catch (_: Throwable) {}
            muxer = null
        }
        // Nothing was ever written — don't leave a zero-byte orphan.
        if (!muxerStarted && outputFile.exists()) {
            outputFile.delete()
        }
    }
}
