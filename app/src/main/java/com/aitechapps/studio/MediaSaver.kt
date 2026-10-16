package com.aitechapps.studio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Saves finished exports where the gallery and file managers can see them, without any storage
 * permission: MediaStore (Movies/AiTechStudio, Pictures/AiTechStudio) on Android 10+, and the app's
 * own external folder (shared through [StudioFileProvider]) on Android 8-9.
 */
object MediaSaver {
    enum class Kind { VIDEO, IMAGE }

    const val FOLDER = "AiTechStudio"

    fun authority(ctx: Context): String = ctx.packageName + ".files"

    class Target internal constructor(
        private val ctx: Context,
        private val kind: Kind,
        val uri: Uri,
        private val file: File?,
        private var pfd: ParcelFileDescriptor?,
        private val whereText: String,
        private val mime: String
    ) {
        private var stream: OutputStream? = null

        /** For MediaMuxer: a seekable descriptor. */
        fun fileDescriptor(): FileDescriptor = pfd?.fileDescriptor ?: throw IOException("No descriptor")

        /** For streaming writers such as [PngWriter]. */
        fun outputStream(): OutputStream {
            stream?.let { return it }
            val s: OutputStream = if (file != null) FileOutputStream(file)
            else ctx.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the output file")
            val b = java.io.BufferedOutputStream(s, 1 shl 16)
            stream = b
            return b
        }

        private fun closeAll() {
            try { stream?.close() } catch (_: Exception) { }
            stream = null
            try { pfd?.close() } catch (_: Exception) { }
            pfd = null
        }

        fun finish(): ExportResult {
            closeAll()
            if (file == null && Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, v, null, null)
            }
            return ExportResult(whereText, uri.toString(), mime)
        }

        fun abort() {
            closeAll()
            try {
                if (file != null) file.delete() else ctx.contentResolver.delete(uri, null, null)
            } catch (_: Exception) { }
        }
    }

    fun open(ctx: Context, kind: Kind, baseName: String, ext: String, mime: String, wantDescriptor: Boolean): Target {
        val clean = baseName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').ifEmpty { "AiTech_export" }
        val name = "$clean.$ext"
        val video = kind == Kind.VIDEO
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + FOLDER)
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = ctx.contentResolver.insert(collection, values) ?: throw IOException("Could not create the file in your gallery")
            val pfd = if (wantDescriptor) ctx.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Could not open the output file") else null
            val where = (if (video) "Movies" else "Pictures") + "/" + FOLDER + "/" + name
            return Target(ctx, kind, uri, null, pfd, "Saved to $where", mime)
        }
        val type = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val dir = ctx.getExternalFilesDir(type) ?: ctx.filesDir
        dir.mkdirs()
        var f = File(dir, name)
        var n = 1
        while (f.exists()) { f = File(dir, "$clean-$n.$ext"); n++ }
        val uri = Uri.parse("content://" + authority(ctx) + "/" + (if (video) "movies" else "pictures") + "/" + Uri.encode(f.name))
        val pfd = if (wantDescriptor) ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE) else null
        return Target(ctx, kind, uri, f, pfd, "Saved in the app folder: " + f.absolutePath.substringAfter("/Android/", f.absolutePath), mime)
    }
}
