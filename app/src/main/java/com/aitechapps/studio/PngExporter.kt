package com.aitechapps.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.IOException
import kotlin.math.min

/**
 * Exports a print-size PNG (flex banner, poster, card...). The page draws the picture in horizontal
 * strips; each strip is decoded and streamed into [PngWriter] row by row, so memory use depends on
 * the strip, not on the full image.
 */
object PngExporter {

    fun run(ctx: Context, o: JSONObject, host: ExportHost): ExportResult {
        val w = o.getInt("width")
        val h = o.getInt("height")
        val dpi = o.optDouble("dpi", 72.0)
        val alpha = o.optBoolean("alpha")
        val strip = o.optInt("stripHeight", 256).coerceIn(8, 1024)
        val name = o.optString("fileName", "AiTech_image")
        if (w < 1 || h < 1) throw IOException("Image size is not valid")

        host.progress(0.0, "Starting")
        val target = MediaSaver.open(ctx, MediaSaver.Kind.IMAGE, name, "png", "image/png", false)
        var writer: PngWriter? = null
        try {
            writer = PngWriter(target.outputStream(), w, h, dpi, alpha)
            val row = IntArray(w)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false
            }
            var y = 0
            while (y < h) {
                if (host.isCancelled()) throw ExportCancelled()
                val sh = min(strip, h - y)
                val bytes = host.requestStrip(y, sh)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?: throw IOException("A strip of the image could not be read (rows $y-${y + sh}). Try a smaller size.")
                try {
                    if (bmp.width != w || bmp.height != sh) throw IOException("Strip came back as ${bmp.width}x${bmp.height}, expected ${w}x$sh")
                    for (r in 0 until sh) {
                        bmp.getPixels(row, 0, w, 0, r, w, 1)
                        writer.writeRow(row)
                    }
                } finally {
                    bmp.recycle()
                }
                y += sh
                host.progress(0.02 + 0.96 * y / h, "Row $y of $h")
            }
            host.progress(0.99, "Finishing the PNG")
            writer.finish()
            return target.finish()
        } catch (t: Throwable) {
            writer?.abort()
            target.abort()
            throw t
        }
    }
}
