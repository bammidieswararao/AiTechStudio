package com.aitechapps.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Everything the page can ask the phone to do. The page is the "studio" (design, preview, timeline);
 * this class is the "workshop" (files, voice, encoders). It is exposed to JavaScript as `Android`.
 *
 * Calls arrive on a WebView background thread; calls back into the page are posted to the main thread.
 * Methods whose result the page checks return Boolean (a Unit method shows up as `undefined`).
 */
class StudioBridge(private val act: MainActivity, private val web: WebView) : ExportHost {
    companion object {
        const val REQ_AUDIO = 101
        const val REQ_IMAGE = 102
        const val REQ_VOICE = 103
        const val REQ_BG = 104
        const val REQ_MODEL = 105
        private const val TAG = "AiTechStudio"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cache: File = act.cacheDir
    private val musicPath = File(cache, "music.dat")
    private val voicePath = File(cache, "voice.dat")
    private val bgPath = File(cache, "bg.mp4")

    @Volatile private var cancelFlag = false
    @Volatile private var busy = false
    @Volatile private var bgObj: BgVideo? = null
    @Volatile private var previewStop = false
    private var previewThread: Thread? = null

    private val tts = VoiceTts(act) { json -> js("Studio.onVoices($json)") }

    override val voiceFile: File? get() = if (voicePath.isFile && voicePath.length() > 44) voicePath else null
    override val musicFile: File? get() = if (musicPath.isFile && musicPath.length() > 44) musicPath else null
    override val bgVideo: BgVideo? get() = bgObj

    val isBusy: Boolean get() = busy

    init {
        musicPath.delete(); voicePath.delete(); bgPath.delete()
        main.post { tts.start() }
    }

    // ------------------------------------------------------------------ helpers

    private fun js(code: String) { main.post { try { web.evaluateJavascript(code, null) } catch (_: Exception) { } } }
    private fun q(s: String): String = JSONObject.quote(s)
    private fun error(msg: String) { Log.w(TAG, msg); js("Studio.onError(${q(msg)})") }
    private fun toast(msg: String) { js("Studio.onToast(${q(msg)})") }

    private fun friendly(t: Throwable): String {
        Log.e(TAG, "Export failed", t)
        return when (t) {
            is OutOfMemoryError -> "The phone ran out of memory. Choose a smaller size or a shorter video."
            else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "file"
            }
        } catch (_: Exception) { }
        return uri.lastPathSegment ?: "file"
    }

    private fun copyTo(uri: Uri, dest: File) {
        val input = act.contentResolver.openInputStream(uri) ?: throw IOException("Could not open that file")
        input.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, 1 shl 16) } }
    }

    private fun decodeDataUrl(url: String): ByteArray {
        val comma = url.indexOf(',')
        if (!url.startsWith("data:") || comma < 0) throw IOException("The page sent an image in an unknown format")
        return Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
    }

    private fun pick(req: Int, mime: String, extra: Array<String>? = null): Boolean {
        main.post {
            try {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = mime
                if (extra != null) i.putExtra(Intent.EXTRA_MIME_TYPES, extra)
                act.startActivityForResult(i, req)
            } catch (e: Exception) {
                error("No file picker is available on this phone.")
            }
        }
        return true
    }

    // ------------------------------------------------------------------ simple info

    @JavascriptInterface
    fun deviceInfo(): String = JSONObject()
        .put("model", Build.MODEL ?: "Android")
        .put("sdk", Build.VERSION.SDK_INT)
        .put("vosk", Captioner.engineAvailable())
        .toString()

    @JavascriptInterface
    fun copyText(text: String) {
        main.post {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AiTech Studio", text))
        }
    }

    // ------------------------------------------------------------------ pickers

    @JavascriptInterface fun pickAudio(): Boolean = pick(REQ_AUDIO, "audio/*")
    @JavascriptInterface fun pickVoice(): Boolean = pick(REQ_VOICE, "audio/*")
    @JavascriptInterface fun pickImage(): Boolean = pick(REQ_IMAGE, "image/*")
    @JavascriptInterface fun pickBgVideo(): Boolean = pick(REQ_BG, "video/*")
    @JavascriptInterface fun pickModel(): Boolean =
        pick(REQ_MODEL, "*/*", arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))

    @JavascriptInterface
    fun clearAudio(kind: String) {
        if (kind == "music") musicPath.delete() else if (kind == "voice") voicePath.delete()
    }

    @JavascriptInterface
    fun clearBgVideo() {
        bgObj?.close(); bgObj = null
        bgPath.delete()
    }

    @JavascriptInterface
    fun listModels(): String = JSONArray(Captioner.listModels(act)).toString()

    @JavascriptInterface
    fun listVoices(): String {
        main.post { tts.start() }
        return tts.voicesJson()
    }

    /** Called by MainActivity with the result of a picker. */
    fun onResult(req: Int, uri: Uri?) {
        if (uri == null) return
        io.execute {
            try {
                when (req) {
                    REQ_AUDIO -> importAudio(uri, musicPath, false)
                    REQ_VOICE -> importAudio(uri, voicePath, true)
                    REQ_IMAGE -> importImage(uri)
                    REQ_BG -> importBg(uri)
                    REQ_MODEL -> importModel(uri)
                }
            } catch (t: Throwable) {
                error(friendly(t))
            }
        }
    }

    private fun importAudio(uri: Uri, dest: File, isVoice: Boolean) {
        val name = displayName(uri)
        copyTo(uri, dest)
        val dur = AudioIO.durationSec(dest)
        if (dur <= 0.05) { dest.delete(); throw IOException("\"$name\" could not be read as audio. MP3, M4A, WAV and OGG files work.") }
        val o = JSONObject().put("name", name).put("durationSec", dur)
        if (isVoice) js("Studio.onVoiceReady(${o.put("source", "file")})") else js("Studio.onAudioPicked($o)")
    }

    private fun importImage(uri: Uri) {
        val name = displayName(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("\"$name\" is not a picture the phone can read.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = act.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("\"$name\" could not be opened.")
        val side = max(bmp.width, bmp.height)
        if (side > 1600) {
            val k = 1600.0 / side
            val s = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
            if (s !== bmp) bmp.recycle()
            bmp = s
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bmp.recycle()
        val url = "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        js("Studio.onImagePicked(${q(url)}, ${q(name)})")
    }

    private fun importBg(uri: Uri) {
        val name = displayName(uri)
        toast("Importing the clip…")
        bgObj?.close(); bgObj = null
        copyTo(uri, bgPath)
        val v = try { BgVideo(bgPath) } catch (e: Exception) { bgPath.delete(); throw IOException("\"$name\" is not a video the phone can read. Use an MP4 (H.264).") }
        val dur = v.durationSec
        if (dur <= 0.05 || v.width <= 0) { v.close(); bgPath.delete(); throw IOException("\"$name\" does not look like a video.") }
        val thumb = v.frameDataUrl(min(0.5, dur / 2), 640, 80)
        if (thumb == null) { v.close(); bgPath.delete(); throw IOException("The first frame of \"$name\" could not be read. Use an MP4 (H.264).") }
        bgObj = v
        val o = JSONObject().put("name", name).put("durationSec", dur).put("w", v.width).put("h", v.height)
            .put("thumb", thumb).put("url", "file://" + bgPath.absolutePath)
        js("Studio.onBgVideoPicked($o)")
    }

    private fun importModel(uri: Uri) {
        val name = displayName(uri)
        toast("Unpacking the speech model…")
        val model = Captioner.importZip(act, uri, name) { toast(it) }
        js("Studio.onModels(${JSONArray(Captioner.listModels(act))})")
        toast("Speech model \"$model\" is ready")
    }

    // ------------------------------------------------------------------ voice-over

    @JavascriptInterface
    fun synthVoice(json: String): Boolean {
        val o = JSONObject(json)
        val text = o.optString("text", "").trim()
        if (text.isEmpty()) { error("Type some text for the voice to say."); return false }
        val tmp = File(cache, "voice_new.wav")
        main.post {
            tts.synth(text, o.optString("voice", ""), o.optDouble("rate", 1.0), o.optDouble("pitch", 1.0), tmp) { ok, msg ->
                io.execute {
                    try {
                        if (!ok) { error(msg ?: "The voice-over could not be created."); return@execute }
                        val dur = AudioIO.durationSec(tmp)
                        if (dur <= 0.05) { error("The voice engine made no sound. Check that an offline voice is installed in your phone's text-to-speech settings."); return@execute }
                        voicePath.delete()
                        if (!tmp.renameTo(voicePath)) { copyTo(Uri.fromFile(tmp), voicePath) }
                        js("Studio.onVoiceReady(${JSONObject().put("name", "Phone voice").put("durationSec", dur).put("source", "tts")})")
                    } catch (t: Throwable) {
                        error(friendly(t))
                    }
                }
            }
        }
        return true
    }

    @JavascriptInterface
    fun transcribeVoice(json: String): Boolean {
        val model = JSONObject(json).optString("model", "")
        val vf = voiceFile
        if (vf == null) { error("Create or add a voice-over first."); return false }
        if (!Captioner.engineAvailable()) {
            error("This build has no offline speech engine. Use \"Script timing\" captions, or rebuild with Vosk (see the README).")
            return false
        }
        if (model.isEmpty()) { error("Import a speech model first."); return false }
        io.execute {
            try {
                val words = Captioner.transcribe(act, model, vf) { cancelFlag }
                val arr = JSONArray()
                for (w in words) arr.put(JSONObject().put("w", w.w).put("s", w.s).put("e", w.e))
                js("Studio.onCaptionsReady($arr)")
            } catch (_: ExportCancelled) {
            } catch (t: Throwable) {
                error(friendly(t))
            }
        }
        return true
    }

    // ------------------------------------------------------------------ sound preview

    @JavascriptInterface
    fun previewAudio(json: String): Boolean {
        if (busy) { error("Wait for the export to finish."); return false }
        stopPreviewNow()
        val o = JSONObject(json)
        val dur = o.optDouble("duration", 5.0)
        val settings = AudioSettings.fromJson(o.optJSONObject("audio")).copy(enabled = true)
        previewStop = false
        val t = Thread({
            var track: AudioTrack? = null
            try {
                val mix = AudioEngine.mix(settings, dur, voiceFile, musicFile, AudioIO) { }
                if (mix == null) { error("There is no sound to preview. Pick a sound pack, add music, or create a voice-over."); return@Thread }
                if (previewStop) return@Thread
                val sr = mix.sampleRate
                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(max(minBuf, 32768))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                js("Studio.onPreviewState('playing')")
                track.play()
                var pos = 0
                val chunk = 8192
                while (pos < mix.pcm.size && !previewStop) {
                    val n = min(chunk, mix.pcm.size - pos)
                    val w = track.write(mix.pcm, pos, n)
                    if (w < 0) break
                    pos += w
                }
                val frames = mix.pcm.size / 2
                val deadline = SystemClock.uptimeMillis() + (dur * 1000).toLong() + 3000
                while (!previewStop && track.playbackHeadPosition < frames - 256 && SystemClock.uptimeMillis() < deadline) Thread.sleep(40)
            } catch (e: Throwable) {
                error(friendly(e))
            } finally {
                try { track?.stop() } catch (_: Exception) { }
                try { track?.release() } catch (_: Exception) { }
                js("Studio.onPreviewState('stopped')")
            }
        }, "studio-preview")
        previewThread = t
        t.start()
        return true
    }

    @JavascriptInterface
    fun stopAudioPreview() { stopPreviewNow() }

    private fun stopPreviewNow() {
        previewStop = true
        previewThread?.let { if (it.isAlive && it !== Thread.currentThread()) try { it.join(800) } catch (_: InterruptedException) { } }
        previewThread = null
    }

    // ------------------------------------------------------------------ export plumbing

    private class Pending {
        val latch = CountDownLatch(1)
        @Volatile var bytes: ByteArray? = null
        @Volatile var error: String? = null
    }

    @Volatile private var pending: Pending? = null
    private var lastProgress = 0L

    private fun awaitJs(code: String): ByteArray {
        val p = Pending()
        pending = p
        js(code)
        val start = SystemClock.uptimeMillis()
        while (!p.latch.await(200, TimeUnit.MILLISECONDS)) {
            if (cancelFlag) { pending = null; throw ExportCancelled() }
            if (SystemClock.uptimeMillis() - start > 120_000) {
                pending = null
                throw IOException("The page stopped responding while drawing. Keep the app open on screen during an export.")
            }
        }
        pending = null
        p.error?.let { throw IOException(it) }
        return p.bytes ?: throw IOException("The page returned an empty image")
    }

    override fun requestFrame(index: Int, bgDataUrl: String?): ByteArray =
        awaitJs("Studio.exportFrame($index, ${if (bgDataUrl == null) "null" else q(bgDataUrl)})")

    override fun requestStrip(y: Int, h: Int): ByteArray = awaitJs("Studio.exportStrip($y, $h)")

    override fun progress(p: Double, label: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastProgress < 80 && p < 0.995) return
        lastProgress = now
        js("Studio.onProgress(${String.format(Locale.US, "%.4f", p.coerceIn(0.0, 1.0))}, ${q(label)})")
    }

    override fun isCancelled(): Boolean = cancelFlag

    @JavascriptInterface
    fun onFrame(index: Int, dataUrl: String) {
        val p = pending ?: return
        try { p.bytes = decodeDataUrl(dataUrl) } catch (e: Exception) { p.error = e.message }
        p.latch.countDown()
    }

    @JavascriptInterface
    fun onStrip(y: Int, h: Int, dataUrl: String) {
        val p = pending ?: return
        try { p.bytes = decodeDataUrl(dataUrl) } catch (e: Exception) { p.error = e.message }
        p.latch.countDown()
    }

    @JavascriptInterface
    fun onJsError(msg: String) {
        val p = pending
        if (p != null) { p.error = msg; p.latch.countDown() } else error(msg)
    }

    @JavascriptInterface
    fun cancelExport() { cancelFlag = true }

    @Synchronized
    private fun begin(): Boolean {
        if (busy) return false
        busy = true
        cancelFlag = false
        stopPreviewNow()
        act.runOnUiThread { act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        return true
    }

    private fun end() {
        busy = false
        pending = null
        act.runOnUiThread { act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    private fun runExport(name: String, json: String, job: (JSONObject) -> ExportResult) {
        if (!begin()) { error("Another export is already running."); return }
        Thread({
            try {
                val r = job(JSONObject(json))
                js("Studio.onDone(${r.toJson()})")
            } catch (_: ExportCancelled) {
                js("Studio.onCancelled()")
            } catch (t: Throwable) {
                error(friendly(t))
            } finally {
                end()
            }
        }, name).start()
    }

    @JavascriptInterface
    fun startVideoExport(json: String) = runExport("studio-video-export", json) { VideoExporter.run(act, it, this) }

    @JavascriptInterface
    fun startPngExport(json: String) = runExport("studio-png-export", json) { PngExporter.run(act, it, this) }

    // ------------------------------------------------------------------ open / share

    @JavascriptInterface
    fun openUri(uri: String, mime: String) {
        main.post {
            try {
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(Uri.parse(uri), mime)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                act.startActivity(i)
            } catch (e: Exception) {
                toast("No app on this phone can open that file. Find it in your gallery or files app.")
            }
        }
    }

    @JavascriptInterface
    fun shareUri(uri: String, mime: String) {
        main.post {
            try {
                val u = Uri.parse(uri)
                val i = Intent(Intent.ACTION_SEND)
                i.type = mime
                i.putExtra(Intent.EXTRA_STREAM, u)
                i.clipData = ClipData.newRawUri("", u)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                act.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                toast("Sharing is not available right now.")
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun release() {
        cancelFlag = true
        previewStop = true
        try { tts.shutdown() } catch (_: Exception) { }
        bgObj?.close(); bgObj = null
        io.shutdown()
    }
}
