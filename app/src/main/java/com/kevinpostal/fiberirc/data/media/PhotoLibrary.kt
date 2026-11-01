package com.kevinpostal.fiberirc.data.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream

/**
 * Saves pictures to the shared Pictures collection (the Photos-library equivalent of the viewer's Save to Photos and
 * Settings → Save to Camera Roll). API 29+ needs no permission: scoped storage limits writes to this app's own
 * MediaStore entries, the counterpart of iOS's add-only photo access. API 26-28 need `WRITE_EXTERNAL_STORAGE`
 * (declared with `maxSdkVersion="28"`), checked with [needsPermission].
 */
object PhotoLibrary {
    /** Whether saving must first ask for `WRITE_EXTERNAL_STORAGE` (API < 29, not granted yet). */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Writes [open]'s bytes as a new picture named [displayName] of type [mime]. Call off the main thread. */
    fun save(context: Context, displayName: String, mime: String, open: () -> InputStream): Result<Uri> = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create the picture")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't write the picture")
            out.use { dst -> open().use { it.copyTo(dst) } }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        uri
    }
}
