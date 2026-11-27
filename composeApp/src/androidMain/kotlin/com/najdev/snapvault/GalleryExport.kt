package com.najdev.snapvault

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.najdev.snapvault.downloader.GalleryPublication
import com.najdev.snapvault.downloader.PendingMediaStore
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

// Android 10+ can publish app-owned copies without broad photo/storage permissions.
actual val supportsGalleryExport: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

actual suspend fun publishToGallery(path: String): GalleryExportResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return GalleryExportResult.Unsupported
    val context = ContextHolder.context ?: return GalleryExportResult.Failed
    val file = File(path)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?.takeIf { it.startsWith("image/") || it.startsWith("video/") } ?: return GalleryExportResult.Unsupported
    return withContext(ioDispatcher) {
        runInterruptibleCompat {
            val resolver = context.contentResolver
            val isVideo = mimeType.startsWith("video/")
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val store = object : PendingMediaStore {
                override fun create(name: String, mimeType: String): String? = resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/SnapVault")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })?.toString()
                override fun output(id: String): OutputStream? = resolver.openOutputStream(Uri.parse(id), "w")
                override fun commit(id: String): Boolean = resolver.update(Uri.parse(id), ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1
                override fun discard(id: String) { resolver.delete(Uri.parse(id), null, null) }
            }
            if (GalleryPublication.publish(file, mimeType, store)) GalleryExportResult.Saved else GalleryExportResult.Failed
        }
    }
}
