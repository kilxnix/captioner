package com.sheltron.captioner.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode an .m4a file to 16 kHz mono Float32 PCM as Whisper expects.
 *
 * The file was produced by our own AudioEncoder, so the input is already 16 kHz mono.
 * For safety, we average channels if we see more than one and drop every Nth sample
 * if the rate is higher than 16 kHz. Upsampling is not supported — if the file is
 * under 16 kHz, we zero-pad (won't happen with our own encoder).
 */
object AudioDecoder {

    private const val TARGET_RATE = 16_000

    fun decodeToFloat16k(file: File): FloatArray {
        if (!file.exists() || file.length() == 0L) return FloatArray(0)

        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        // Find an audio track.
        var audioTrack = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrack = i
                format = f
                break
            }
        }
        if (audioTrack < 0 || format == null) {
            extractor.release()
            return FloatArray(0)
        }
        extractor.selectTrack(audioTrack)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val outputShorts = ArrayList<Short>((file.length() / 2).toInt().coerceAtLeast(1024))

        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val input = codec.getInputBuffer(inIdx)
                    val sampleSize = if (input != null) extractor.readSampleData(input, 0) else -1
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* keep polling */ }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignore */ }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null && bufferInfo.size > 0) {
                        buf.position(bufferInfo.offset)
                        buf.limit(bufferInfo.offset + bufferInfo.size)
                        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (sb.hasRemaining()) outputShorts.add(sb.get())
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEos = true
                    }
                }
            }
        }

        try { codec.stop() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
        extractor.release()

        return toFloat16kMono(outputShorts.toShortArray(), sampleRate, channelCount)
    }

    private fun toFloat16kMono(shorts: ShortArray, rate: Int, channels: Int): FloatArray {
        // Downmix to mono first.
        val mono: ShortArray = if (channels <= 1) shorts
        else {
            val out = ShortArray(shorts.size / channels)
            var i = 0
            var j = 0
            while (i + channels <= shorts.size) {
                var sum = 0
                for (c in 0 until channels) sum += shorts[i + c]
                out[j++] = (sum / channels).toShort()
                i += channels
            }
            out
        }

        // Resample (nearest-neighbor — good enough for speech, fast).
        val resampled: ShortArray = when {
            rate == TARGET_RATE -> mono
            rate <= 0 -> mono
            else -> {
                val outLen = (mono.size.toLong() * TARGET_RATE / rate).toInt()
                val out = ShortArray(outLen)
                for (i in 0 until outLen) {
                    val srcIdx = (i.toLong() * rate / TARGET_RATE).toInt().coerceAtMost(mono.size - 1)
                    out[i] = mono[srcIdx]
                }
                out
            }
        }

        val floats = FloatArray(resampled.size)
        for (i in resampled.indices) floats[i] = resampled[i] / 32768f
        return floats
    }
}
