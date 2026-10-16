package com.aitechapps.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import org.json.JSONObject
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders the design to an MP4 entirely on the phone:
 * mix and encode the sound first, then for every frame ask the page to draw it, decode the JPEG,
 * paint it into the H.264 encoder's surface with an exact timestamp, and mux video + audio.
 */
object VideoExporter {

    fun run(ctx: Context, o: JSONObject, host: ExportHost): ExportResult {
        var w = o.getInt("width")
        var h = o.getInt("height")
        w -= w % 2; h -= h % 2
        val fps = o.optInt("fps", 30).coerceIn(1, 60)
        val dur = o.getDouble("duration")
        var bitrate = o.optInt("bitrate", 8_000_000)
        val total = max(1, (dur * fps).roundToInt())
        val name = o.optString("fileName", "AiTech_video")
        val bg = if (o.optBoolean("bg")) host.bgVideo else null
        val settings = AudioSettings.fromJson(o.optJSONObject("audio"))

        // --- will this phone's encoder take this size? ---------------------------------
        val probe = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val vc = probe.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            if (!vc.isSizeSupported(w, h)) {
                throw IOException("This phone's video encoder cannot make ${w}x$h. Choose a smaller size, for example 1920x1080.")
            }
            if (!vc.areSizeAndRateSupported(w, h, fps.toDouble())) {
                throw IOException("This phone's video encoder cannot make ${w}x$h at $fps fps. Choose 24 or 30 fps, or a smaller size.")
            }
            bitrate = vc.bitrateRange.clamp(bitrate)
        } finally {
            probe.release()
        }

        // --- sound -----------------------------------------------------------------------
        host.progress(0.0, "Preparing sound")
        var aac: AacEncoder.Result? = null
        if (AudioEngine.hasAudio(settings, host.voiceFile, host.musicFile)) {
            val mix = AudioEngine.mix(settings, dur, host.voiceFile, host.musicFile, AudioIO) { label -> host.progress(0.02, label) }
            if (host.isCancelled()) throw ExportCancelled()
            if (mix != null) {
                host.progress(0.05, "Encoding sound")
                aac = AacEncoder.encode(mix.pcm, mix.sampleRate, 2, mix.bitrate) { host.isCancelled() }
            }
        }

        // --- video -----------------------------------------------------------------------
        val target = MediaSaver.open(ctx, MediaSaver.Kind.VIDEO, name, "mp4", "video/mp4", true)
        var mux: Mp4Mux? = null
        var enc: GlVideoEncoder? = null
        try {
            mux = Mp4Mux(target.fileDescriptor(), aac)
            enc = GlVideoEncoder(w, h, fps, bitrate, mux)
            enc.start()
            val bgSide = min(max(w, h), 1920)
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            for (i in 0 until total) {
                if (host.isCancelled()) throw ExportCancelled()
                var bgUrl: String? = null
                if (bg != null) {
                    val t = if (bg.durationSec > 0.1) (i.toDouble() / fps) % bg.durationSec else 0.0
                    bgUrl = bg.frameDataUrl(t, bgSide)
                }
                val bytes = host.requestFrame(i, bgUrl)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IOException("Frame ${i + 1} could not be read")
                try {
                    enc.drawFrame(bmp, i)
                } finally {
                    bmp.recycle()
                }
                host.progress(0.08 + 0.89 * (i + 1) / total, "Frame ${i + 1} of $total")
            }
            host.progress(0.97, "Finishing the MP4")
            enc.finish()
            enc = null
            mux.finish()
            mux = null
            return target.finish()
        } catch (t: Throwable) {
            try { enc?.abort() } catch (_: Exception) { }
            try { mux?.abort() } catch (_: Exception) { }
            target.abort()
            throw t
        }
    }
}
