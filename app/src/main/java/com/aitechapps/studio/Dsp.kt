package com.aitechapps.studio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Small, dependency-free DSP toolkit used by the offline audio engine:
 * RBJ biquad filters, a Freeverb-style reverb and a feed-forward compressor.
 */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + z1
        z1 = b1 * xd - a1 * y + z2
        z2 = b2 * xd - a2 * y
        return y.toFloat()
    }

    fun run(buf: FloatArray) {
        for (i in buf.indices) buf[i] = process(buf[i])
    }

    companion object {
        private const val LP = 0
        private const val HP = 1
        private const val BP = 2
        private const val PEAK = 3
        private const val LOW_SHELF = 4
        private const val HIGH_SHELF = 5

        fun lowpass(sr: Int, f: Double, q: Double = 0.7071) = make(sr, f, q, 0.0, LP)
        fun highpass(sr: Int, f: Double, q: Double = 0.7071) = make(sr, f, q, 0.0, HP)
        fun bandpass(sr: Int, f: Double, q: Double = 1.0) = make(sr, f, q, 0.0, BP)
        fun peaking(sr: Int, f: Double, q: Double, gainDb: Double) = make(sr, f, q, gainDb, PEAK)
        fun lowShelf(sr: Int, f: Double, gainDb: Double) = make(sr, f, 0.7071, gainDb, LOW_SHELF)
        fun highShelf(sr: Int, f: Double, gainDb: Double) = make(sr, f, 0.7071, gainDb, HIGH_SHELF)

        private fun make(sr: Int, f0In: Double, q: Double, gainDb: Double, type: Int): Biquad {
            val f0 = min(max(f0In, 10.0), sr * 0.49)
            val w0 = 2.0 * PI * f0 / sr
            val cw = cos(w0)
            val sw = sin(w0)
            val alpha = sw / (2.0 * q)
            val amp = 10.0.pow(gainDb / 40.0)
            val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
            when (type) {
                LP -> {
                    b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                HP -> {
                    b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                BP -> {
                    b0 = alpha; b1 = 0.0; b2 = -alpha
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                PEAK -> {
                    b0 = 1 + alpha * amp; b1 = -2 * cw; b2 = 1 - alpha * amp
                    a0 = 1 + alpha / amp; a1 = -2 * cw; a2 = 1 - alpha / amp
                }
                LOW_SHELF -> {
                    val beta = 2.0 * sqrt(amp) * alpha
                    b0 = amp * ((amp + 1) - (amp - 1) * cw + beta)
                    b1 = 2 * amp * ((amp - 1) - (amp + 1) * cw)
                    b2 = amp * ((amp + 1) - (amp - 1) * cw - beta)
                    a0 = (amp + 1) + (amp - 1) * cw + beta
                    a1 = -2 * ((amp - 1) + (amp + 1) * cw)
                    a2 = (amp + 1) + (amp - 1) * cw - beta
                }
                else -> { // HIGH_SHELF
                    val beta = 2.0 * sqrt(amp) * alpha
                    b0 = amp * ((amp + 1) + (amp - 1) * cw + beta)
                    b1 = -2 * amp * ((amp - 1) + (amp + 1) * cw)
                    b2 = amp * ((amp + 1) + (amp - 1) * cw - beta)
                    a0 = (amp + 1) - (amp - 1) * cw + beta
                    a1 = 2 * ((amp - 1) - (amp + 1) * cw)
                    a2 = (amp + 1) - (amp - 1) * cw - beta
                }
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/** Freeverb: 8 parallel damped combs + 4 series all-passes per channel. */
class Freeverb(sr: Int, room: Float, damp: Float) {
    private class Comb(size: Int) {
        val buf = FloatArray(max(1, size))
        var idx = 0
        var store = 0f
        fun process(inp: Float, fb: Float, damp: Float): Float {
            val out = buf[idx]
            store = out * (1f - damp) + store * damp
            buf[idx] = inp + store * fb
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private class Allpass(size: Int) {
        val buf = FloatArray(max(1, size))
        var idx = 0
        fun process(inp: Float): Float {
            val b = buf[idx]
            val out = -inp + b
            buf[idx] = inp + b * 0.5f
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val apTuning = intArrayOf(556, 441, 341, 225)
    private val scale = sr / 44100.0
    private val combL = Array(8) { Comb((combTuning[it] * scale).toInt()) }
    private val combR = Array(8) { Comb(((combTuning[it] + 23) * scale).toInt()) }
    private val apL = Array(4) { Allpass((apTuning[it] * scale).toInt()) }
    private val apR = Array(4) { Allpass(((apTuning[it] + 23) * scale).toInt()) }
    private val fb = room * 0.28f + 0.7f
    private val dmp = damp * 0.4f

    /** Adds `wet` x reverb(inp) into outL / outR. */
    fun process(inp: FloatArray, outL: FloatArray, outR: FloatArray, wet: Float) {
        val n = min(inp.size, min(outL.size, outR.size))
        for (i in 0 until n) {
            val x = inp[i] * 0.015f
            var l = 0f
            var r = 0f
            for (c in 0 until 8) {
                l += combL[c].process(x, fb, dmp)
                r += combR[c].process(x, fb, dmp)
            }
            for (a in 0 until 4) {
                l = apL[a].process(l)
                r = apR[a].process(r)
            }
            outL[i] += l * wet * 3f
            outR[i] += r * wet * 3f
        }
    }
}

object Dsp {
    fun dbToLin(db: Double): Double = 10.0.pow(db / 20.0)
    fun linToDb(x: Double): Double = 20.0 * log10(max(x, 1e-9))

    /** Gentle soft limiter: transparent below 0.9, smoothly saturates above. */
    fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.9f) return x
        val s = if (x < 0f) -1f else 1f
        return s * (0.9f + 0.1f * tanh((a - 0.9f) / 0.1f))
    }

    /** Feed-forward peak compressor applied to a stereo pair in place. */
    fun compress(
        l: FloatArray, r: FloatArray, sr: Int,
        thresholdDb: Double, ratio: Double, attackMs: Double, releaseMs: Double, makeupDb: Double
    ) {
        val atk = exp(-1.0 / (attackMs * 0.001 * sr))
        val rel = exp(-1.0 / (releaseMs * 0.001 * sr))
        var env = 0.0
        val slope = 1.0 - 1.0 / ratio
        val n = min(l.size, r.size)
        for (i in 0 until n) {
            val level = max(abs(l[i]), abs(r[i])).toDouble()
            val c = if (level > env) atk else rel
            env = c * env + (1.0 - c) * level
            val over = linToDb(env) - thresholdDb
            val gainDb = (if (over > 0) -over * slope else 0.0) + makeupDb
            val g = dbToLin(gainDb).toFloat()
            l[i] *= g
            r[i] *= g
        }
    }

    /** Same compressor for a single channel. */
    fun compressMono(
        x: FloatArray, sr: Int,
        thresholdDb: Double, ratio: Double, attackMs: Double, releaseMs: Double, makeupDb: Double
    ) {
        val atk = exp(-1.0 / (attackMs * 0.001 * sr))
        val rel = exp(-1.0 / (releaseMs * 0.001 * sr))
        var env = 0.0
        val slope = 1.0 - 1.0 / ratio
        for (i in x.indices) {
            val level = abs(x[i]).toDouble()
            val c = if (level > env) atk else rel
            env = c * env + (1.0 - c) * level
            val over = linToDb(env) - thresholdDb
            val gainDb = (if (over > 0) -over * slope else 0.0) + makeupDb
            x[i] *= dbToLin(gainDb).toFloat()
        }
    }

    fun peak(l: FloatArray, r: FloatArray): Float {
        var p = 0f
        for (i in l.indices) {
            val a = abs(l[i]); if (a > p) p = a
            val b = abs(r[i]); if (b > p) p = b
        }
        return p
    }

    fun rms(l: FloatArray, r: FloatArray): Double {
        if (l.isEmpty()) return 0.0
        var s = 0.0
        for (i in l.indices) s += (l[i] * l[i] + r[i] * r[i]).toDouble()
        return sqrt(s / (2.0 * l.size))
    }
}
