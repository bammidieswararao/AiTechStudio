package com.aitechapps.studio

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Decoded stereo audio as floats in [-1, 1]. */
class Pcm(val l: FloatArray, val r: FloatArray, val sampleRate: Int) {
    val frames: Int get() = l.size
}

/** Turns an audio file into [Pcm] at a given rate. The Android implementation is [AudioIO]. */
interface Decoder {
    fun decode(file: File, targetSr: Int, maxSec: Double): Pcm?
}

/**
 * Offline mastering chain. Everything is computed from the timeline the design engine produced:
 *
 *   sound effects ─┐
 *   voice-over ────┼─> reverb send -> bass/air EQ -> stereo width -> punch compressor
 *   music (ducked) ┘                  -> loudness normalise (-16 dBFS RMS, peak <= -1 dBFS) -> soft clip
 *
 * It has no Android dependencies except through [Decoder], so it can run in a plain JVM test.
 */
object AudioEngine {
    class Mix(
        /** Interleaved stereo, 16-bit. */
        val pcm: ShortArray,
        val sampleRate: Int,
        val bitrate: Int,
        val peakDb: Double,
        val rmsDb: Double
    )

    fun sampleRateFor(quality: String): Int = if (quality == "standard") 44100 else 48000

    fun bitrateFor(quality: String): Int = when (quality) {
        "standard" -> 128_000
        "studio" -> 256_000
        else -> 192_000
    }

    fun hasAudio(s: AudioSettings, voiceFile: File?, musicFile: File?): Boolean =
        s.enabled && (s.cues.isNotEmpty() || (s.voice != null && voiceFile != null) || (s.music != null && musicFile != null))

    private fun sendFor(type: String): Float = when (type) {
        "impact" -> 0.55f
        "riser" -> 0.5f
        "whoosh" -> 0.45f
        "ding" -> 0.7f
        "sparkle" -> 0.6f
        "tick" -> 0.15f
        "zap" -> 0.3f
        "pop" -> 0.25f
        "pad" -> 0.2f
        else -> 0.3f
    }

    private fun panFor(type: String, idx: Int): Double = when (type) {
        "impact", "riser", "pad" -> 0.0
        else -> sin(idx * 2.399963) * 0.35
    }

    fun mix(
        s: AudioSettings,
        durationSec: Double,
        voiceFile: File?,
        musicFile: File?,
        dec: Decoder,
        progress: ((String) -> Unit)? = null
    ): Mix? {
        if (!hasAudio(s, voiceFile, musicFile)) return null
        val sr = sampleRateFor(s.quality)
        val n = max(1, (durationSec * sr).roundToInt())
        val outL = FloatArray(n)
        val outR = FloatArray(n)
        val send = FloatArray(n)

        // ---- sound effects -------------------------------------------------------------
        progress?.invoke("Building sound effects")
        for ((idx, c) in s.cues.withIndex()) {
            val seed = c.type.hashCode().toLong() * 31L + idx * 7919L + 17L
            val buf = Synth.render(c.type, sr, c.dur, c.freq, c.up, seed) ?: continue
            val start = (c.t * sr).roundToInt()
            if (start < 0 || start >= n) continue
            val g = (c.gain * s.sfxGain).toFloat()
            val ang = (panFor(c.type, idx) + 1.0) * 0.5 * (PI / 2.0)
            val gl = (cos(ang) * 1.4142135).toFloat() * g
            val gr = (sin(ang) * 1.4142135).toFloat() * g
            val snd = sendFor(c.type) * g
            val len = min(buf.size, n - start)
            for (i in 0 until len) {
                val v = buf[i]
                val p = start + i
                outL[p] += v * gl
                outR[p] += v * gr
                send[p] += v * snd
            }
        }

        // ---- voice-over ----------------------------------------------------------------
        var duckEnv: FloatArray? = null
        val vo = s.voice
        val mu = s.music
        if (vo != null && voiceFile != null) {
            progress?.invoke("Polishing voice-over")
            val p = dec.decode(voiceFile, sr, 600.0)
            if (p != null && p.frames > 0) {
                val vs = max(0, (vo.start * sr).roundToInt())
                if (vs < n) {
                    val tailSec = when (vo.fx) { "hall" -> 3.2; "echo" -> 2.0; else -> 0.2 }
                    val total = p.frames + (tailSec * sr).toInt()
                    val mono = FloatArray(total)
                    var pk = 0f
                    for (i in 0 until p.frames) {
                        val v = 0.5f * (p.l[i] + p.r[i])
                        mono[i] = v
                        val a = abs(v); if (a > pk) pk = a
                    }
                    if (pk > 1e-5f) { val g = 0.8f / pk; for (i in 0 until p.frames) mono[i] *= g }
                    val (vl, vr) = processVoice(mono, sr, vo.fx)
                    val g = vo.gain.toFloat()
                    val len = min(total, n - vs)
                    for (i in 0 until len) {
                        outL[vs + i] += vl[i] * g
                        outR[vs + i] += vr[i] * g
                        send[vs + i] += 0.5f * (vl[i] + vr[i]) * g * 0.12f
                    }
                    if (mu != null && mu.duck) {
                        val env = FloatArray(n)
                        var e = 0.0
                        val atk = kotlin.math.exp(-1.0 / (0.020 * sr))
                        val rel = kotlin.math.exp(-1.0 / (0.350 * sr))
                        for (i in 0 until n) {
                            val x = if (i >= vs && i - vs < p.frames) abs(mono[i - vs]).toDouble() * g else 0.0
                            val c = if (x > e) atk else rel
                            e = c * e + (1.0 - c) * x
                            env[i] = e.toFloat()
                        }
                        duckEnv = env
                    }
                }
            }
        }

        // ---- music ---------------------------------------------------------------------
        if (mu != null && musicFile != null) {
            progress?.invoke("Preparing music")
            val p = dec.decode(musicFile, sr, max(0.0, mu.start) + durationSec + 1.0)
            if (p != null && p.frames > 16) {
                var skip = (max(0.0, mu.start) * sr).roundToInt()
                if (skip >= p.frames - sr / 2) skip = 0
                val region = p.frames - skip
                val looping = region < n
                val seam = (0.02 * sr).toInt()
                val g = (mu.gain * 0.8).toFloat()
                val fin = (0.4 * sr).toInt()
                val fout = (min(1.2, durationSec * 0.3) * sr).toInt()
                for (pos in 0 until n) {
                    val rp = if (looping) pos % region else pos
                    var f = g
                    if (looping && seam > 0) {
                        if (rp < seam) f *= rp.toFloat() / seam
                        else if (region - rp < seam) f *= (region - rp).toFloat() / seam
                    }
                    if (pos < fin) f *= pos.toFloat() / fin
                    if (n - pos < fout) f *= (n - pos).toFloat() / fout
                    val de = duckEnv
                    if (de != null) f *= (1.0 - 0.65 * min(1.0, de[pos] / 0.04)).toFloat()
                    val si = skip + rp
                    outL[pos] += p.l[si] * f
                    outR[pos] += p.r[si] * f
                }
            }
        }
        duckEnv = null

        // ---- master --------------------------------------------------------------------
        progress?.invoke("Mastering")
        val wet = (s.space.coerceIn(0.0, 1.0) * 0.8).toFloat()
        if (wet > 0.001f) Freeverb(sr, 0.82f, 0.45f).process(send, outL, outR, wet)

        val hp1 = Biquad.highpass(sr, 28.0); val hp2 = Biquad.highpass(sr, 28.0)
        for (i in 0 until n) { outL[i] = hp1.process(outL[i]); outR[i] = hp2.process(outR[i]) }

        val bassDb = s.bass.coerceIn(-12.0, 12.0)
        if (abs(bassDb) > 0.05) { Biquad.lowShelf(sr, 100.0, bassDb).run(outL); Biquad.lowShelf(sr, 100.0, bassDb).run(outR) }
        val airDb = s.air.coerceIn(-12.0, 12.0)
        if (abs(airDb) > 0.05) { Biquad.highShelf(sr, 9000.0, airDb).run(outL); Biquad.highShelf(sr, 9000.0, airDb).run(outR) }

        val w = s.width.coerceIn(-0.5, 1.5)
        if (abs(w) > 0.01) {
            for (i in 0 until n) {
                val m = 0.5f * (outL[i] + outR[i])
                val sd = 0.5f * (outL[i] - outR[i]) * (1.0 + w).toFloat()
                outL[i] = m + sd
                outR[i] = m - sd
            }
        }

        val punch = s.punch.coerceIn(0.0, 1.0)
        if (punch > 0.02) Dsp.compress(outL, outR, sr, -16.0 - 8.0 * punch, 1.6 + 2.4 * punch, 10.0, 140.0, 0.0)

        // loudness: aim for -16 dBFS RMS (measured on the non-silent parts) but never above -1 dBFS peak
        val peak = Dsp.peak(outL, outR)
        if (peak < 1e-5f) return null
        val rms = activeRms(outL, outR, sr)
        val targetRms = Dsp.dbToLin(-16.0)
        val gRms = if (rms > 1e-6) targetRms / rms else 1.0
        val gPeak = 0.891 / peak
        val gain = min(min(gRms, gPeak), 8.0).toFloat()
        for (i in 0 until n) { outL[i] *= gain; outR[i] *= gain }

        val fi = max(1, (0.005 * sr).toInt())
        val fo = max(1, (min(0.25, durationSec * 0.25) * sr).toInt())
        val out = ShortArray(n * 2)
        var pk = 0f
        for (i in 0 until n) {
            var f = 1f
            if (i < fi) f = i.toFloat() / fi
            if (n - i < fo) f = min(f, (n - i).toFloat() / fo)
            val a = Dsp.softClip(outL[i] * f)
            val b = Dsp.softClip(outR[i] * f)
            val aa = abs(a); val bb = abs(b)
            if (aa > pk) pk = aa
            if (bb > pk) pk = bb
            out[2 * i] = (a * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            out[2 * i + 1] = (b * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        val finalRms = activeRms(outL, outR, sr)
        return Mix(out, sr, bitrateFor(s.quality), Dsp.linToDb(pk.toDouble()), Dsp.linToDb(finalRms))
    }

    private fun activeRms(l: FloatArray, r: FloatArray, sr: Int): Double {
        val blk = max(1, sr / 10)
        var sum = 0.0
        var cnt = 0L
        var i = 0
        while (i < l.size) {
            val e = min(l.size, i + blk)
            var s = 0.0
            for (k in i until e) s += (l[k] * l[k] + r[k] * r[k]).toDouble()
            val ms = s / (2.0 * (e - i))
            if (ms > 3e-6) { sum += s; cnt += 2L * (e - i) }
            i = e
        }
        return if (cnt > 0) sqrt(sum / cnt) else 0.0
    }

    /** Voice effects. `x` is mono and already includes room for any reverb / echo tail. */
    private fun processVoice(x: FloatArray, sr: Int, fx: String): Pair<FloatArray, FloatArray> {
        fun studio(buf: FloatArray) {
            Biquad.highpass(sr, 85.0).run(buf)
            Biquad.lowShelf(sr, 140.0, 1.5).run(buf)
            Biquad.peaking(sr, 3200.0, 1.0, 2.5).run(buf)
            Dsp.compressMono(buf, sr, -22.0, 3.0, 8.0, 120.0, 3.0)
            Biquad.highShelf(sr, 9000.0, 2.0).run(buf)
        }
        when (fx) {
            "clean" -> {
                Biquad.highpass(sr, 70.0).run(x)
                return Pair(x, x.copyOf())
            }
            "radio" -> {
                Biquad.highpass(sr, 380.0).run(x)
                Biquad.highpass(sr, 380.0).run(x)
                Biquad.lowpass(sr, 3300.0).run(x)
                Biquad.lowpass(sr, 3300.0).run(x)
                Biquad.peaking(sr, 1500.0, 1.2, 4.0).run(x)
                Dsp.compressMono(x, sr, -26.0, 6.0, 4.0, 90.0, 6.0)
                val k = 2.5f
                val norm = tanh(k.toDouble()).toFloat()
                for (i in x.indices) x[i] = tanh((x[i] * k).toDouble()).toFloat() / norm
                return Pair(x, x.copyOf())
            }
            "robot" -> {
                val dry = x.copyOf()
                val w = 2.0 * PI * 70.0 / sr
                for (i in x.indices) x[i] = (x[i] * sin(w * i)).toFloat() * 0.85f + dry[i] * 0.15f
                val d = max(1, (0.009 * sr).toInt())
                val y = x.copyOf()
                for (i in d until y.size) y[i] = x[i] + x[i - d] * 0.45f
                Biquad.lowpass(sr, 6500.0).run(y)
                Dsp.compressMono(y, sr, -20.0, 3.0, 5.0, 80.0, 2.0)
                return Pair(y, y.copyOf())
            }
            "hall" -> {
                studio(x)
                val l = x.copyOf()
                val r = x.copyOf()
                Freeverb(sr, 0.9f, 0.4f).process(x, l, r, 0.45f)
                return Pair(l, r)
            }
            "echo" -> {
                studio(x)
                val l = x.copyOf()
                val r = x.copyOf()
                val d = (0.27 * sr).toInt()
                val lp = Biquad.lowpass(sr, 4500.0)
                val fb = FloatArray(x.size)
                for (i in x.indices) {
                    val prev = if (i >= d) fb[i - d] else 0f
                    fb[i] = lp.process(x[i] + prev * 0.42f)
                }
                // ping-pong: echo delayed once for the right channel, then the repeats alternate
                for (i in x.indices) {
                    if (i >= d) {
                        val e = fb[i - d] * 0.5f
                        if (((i / d) and 1) == 0) l[i] += e else r[i] += e
                    }
                }
                return Pair(l, r)
            }
            "warm" -> {
                Biquad.highpass(sr, 70.0).run(x)
                Biquad.lowShelf(sr, 200.0, 4.0).run(x)
                Biquad.peaking(sr, 400.0, 0.9, 1.5).run(x)
                Biquad.lowpass(sr, 6500.0).run(x)
                Dsp.compressMono(x, sr, -22.0, 2.5, 12.0, 160.0, 2.0)
                return Pair(x, x.copyOf())
            }
            else -> { // "studio" and anything unknown
                studio(x)
                return Pair(x, x.copyOf())
            }
        }
    }
}
