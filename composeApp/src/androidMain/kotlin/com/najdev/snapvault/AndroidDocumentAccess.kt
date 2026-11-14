package com.najdev.snapvault

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.najdev.snapvault.downloader.sortExportZipPaths
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal data class AndroidExportDocument(val source: ExportSource, val label: String)

internal class AndroidDocumentAccess(private val context: Context) {
    fun keepReadAccess(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Reopen a seekable provider FD through procfs; pipe-only providers use streaming. */
    fun directory(path: String): List<DocumentZipEntry>? = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { descriptor ->
            java.util.zip.ZipFile("/proc/self/fd/${descriptor.fd}").use { zip ->
                zip.entries().asSequence().map { DocumentZipEntry(it.name, it.extra) }.toList()
            }
        }
    }.getOrNull()

    fun archives(tree: String): List<String> {
        val children = DocumentFile.fromTreeUri(context, Uri.parse(tree))?.listFiles().orEmpty()
            .filter { it.isFile && it.name?.endsWith(".zip", ignoreCase = true) == true }
        val byName = children.associateBy { it.name.orEmpty() }
        return sortExportZipPaths(byName.keys.toList()).mapNotNull { byName[it]?.uri?.toString() }
    }

    fun prepare(uri: Uri, tree: Boolean): AndroidExportDocument? {
        keepReadAccess(uri)
        if (tree) {
            val folder = DocumentFile.fromTreeUri(context, uri) ?: return null
            if (archives(uri.toString()).isNotEmpty()) return AndroidExportDocument(ExportSource.ZipFolder(uri.toString()), folder.name.orEmpty())
            for (relative in listOf("memories_history.json", "json/memories_history.json", "memories_history.html", "html/memories_history.html")) {
                var document: DocumentFile? = folder
                for (part in relative.split('/')) document = document?.findFile(part)
                if (document?.isFile == true) return history(document)
            }
            return null
        }
        val document = DocumentFile.fromSingleUri(context, uri) ?: return null
        val name = document.name ?: return null
        return when (name.substringAfterLast('.').lowercase()) {
            "zip" -> AndroidExportDocument(ExportSource.ZipFile(uri.toString()), name)
            "json", "html" -> history(document)
            else -> null
        }
    }

    private fun history(document: DocumentFile): AndroidExportDocument {
        val name = document.name ?: throw IOException("Document has no name")
        val directory = File(context.filesDir, "import-history").apply { mkdirs() }
        val id = MessageDigest.getInstance("SHA-256").digest(document.uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val target = File(directory, "$id-${UUID.randomUUID()}.${name.substringAfterLast('.')}")
        val staged = File.createTempFile("history-", ".part", directory)
        try {
            context.contentResolver.openInputStream(document.uri)?.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_STREAMED_HISTORY_BYTES) throw IOException("History document is too large")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("Could not open history document")
            if (!staged.renameTo(target)) throw IOException("Could not keep selected history")
        } finally { staged.delete() }
        return AndroidExportDocument(ExportSource.History(target.path), name)
    }
}

internal fun androidWorkingLibrary(context: Context): String =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "library").absolutePath
