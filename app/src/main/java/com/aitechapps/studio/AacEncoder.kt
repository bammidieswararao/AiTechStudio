package com.aitechapps.studio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.IOException
import kotlin.math.min

/** One compressed AAC access unit waiting to be written into the MP4. */
class AacSample(val data: ByteArray, val ptsUs: Long, val flags: Int)

/**
 * Encodes the finished mix to AAC-LC in memory. It runs before the video encoder starts, because
 * MediaMuxer needs the audio format up front, and the samples are then interleaved with the video.
 */
object AacEncoder {
    class Result(val format: MediaFormat, val samples: List<AacSample>)

    fun encode(pcm: ShortArray, sampleRate: Int, channels: Int, bitrate: Int, cancelled: () -> Boolean): Result {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val samples = ArrayList<AacSample>()
            var outFormat: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            val frameBytes = 2 * channels
            val totalBytes = pcm.size * 2
            var inPos = 0
            var inputDone = false
            var outputDone = false
            var stall = 0

            while (!outputDone) {
                if (cancelled()) throw ExportCancelled()
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii) ?: throw IOException("Audio encoder gave no input buffer")
                        ib.clear()
                        var n = min(totalBytes - inPos, ib.capacity())
                        n -= n % frameBytes
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, ptsFor(inPos, sampleRate, frameBytes), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            ib.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            ib.asShortBuffer().put(pcm, inPos / 2, n / 2)
                            ib.position(0)
                            codec.queueInputBuffer(ii, 0, n, ptsFor(inPos, sampleRate, frameBytes), 0)
                            inPos += n
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                    stall = 0
                } else if (oi >= 0) {
                    stall = 0
                    val ob = codec.getOutputBuffer(oi)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (ob != null && info.size > 0 && !isConfig) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        ob.get(data)
                        samples.add(AacSample(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME))
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    if (++stall > 800) throw IOException("Audio encoder stopped responding")
                }
            }
            val f = outFormat ?: codec.outputFormat
            return Result(f, samples)
        } finally {
            try { codec.stop() } catch (_: Exception) { }
            try { codec.release() } catch (_: Exception) { }
        }
    }

    private fun ptsFor(bytePos: Int, sr: Int, frameBytes: Int): Long = (bytePos / frameBytes).toLong() * 1_000_000L / sr
}
