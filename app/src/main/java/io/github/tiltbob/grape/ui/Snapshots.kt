package io.github.tiltbob.grape.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes a JPEG frame to the phone's Pictures collection. */
object Snapshots {
    private const val ALBUM = "EarDigger"

    /** Returns a human-readable location of the saved file, or null on failure. */
    fun save(context: Context, jpeg: ByteArray): String? {
        val name = "eardigger_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStore(context, name, jpeg)
            else saveToAppPictures(context, name, jpeg)
        }.getOrNull()
    }

    private fun saveToMediaStore(context: Context, name: String, jpeg: ByteArray): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("insert failed")
        resolver.openOutputStream(uri)?.use { it.write(jpeg) } ?: throw IllegalStateException("open failed")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Pictures/$ALBUM/$name"
    }

    private fun saveToAppPictures(context: Context, name: String, jpeg: ByteArray): String {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM)
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(jpeg)
        return file.absolutePath
    }
}
