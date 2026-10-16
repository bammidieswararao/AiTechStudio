package com.aitechapps.studio

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An imported background clip (for example one made by LongCat-Video on a GPU machine).
 * Frames are pulled one at a time with MediaMetadataRetriever and handed to the page as JPEG data
 * URLs, so the page can draw them on its canvas without the cross-origin "tainted canvas" problem.
 */
class BgVideo(file: File) : AutoCloseable {
    private val r = MediaMetadataRetriever()
    val durationSec: Double
    val width: Int
    val height: Int

    init {
        try {
            r.setDataSource(file.absolutePath)
            durationSec = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { width = h; height = w } else { width = w; height = h }
        } catch (e: Exception) {
            r.release()
            throw e
        }
    }

    fun frame(tSec: Double, maxSide: Int): Bitmap? {
        val us = (max(0.0, min(tSec, max(0.0, durationSec - 0.02))) * 1_000_000.0).toLong()
        var b = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        val side = max(b.width, b.height)
        if (side > maxSide) {
            val k = maxSide.toDouble() / side
            val nw = max(2, (b.width * k).roundToInt())
            val nh = max(2, (b.height * k).roundToInt())
            val s = Bitmap.createScaledBitmap(b, nw, nh, true)
            if (s !== b) b.recycle()
            b = s
        }
        return b
    }

    fun frameDataUrl(tSec: Double, maxSide: Int, quality: Int = 85): String? {
        val b = frame(tSec, maxSide) ?: return null
        try {
            val bos = ByteArrayOutputStream(1 shl 17)
            b.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } finally {
            b.recycle()
        }
    }

    override fun close() {
        try { r.release() } catch (_: Exception) { }
    }
}
