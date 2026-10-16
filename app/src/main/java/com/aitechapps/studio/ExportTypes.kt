package com.aitechapps.studio

import org.json.JSONObject
import java.io.File

/** Thrown to unwind an export when the person taps Cancel. */
class ExportCancelled : RuntimeException("Export cancelled")

class ExportResult(val where: String, val uri: String, val mime: String) {
    fun toJson(): String = JSONObject().put("where", where).put("uri", uri).put("mime", mime).toString()
}

/** What an export needs from the app around it. Implemented by [StudioBridge]. */
interface ExportHost {
    /** Asks the page to draw frame [index] (optionally over [bgDataUrl]) and returns the JPEG bytes. */
    fun requestFrame(index: Int, bgDataUrl: String?): ByteArray

    /** Asks the page to draw rows [y, y+h) of the print image and returns the PNG bytes. */
    fun requestStrip(y: Int, h: Int): ByteArray

    fun progress(p: Double, label: String)
    fun isCancelled(): Boolean

    val voiceFile: File?
    val musicFile: File?
    val bgVideo: BgVideo?
}
