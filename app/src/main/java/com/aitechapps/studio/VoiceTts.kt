package com.aitechapps.studio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Voice-over from the phone's own text-to-speech engine. Only voices that are installed on the
 * phone and do not need a network connection are offered, so it works in aeroplane mode.
 * Every call must be made on the main thread.
 */
class VoiceTts(private val ctx: Context, private val onVoicesChanged: (String) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initStarted = false
    private var voicesJson = "[]"
    private var pending: Runnable? = null

    private class Job(val file: File, val onDone: (Boolean, String?) -> Unit)

    private val jobs = HashMap<String, Job>()
    private var counter = 0

    fun start() {
        if (initStarted) return
        initStarted = true
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    tts?.setOnUtteranceProgressListener(listener)
                    voicesJson = buildVoices()
                    onVoicesChanged(voicesJson)
                    pending?.run()
                    pending = null
                } else {
                    ready = false
                    initStarted = false
                    pending = null
                }
            }
        } catch (_: Exception) {
            initStarted = false
        }
    }

    fun voicesJson(): String = voicesJson

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { finish(utteranceId, true, null) }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { finish(utteranceId, false, "The voice engine could not create the voice-over.") }
        override fun onError(utteranceId: String?, errorCode: Int) {
            finish(utteranceId, false, "The voice engine could not create the voice-over (error $errorCode).")
        }
    }

    private fun finish(id: String?, ok: Boolean, msg: String?) {
        val job = synchronized(jobs) { jobs.remove(id) } ?: return
        job.onDone(ok, msg)
    }

    private fun isOffline(v: Voice): Boolean =
        !v.isNetworkConnectionRequired && !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

    private fun buildVoices(): String {
        val arr = JSONArray()
        try {
            val list = (tts?.voices ?: emptySet<Voice>()).filter { isOffline(it) }
                .sortedWith(compareBy({ it.locale.getDisplayName(Locale.ENGLISH) }, { it.name }))
            for (v in list.take(250)) {
                val o = JSONObject()
                o.put("name", v.name)
                val q = if (v.quality >= Voice.QUALITY_HIGH) " · high quality" else ""
                o.put("label", v.locale.getDisplayName(Locale.ENGLISH) + q + " · " + v.name)
                arr.put(o)
            }
        } catch (_: Exception) { }
        return arr.toString()
    }

    /** Creates a WAV file of [text]. [onDone] runs on a TTS callback thread. */
    fun synth(text: String, voiceName: String, rate: Double, pitch: Double, out: File, onDone: (Boolean, String?) -> Unit) {
        val run = Runnable {
            val t = tts
            if (t == null) { onDone(false, "No text-to-speech engine is installed on this phone."); return@Runnable }
            try {
                if (voiceName.isNotEmpty()) {
                    t.voices?.firstOrNull { it.name == voiceName }?.let { t.voice = it }
                }
                t.setSpeechRate(rate.toFloat().coerceIn(0.3f, 2.5f))
                t.setPitch(pitch.toFloat().coerceIn(0.4f, 2.0f))
                val max = TextToSpeech.getMaxSpeechInputLength() - 10
                val clean = text.trim().let { if (it.length > max) it.substring(0, max) else it }
                if (clean.isEmpty()) { onDone(false, "Type some text for the voice to say."); return@Runnable }
                if (out.exists()) out.delete()
                val id = "studio-" + (++counter)
                synchronized(jobs) { jobs[id] = Job(out, onDone) }
                val r = t.synthesizeToFile(clean, Bundle(), out, id)
                if (r != TextToSpeech.SUCCESS) {
                    synchronized(jobs) { jobs.remove(id) }
                    onDone(false, "The voice engine refused the request. Check that a text-to-speech voice is installed.")
                }
            } catch (e: Exception) {
                onDone(false, e.message ?: "Voice-over failed")
            }
        }
        if (ready) run.run()
        else {
            pending = run
            start()
        }
    }

    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) { }
        try { tts?.shutdown() } catch (_: Exception) { }
        tts = null
        ready = false
        initStarted = false
    }
}
