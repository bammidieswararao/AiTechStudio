package com.aitechapps.studio

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Tiny read-only provider so Open / Share work for files saved in the app folder on Android 8-9
 * (Android 10+ shares MediaStore addresses directly). It avoids pulling in androidx just for this.
 */
class StudioFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun resolve(uri: Uri): File? {
        val ctx = context ?: return null
        val seg = uri.pathSegments
        if (seg.size != 2) return null
        val type = when (seg[0]) {
            "movies" -> Environment.DIRECTORY_MOVIES
            "pictures" -> Environment.DIRECTORY_PICTURES
            else -> return null
        }
        val base = ctx.getExternalFilesDir(type) ?: ctx.filesDir
        val f = File(base, seg[1])
        // refuse anything that escapes the folder ("..")
        if (!f.canonicalPath.startsWith(base.canonicalPath + File.separator)) return null
        return if (f.isFile) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Read-only")
        val f = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        val row = c.newRow()
        for (col in cols) {
            when (col) {
                OpenableColumns.DISPLAY_NAME -> row.add(f.name)
                OpenableColumns.SIZE -> row.add(f.length())
                else -> row.add(null)
            }
        }
        return c
    }

    override fun getType(uri: Uri): String? {
        val n = uri.lastPathSegment ?: return null
        return when {
            n.endsWith(".mp4", true) -> "video/mp4"
            n.endsWith(".png", true) -> "image/png"
            else -> "application/octet-stream"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
