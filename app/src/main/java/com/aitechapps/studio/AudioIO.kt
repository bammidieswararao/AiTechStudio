package com.aitechapps.studio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Reads audio files (WAV parsed by hand, everything else through the phone's own decoders). */
object AudioIO : Decoder {

    private class WavInfo(val tag: Int, val channels: Int, val sampleRate: Int, val bits: Int, val dataPos: Long, val dataLen: Long)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    /** Parses the RIFF header. Phone TTS engines sometimes write a wrong data length, so it is clamped to the file. */
    private fun wavInfo(raf: RandomAccessFile): WavInfo? {
        val hdr = ByteArray(12)
        raf.seek(0)
        if (raf.read(hdr) < 12) return null
        if (String(hdr, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(hdr, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        var tag = 1; var ch = 1; var sr = 44100; var bits = 16
        var haveFmt = false
        val c8 = ByteArray(8)
        while (raf.read(c8) == 8) {
            val id = String(c8, 0, 4, Charsets.ISO_8859_1)
            val size = u32(c8, 4)
            val pos = raf.filePointer
            if (id == "fmt " && size >= 16) {
                val f = ByteArray(min(size, 40L).toInt())
                raf.readFully(f)
                tag = u16(f, 0); ch = u16(f, 2); sr = u32(f, 4).toInt(); bits = u16(f, 14)
                if (tag == 0xFFFE && f.size >= 26) tag = u16(f, 24)
                haveFmt = true
            } else if (id == "data") {
                if (!haveFmt) return null
                var len = size
                val avail = raf.length() - pos
                if (len <= 0L || len > avail) len = avail
                return WavInfo(tag, ch, sr, bits, pos, len)
            }
            val next = pos + size + (size and 1L)
            if (next >= raf.length()) break
            raf.seek(next)
        }
        return null
    }

    fun durationSec(file: File): Double {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val w = wavInfo(raf)
                if (w != null && w.sampleRate > 0 && w.channels > 0 && w.bits > 0) {
                    return w.dataLen.toDouble() / (w.channels * (w.bits / 8)) / w.sampleRate
                }
            }
        } catch (_: Exception) { }
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()
                if (ms != null && ms > 0) return ms / 1000.0
            } finally { r.release() }
        } catch (_: Exception) { }
        try {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true && f.containsKey(MediaFormat.KEY_DURATION)) {
                        return f.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
                    }
                }
            } finally { ex.release() }
        } catch (_: Exception) { }
        return 0.0
    }

    override fun decode(file: File, targetSr: Int, maxSec: Double): Pcm? {
        val raw = decodeWav(file, maxSec) ?: decodeCodec(file, maxSec) ?: return null
        return resample(raw, targetSr)
    }

    private fun decodeWav(file: File, maxSec: Double): Pcm? {
        RandomAccessFile(file, "r").use { raf ->
            val w = wavInfo(raf) ?: return null
            if (w.channels < 1 || w.sampleRate < 1000) return null
            val bytesPer = w.bits / 8
            if (bytesPer !in 1..4) return null
            val isFloat = w.tag == 3
            if (w.tag != 1 && !isFloat) return null
            if (isFloat && bytesPer != 4) return null
            val frameBytes = w.channels * bytesPer
            val totalFrames = min(w.dataLen / frameBytes, (maxSec * w.sampleRate).toLong()).toInt()
            if (totalFrames <= 0) return null
            val l = FloatArray(totalFrames)
            val r = FloatArray(totalFrames)
            val chunkFrames = 16384
            val buf = ByteArray(chunkFrames * frameBytes)
            raf.seek(w.dataPos)
            var done = 0
            while (done < totalFrames) {
                val want = min(chunkFrames, totalFrames - done)
                raf.readFully(buf, 0, want * frameBytes)
                for (f in 0 until want) {
                    val base = f * frameBytes
                    val a = sampleAt(buf, base, w.bits, isFloat)
                    val b = if (w.channels > 1) sampleAt(buf, base + bytesPer, w.bits, isFloat) else a
                    l[done + f] = a
                    r[done + f] = b
                }
                done += want
            }
            return Pcm(l, r, w.sampleRate)
        }
    }

    private fun sampleAt(b: ByteArray, o: Int, bits: Int, isFloat: Boolean): Float = when {
        isFloat -> java.lang.Float.intBitsToFloat(
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        )
        bits == 8 -> ((b[o].toInt() and 0xFF) - 128) / 128f
        bits == 16 -> ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort() / 32768f
        bits == 24 -> (((b[o].toInt() and 0xFF) shl 8) or ((b[o + 1].toInt() and 0xFF) shl 16) or (b[o + 2].toInt() shl 24)) / 2147483648f
        else -> ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or (b[o + 3].toInt() shl 24)) / 2147483648f
    }

    private fun decodeCodec(file: File, maxSec: Double): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var enc = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var acc = ShortArray(1 shl 20)
            var accLen = 0
            var inputDone = false
            var outputDone = false
            var idleLoops = 0
            while (!outputDone) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)
                        val sz = if (ib != null) ex.readSampleData(ib, 0) else -1
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    idleLoops = 0
                } else if (oi >= 0) {
                    idleLoops = 0
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        val count: Int
                        if (enc == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = ob.asFloatBuffer()
                            count = fb.remaining()
                            if (accLen + count > acc.size) acc = acc.copyOf(max(acc.size * 2, accLen + count))
                            for (k in 0 until count) {
                                val v = fb.get()
                                acc[accLen + k] = (max(-1f, min(1f, v)) * 32767f).toInt().toShort()
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            count = sb.remaining()
                            if (accLen + count > acc.size) acc = acc.copyOf(max(acc.size * 2, accLen + count))
                            sb.get(acc, accLen, count)
                        }
                        accLen += count
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    if (ch > 0 && accLen.toDouble() / ch / max(1, sr) >= maxSec) outputDone = true
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    if (++idleLoops > 500) outputDone = true // decoder stalled after end of input
                }
            }
            if (ch < 1 || accLen < ch) return null
            var frames = accLen / ch
            val cap = (maxSec * sr).toLong()
            if (frames > cap) frames = cap.toInt()
            val l = FloatArray(frames)
            val r = FloatArray(frames)
            for (f in 0 until frames) {
                val a = acc[f * ch] / 32768f
                l[f] = a
                r[f] = if (ch > 1) acc[f * ch + 1] / 32768f else a
            }
            return Pcm(l, r, sr)
        } catch (_: Exception) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            ex.release()
        }
    }

    /** Catmull-Rom resampler; low-passes first when reducing the rate to keep aliasing out. */
    fun resample(p: Pcm, target: Int): Pcm {
        if (p.sampleRate == target || p.frames < 4) return Pcm(p.l, p.r, if (p.frames < 4) p.sampleRate else target)
        var l = p.l
        var r = p.r
        if (target < p.sampleRate) {
            l = l.copyOf(); r = r.copyOf()
            val fc = target * 0.45
            for (pass in 0 until 2) {
                Biquad.lowpass(p.sampleRate, fc).run(l)
                Biquad.lowpass(p.sampleRate, fc).run(r)
            }
        }
        val ratio = p.sampleRate.toDouble() / target
        val n = max(1, floor((l.size - 1) / ratio).toInt())
        val ol = FloatArray(n)
        val orr = FloatArray(n)
        val last = l.size - 1
        for (i in 0 until n) {
            val pos = i * ratio
            val i1 = pos.toInt()
            val t = (pos - i1).toFloat()
            val i0 = max(0, i1 - 1)
            val i2 = min(last, i1 + 1)
            val i3 = min(last, i1 + 2)
            ol[i] = cubic(l[i0], l[i1], l[i2], l[i3], t)
            orr[i] = cubic(r[i0], r[i1], r[i2], r[i3], t)
        }
        return Pcm(ol, orr, target)
    }

    private fun cubic(y0: Float, y1: Float, y2: Float, y3: Float, t: Float): Float {
        val a = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
        val b = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c = -0.5f * y0 + 0.5f * y2
        return ((a * t + b) * t + c) * t + y1
    }

    /** 16 kHz mono PCM16 little-endian, the input format of the offline speech recogniser. */
    fun mono16k(file: File, maxSec: Double): ByteArray? {
        val p = decode(file, 16000, maxSec) ?: return null
        val out = ByteArray(p.frames * 2)
        for (i in 0 until p.frames) {
            val v = (0.5f * (p.l[i] + p.r[i]) * 32767f).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
