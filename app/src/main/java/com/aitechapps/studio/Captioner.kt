package com.aitechapps.studio

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Word-timed captions from an offline speech model (Vosk).
 *
 * The Vosk library is optional at build time, so it is used through reflection: if the APK was built
 * without it the app still runs, and this class explains how to get it. Models are ordinary folders
 * imported once from a .zip into the app's private storage; nothing is ever downloaded.
 */
object Captioner {
    class Word(val w: String, val s: Double, val e: Double)

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").also { it.mkdirs() }

    fun engineAvailable(): Boolean = try {
        Class.forName("org.vosk.Model"); Class.forName("org.vosk.Recognizer"); true
    } catch (_: Throwable) { false }

    fun listModels(ctx: Context): List<String> =
        (modelsDir(ctx).listFiles() ?: emptyArray()).filter { it.isDirectory && findModelRoot(it) != null }.map { it.name }.sorted()

    /** A Vosk model folder is the one that holds the "am" and "conf" directories. */
    private fun findModelRoot(dir: File, depth: Int = 0): File? {
        if (File(dir, "am").isDirectory && File(dir, "conf").isDirectory) return dir
        if (depth >= 2) return null
        for (c in dir.listFiles() ?: return null) if (c.isDirectory) findModelRoot(c, depth + 1)?.let { return it }
        return null
    }

    /** Unpacks a model .zip into private storage and returns the model's name. */
    fun importZip(ctx: Context, uri: Uri, fallbackName: String, onProgress: (String) -> Unit): String {
        val base = fallbackName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").ifEmpty { "model" }
        var name = base
        val root = modelsDir(ctx)
        var n = 2
        while (File(root, name).exists()) { name = "$base-$n"; n++ }
        val dest = File(root, name)
        val destCanon = dest.canonicalPath
        dest.mkdirs()
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Could not open the model file")
            ZipInputStream(input.buffered()).use { zip ->
                var count = 0
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(dest, e.name)
                    if (!out.canonicalPath.startsWith(destCanon + File.separator) && out.canonicalPath != destCanon) {
                        throw IOException("The zip contains an unsafe path")
                    }
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fo ->
                        while (true) { val r = zip.read(buf); if (r < 0) break; fo.write(buf, 0, r) }
                    }
                    if (++count % 20 == 0) onProgress("Unpacking model ($count files)")
                }
            }
            if (findModelRoot(dest) == null) throw IOException("That zip is not a Vosk model (no am/ and conf/ folders inside).")
            return name
        } catch (t: Throwable) {
            dest.deleteRecursively()
            throw t
        }
    }

    fun transcribe(ctx: Context, modelName: String, voice: File, cancelled: () -> Boolean): List<Word> {
        if (!engineAvailable()) {
            throw IOException("This build has no offline speech engine. Use \"Script timing\" captions, or rebuild with Vosk (see the README).")
        }
        val dir = File(modelsDir(ctx), modelName)
        val root = findModelRoot(dir) ?: throw IOException("Speech model \"$modelName\" was not found. Import it again.")
        val pcm = AudioIO.mono16k(voice, 600.0) ?: throw IOException("The voice-over could not be read.")

        val modelCls = Class.forName("org.vosk.Model")
        val recCls = Class.forName("org.vosk.Recognizer")
        val model = modelCls.getConstructor(String::class.java).newInstance(root.absolutePath)
        val rec = recCls.getConstructor(modelCls, java.lang.Float.TYPE).newInstance(model, 16000f)
        try {
            recCls.getMethod("setWords", java.lang.Boolean.TYPE).invoke(rec, true)
            val accept = recCls.getMethod("acceptWaveForm", ByteArray::class.java, Integer.TYPE)
            val getResult = recCls.getMethod("getResult")
            val getFinal = recCls.getMethod("getFinalResult")
            val words = ArrayList<Word>()
            fun collect(json: String) {
                val arr = JSONObject(json).optJSONArray("result") ?: return
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    words.add(Word(o.getString("word"), o.getDouble("start"), o.getDouble("end")))
                }
            }
            val chunk = 8000
            var pos = 0
            val tmp = ByteArray(chunk)
            while (pos < pcm.size) {
                if (cancelled()) throw ExportCancelled()
                val n = minOf(chunk, pcm.size - pos)
                System.arraycopy(pcm, pos, tmp, 0, n)
                val sentenceEnd = accept.invoke(rec, tmp, n) as Boolean
                if (sentenceEnd) collect(getResult.invoke(rec) as String)
                pos += n
            }
            collect(getFinal.invoke(rec) as String)
            return words
        } finally {
            try { recCls.getMethod("close").invoke(rec) } catch (_: Throwable) { }
            try { modelCls.getMethod("close").invoke(model) } catch (_: Throwable) { }
        }
    }
}
