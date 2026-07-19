package com.najdev.snapvault

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
actual fun rememberPlatformPickers(): PlatformPickers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeJob by remember { mutableStateOf<Job?>(null) }
    var onHtmlResult by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onFolderResult by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onZipsResult by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }

    val htmlPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val cb = onHtmlResult ?: return@rememberLauncherForActivityResult
        onHtmlResult = null
        if (uri == null) {
            cb(null)
            return@rememberLauncherForActivityResult
        }
        activeJob?.cancel()
        activeJob = scope.launch {
            val path = withContext(Dispatchers.IO) {
                copyUriToInternalStorage(context, uri, "memories_history.html")
            }
            cb(path)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val cb = onFolderResult ?: return@rememberLauncherForActivityResult
        onFolderResult = null
        if (uri == null) {
            cb(null)
            return@rememberLauncherForActivityResult
        }
        try {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {}

        val resolvedPath = resolveTreeUriToPath(context, uri)
        cb(resolvedPath)
    }

    val zipsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val cb = onZipsResult ?: return@rememberLauncherForActivityResult
        onZipsResult = null
        if (uris.isEmpty()) {
            cb(emptyList())
            return@rememberLauncherForActivityResult
        }
        activeJob?.cancel()
        activeJob = scope.launch {
            val paths = withContext(Dispatchers.IO) {
                coroutineScope {
                    uris.mapIndexed { index, uri ->
                        async(Dispatchers.IO) {
                            val filename = getFileNameFromUri(context, uri) ?: "snapchat_export_${index}.zip"
                            copyUriToInternalStorage(context, uri, filename)
                        }
                    }.awaitAll().filterNotNull()
                }
            }
            cb(paths)
        }
    }

    return remember {
        object : PlatformPickers {
            override fun pickHtmlFile(onResult: (String?) -> Unit) {
                onHtmlResult = onResult
                htmlPickerLauncher.launch("text/html")
            }

            override fun pickFolder(onResult: (String?) -> Unit) {
                onFolderResult = onResult
                folderPickerLauncher.launch(null)
            }

            override fun pickMultipleZips(onResult: (List<String>) -> Unit) {
                onZipsResult = onResult
                zipsPickerLauncher.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            }
        }
    }
}

private fun copyUriToInternalStorage(context: Context, uri: Uri, targetName: String): String? {
    return try {
        val expectedSize = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (_: Exception) {
            -1L
        }

        val importsDir = File(context.filesDir, "imports").also { it.mkdirs() }
        val sanitized = targetName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val file = File(importsDir, sanitized)

        // Reuse cached file if present and matches length (deduplication on re-pick)
        if (file.exists() && expectedSize > 0 && file.length() == expectedSize) {
            return file.absolutePath
        }

        val tempFile = File.createTempFile("copy_", ".part", importsDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        if (file.exists()) file.delete()
        if (tempFile.renameTo(file)) {
            file.absolutePath
        } else {
            tempFile.absolutePath
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var name: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index)
                }
            }
        } catch (_: Exception) {}
    }
    if (name == null) {
        name = uri.path?.substringAfterLast('/')
    }
    return name
}

private fun resolveTreeUriToPath(context: Context, uri: Uri): String {
    val docId = try {
        android.provider.DocumentsContract.getTreeDocumentId(uri)
    } catch (_: Exception) {
        uri.path
    }
    if (docId != null && docId.startsWith("primary:")) {
        val relativePath = docId.substringAfter("primary:")
        val primaryDir = Environment.getExternalStorageDirectory()
        val resolved = File(primaryDir, relativePath)
        if (resolved.exists() || resolved.mkdirs()) {
            if (isWritableDirectory(resolved)) {
                return resolved.absolutePath
            }
        }
    }

    // Fallback to app-external directory if primary path is unresolvable or non-writable due to Scoped Storage
    val fallback = context.getExternalFilesDir("SnapVault") ?: context.filesDir
    fallback.mkdirs()
    return fallback.absolutePath
}

private fun isWritableDirectory(dir: File): Boolean {
    return try {
        val testFile = File(dir, ".snapvault_write_test_${System.currentTimeMillis()}.tmp")
        val created = testFile.createNewFile()
        if (created) {
            testFile.delete()
            true
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }
}
