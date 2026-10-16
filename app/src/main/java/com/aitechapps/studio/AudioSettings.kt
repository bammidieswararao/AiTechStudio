package com.aitechapps.studio

import org.json.JSONObject

/** One sound-effect cue from the timeline (seconds from the start of the video). */
data class Cue(
    val t: Double,
    val type: String,
    val dur: Double = 0.0,
    val freq: Double = 0.0,
    val up: Boolean = true,
    val gain: Double = 1.0
)

data class MusicOpts(val gain: Double, val start: Double, val duck: Boolean)
data class VoiceOpts(val gain: Double, val start: Double, val fx: String)

/** Everything the offline mixer needs. Pure data, so the mixer can be tested on a plain JVM. */
data class AudioSettings(
    val enabled: Boolean = true,
    val quality: String = "high",
    val sfxGain: Double = 0.9,
    val bass: Double = 0.0,
    val air: Double = 0.0,
    val space: Double = 0.2,
    val width: Double = 0.0,
    val punch: Double = 0.3,
    val cues: List<Cue> = emptyList(),
    val music: MusicOpts? = null,
    val voice: VoiceOpts? = null
) {
    companion object {
        fun fromJson(o: JSONObject?): AudioSettings {
            if (o == null) return AudioSettings(enabled = false)
            val m = o.optJSONObject("master")
            val cues = ArrayList<Cue>()
            o.optJSONArray("cues")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    cues.add(
                        Cue(
                            t = c.optDouble("t", 0.0),
                            type = c.optString("type", ""),
                            dur = c.optDouble("dur", 0.0),
                            freq = c.optDouble("freq", 0.0),
                            up = c.optBoolean("up", true),
                            gain = c.optDouble("gain", 1.0)
                        )
                    )
                }
            }
            val mu = o.optJSONObject("music")?.let {
                MusicOpts(it.optDouble("gain", 0.5), it.optDouble("start", 0.0), it.optBoolean("duck", true))
            }
            val vo = o.optJSONObject("voice")?.let {
                VoiceOpts(it.optDouble("gain", 1.0), it.optDouble("start", 0.0), it.optString("fx", "studio"))
            }
            return AudioSettings(
                enabled = o.optBoolean("enabled", true),
                quality = o.optString("quality", "high"),
                sfxGain = o.optDouble("sfxGain", 0.9),
                bass = m?.optDouble("bass", 0.0) ?: 0.0,
                air = m?.optDouble("air", 0.0) ?: 0.0,
                space = m?.optDouble("space", 0.2) ?: 0.2,
                width = m?.optDouble("width", 0.0) ?: 0.0,
                punch = m?.optDouble("punch", 0.3) ?: 0.3,
                cues = cues,
                music = mu,
                voice = vo
            )
        }
    }
}
