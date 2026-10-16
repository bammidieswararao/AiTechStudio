package com.aitechapps.studio

import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Streaming PNG encoder: rows go in one at a time and compressed data goes straight to the output,
 * so a 30,000 px wide flex-banner never needs the whole picture in memory.
 * Writes 8-bit RGB or RGBA, a physical size chunk (pHYs) so print shops see the right DPI,
 * and an sRGB chunk. Each row picks the best of the None / Sub / Up filters.
 */
class PngWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    dpi: Double,
    private val alpha: Boolean
) {
    private val bpp = if (alpha) 4 else 3
    private val rowBytes = width * bpp
    private val deflater = Deflater(5)
    private val crc = CRC32()
    private val idat = ByteArray(1 shl 16)
    private var idatLen = 0
    private var prev = ByteArray(rowBytes)
    private var cur = ByteArray(rowBytes)
    private val none = ByteArray(rowBytes + 1)
    private val sub = ByteArray(rowBytes + 1)
    private val up = ByteArray(rowBytes + 1)
    private val tmp = ByteArray(1 shl 16)
    private var rows = 0
    private var closed = false

    init {
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        put32(ihdr, 0, width); put32(ihdr, 4, height)
        ihdr[8] = 8
        ihdr[9] = if (alpha) 6 else 2
        chunk("IHDR", ihdr, 0, 13)
        val ppm = (dpi / 0.0254).roundToInt()
        val phys = ByteArray(9)
        put32(phys, 0, ppm); put32(phys, 4, ppm); phys[8] = 1
        chunk("pHYs", phys, 0, 9)
        chunk("sRGB", byteArrayOf(0), 0, 1)
    }

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }

    private fun chunk(type: String, data: ByteArray, off: Int, len: Int) {
        val h = ByteArray(8)
        put32(h, 0, len)
        for (i in 0 until 4) h[4 + i] = type[i].code.toByte()
        out.write(h)
        crc.reset()
        crc.update(h, 4, 4)
        if (len > 0) { out.write(data, off, len); crc.update(data, off, len) }
        val c = ByteArray(4)
        put32(c, 0, crc.value.toInt())
        out.write(c)
    }

    private fun flushIdat() {
        if (idatLen > 0) { chunk("IDAT", idat, 0, idatLen); idatLen = 0 }
    }

    private fun pump(finish: Boolean) {
        while (true) {
            val n = deflater.deflate(tmp)
            if (n > 0) {
                var o = 0
                while (o < n) {
                    val c = minOf(n - o, idat.size - idatLen)
                    System.arraycopy(tmp, o, idat, idatLen, c)
                    idatLen += c; o += c
                    if (idatLen == idat.size) flushIdat()
                }
            }
            if (finish) { if (deflater.finished()) break } else if (n == 0 && deflater.needsInput()) break
        }
    }

    /** `argb` holds one row of Android colour ints (non-premultiplied ARGB). */
    fun writeRow(argb: IntArray) {
        if (closed) throw IOException("PNG already closed")
        if (rows >= height) throw IOException("Too many rows")
        var p = 0
        if (alpha) {
            for (x in 0 until width) {
                val c = argb[x]
                cur[p++] = (c shr 16).toByte(); cur[p++] = (c shr 8).toByte(); cur[p++] = c.toByte(); cur[p++] = (c ushr 24).toByte()
            }
        } else {
            for (x in 0 until width) {
                val c = argb[x]
                cur[p++] = (c shr 16).toByte(); cur[p++] = (c shr 8).toByte(); cur[p++] = c.toByte()
            }
        }
        none[0] = 0; sub[0] = 1; up[0] = 2
        var sNone = 0L; var sSub = 0L; var sUp = 0L
        for (i in 0 until rowBytes) {
            val v = cur[i]
            none[i + 1] = v
            val a = if (i >= bpp) cur[i - bpp].toInt() else 0
            val sv = (v - a).toByte()
            sub[i + 1] = sv
            val uv = (v - prev[i]).toByte()
            up[i + 1] = uv
            sNone += abs(v.toInt()); sSub += abs(sv.toInt()); sUp += abs(uv.toInt())
        }
        val best = if (sNone <= sSub && sNone <= sUp) none else if (sSub <= sUp) sub else up
        deflater.setInput(best, 0, rowBytes + 1)
        pump(false)
        val t = prev; prev = cur; cur = t
        rows++
    }

    fun finish() {
        if (closed) return
        closed = true
        if (rows != height) { deflater.end(); throw IOException("PNG ended after $rows of $height rows") }
        deflater.finish()
        pump(true)
        flushIdat()
        chunk("IEND", ByteArray(0), 0, 0)
        deflater.end()
        out.flush()
    }

    fun abort() {
        closed = true
        try { deflater.end() } catch (_: Exception) { }
    }
}
