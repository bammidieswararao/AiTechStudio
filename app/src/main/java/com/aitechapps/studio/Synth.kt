package com.aitechapps.studio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Procedural sound-effect synthesiser. Every effect is generated from maths and a seeded
 * random generator, so the phone needs no sample files and the same cue always sounds the same.
 * Each effect is peak-normalised to 0.9 so the cue "gain" in the timeline is predictable.
 */
object Synth {
    private fun noise(r: Random): Float = r.nextFloat() * 2f - 1f

    private fun finish(buf: FloatArray, fadeOutSec: Double, sr: Int): FloatArray {
        val fo = max(1, min(buf.size, (fadeOutSec * sr).toInt()))
        for (i in 0 until fo) {
            val k = i.toFloat() / fo
            buf[buf.size - 1 - i] *= k
        }
        val fi = min(buf.size, (0.002 * sr).toInt())
        for (i in 0 until fi) buf[i] *= i.toFloat() / max(1, fi)
        var p = 0f
        for (v in buf) { val a = abs(v); if (a > p) p = a }
        if (p > 1e-6f) { val g = 0.9f / p; for (i in buf.indices) buf[i] *= g }
        return buf
    }

    /** Returns a mono effect, or null for an unknown type. */
    fun render(type: String, sr: Int, dur: Double, freq: Double, up: Boolean, seed: Long): FloatArray? = when (type) {
        "impact" -> impact(sr, seed)
        "whoosh" -> whoosh(sr, if (dur > 0.05) dur else 0.7, up, seed)
        "riser" -> riser(sr, if (dur > 0.1) dur else 1.0, seed)
        "tick" -> tick(sr, seed)
        "ding" -> ding(sr, if (freq > 50) freq else 880.0)
        "sparkle" -> sparkle(sr, seed)
        "zap" -> zap(sr, seed)
        "pop" -> pop(sr)
        "pad" -> pad(sr, if (dur > 0.5) dur else 4.0)
        else -> null
    }

    private fun impact(sr: Int, seed: Long): FloatArray {
        val n = (1.7 * sr).toInt()
        val out = FloatArray(n)
        val r = Random(seed)
        var phase = 0.0
        var lp = 0f
        var lp2 = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 38.0 + 95.0 * exp(-t * 16.0)
            phase += 2.0 * PI * f / sr
            val body = (sin(phase) * exp(-t * 3.0)).toFloat()
            val nz = noise(r)
            lp += 0.12f * (nz - lp)
            val crack = lp * exp(-t * 26.0).toFloat()
            lp2 += 0.01f * (nz - lp2)
            val rumble = lp2 * exp(-t * 2.2).toFloat() * 6f
            val click = if (t < 0.003) nz * 0.7f else 0f
            out[i] = body * 0.95f + crack * 1.1f + rumble * 0.35f + click
        }
        return finish(out, 0.08, sr)
    }

    /** Band-passed noise whose centre frequency and loudness follow the given curves. */
    private fun sweep(sr: Int, dur: Double, seed: Long, q: Double, fcAt: (Double) -> Double, ampAt: (Double) -> Double): FloatArray {
        val n = max(1, (dur * sr).toInt())
        val out = FloatArray(n)
        val r = Random(seed)
        var low = 0.0
        var band = 0.0
        for (i in 0 until n) {
            val p = i.toDouble() / n
            val fc = min(fcAt(p), 6500.0)
            val f = 2.0 * sin(PI * fc / sr)
            val x = noise(r).toDouble()
            low += f * band
            val high = x - low - q * band
            band += f * high
            out[i] = (band * ampAt(p)).toFloat()
        }
        return out
    }

    private fun whoosh(sr: Int, dur: Double, up: Boolean, seed: Long): FloatArray {
        val out = sweep(sr, dur, seed, 0.4,
            { p -> if (up) 250.0 * 22.0.pow(p * p) else 5500.0 * (0.05).pow(p.pow(0.7)) },
            { p -> sin(PI * p).pow(1.6) })
        return finish(out, 0.03, sr)
    }

    private fun riser(sr: Int, dur: Double, seed: Long): FloatArray {
        val out = sweep(sr, dur, seed, 0.45, { p -> 200.0 * 30.0.pow(p) }, { p -> p.pow(2.2) })
        var phase = 0.0
        for (i in out.indices) {
            val p = i.toDouble() / out.size
            val f = 120.0 * 2.0.pow(p * 3.5)
            phase += 2.0 * PI * f / sr
            out[i] = out[i] * 0.8f + (sin(phase) * 0.3 * p.pow(2.0)).toFloat()
        }
        return finish(out, 0.02, sr)
    }

    private fun tick(sr: Int, seed: Long): FloatArray {
        val n = (0.06 * sr).toInt()
        val out = FloatArray(n)
        val r = Random(seed)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val tone = (sin(2.0 * PI * 2200.0 * t) * exp(-t * 90.0)).toFloat() * 0.6f
            val click = if (t < 0.002) noise(r) * 0.5f else 0f
            out[i] = tone + click
        }
        return finish(out, 0.01, sr)
    }

    private fun ding(sr: Int, f: Double): FloatArray {
        val n = (1.8 * sr).toInt()
        val out = FloatArray(n)
        val ratios = doubleArrayOf(1.0, 2.756, 5.404)
        val amps = doubleArrayOf(1.0, 0.45, 0.2)
        val decays = doubleArrayOf(3.0, 5.5, 9.0)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var s = 0.0
            for (k in 0 until 3) s += sin(2.0 * PI * f * ratios[k] * t) * amps[k] * exp(-t * decays[k])
            out[i] = (s * min(1.0, t / 0.002)).toFloat()
        }
        return finish(out, 0.1, sr)
    }

    private fun sparkle(sr: Int, seed: Long): FloatArray {
        val n = (0.9 * sr).toInt()
        val out = FloatArray(n)
        val r = Random(seed)
        for (b in 0 until 10) {
            val start = (r.nextDouble() * 0.5 * sr).toInt()
            val f = 3000.0 + r.nextDouble() * 5000.0
            val decay = 25.0 + r.nextDouble() * 35.0
            val amp = 0.25 + r.nextDouble() * 0.25
            var i = start
            while (i < n) {
                val t = (i - start).toDouble() / sr
                val e = exp(-t * decay)
                if (e < 0.002) break
                out[i] += (sin(2.0 * PI * f * t) * e * amp).toFloat()
                i++
            }
        }
        return finish(out, 0.05, sr)
    }

    private fun zap(sr: Int, seed: Long): FloatArray {
        val n = (0.28 * sr).toInt()
        val out = FloatArray(n)
        val r = Random(seed)
        var hold = 0f
        var holdLeft = 0
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            if (holdLeft <= 0) { hold = noise(r); holdLeft = 20 + r.nextInt(280) }
            holdLeft--
            val crushed = (hold * 4f).toInt() / 4f
            val f = 1200.0 * exp(-t * 7.0) + 200.0
            phase += 2.0 * PI * f / sr
            val gate = if ((i / 220) % 3 == 0 && r.nextFloat() > 0.7f) 0f else 1f
            out[i] = ((crushed * 0.6f + sin(phase).toFloat() * 0.5f) * exp(-t * 9.0).toFloat()) * gate
        }
        return finish(out, 0.02, sr)
    }

    private fun pop(sr: Int): FloatArray {
        val n = (0.12 * sr).toInt()
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 700.0 * exp(-t * 28.0) + 120.0
            phase += 2.0 * PI * f / sr
            out[i] = (sin(phase) * exp(-t * 35.0)).toFloat()
        }
        return finish(out, 0.01, sr)
    }

    /** Slow evolving A-minor pad used as an ambient bed for the cinematic pack. */
    private fun pad(sr: Int, dur: Double): FloatArray {
        val n = max(1, (dur * sr).toInt())
        val out = FloatArray(n)
        val notes = doubleArrayOf(110.0, 130.81, 164.81, 220.0, 329.63)
        val attack = min(1.5, dur * 0.3)
        val release = min(1.8, dur * 0.3)
        val phases = DoubleArray(notes.size * 2)
        var lp = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val env = min(1.0, t / attack) * min(1.0, (dur - t) / release).coerceAtLeast(0.0)
            var s = 0.0
            for (k in notes.indices) {
                val vib = 1.0 + 0.002 * sin(2.0 * PI * (0.15 + k * 0.03) * t)
                phases[k * 2] += 2.0 * PI * notes[k] * vib / sr
                phases[k * 2 + 1] += 2.0 * PI * notes[k] * 1.004 / sr
                s += sin(phases[k * 2]) + 0.6 * sin(phases[k * 2 + 1]) + 0.2 * sin(phases[k * 2] * 2.0)
            }
            val x = (s * env * 0.12).toFloat()
            lp += 0.12f * (x - lp)
            out[i] = lp
        }
        var p = 0f
        for (v in out) { val a = abs(v); if (a > p) p = a }
        if (p > 1e-6f) { val g = 0.9f / p; for (i in out.indices) out[i] *= g }
        return out
    }
}
