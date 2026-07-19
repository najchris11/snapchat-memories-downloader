package com.najdev.snapvault

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
actual fun rememberPlatformPickers(): PlatformPickers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var onHtmlResult by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onFolderResult by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onZipsResult by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }

    val htmlPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val cb = onHtmlResult ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            cb(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
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
        if (uris.isEmpty()) {
            cb(emptyList())
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val paths = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { index, uri ->
                    val filename = getFileNameFromUri(context, uri) ?: "snapchat_export_${index}.zip"
                    copyUriToInternalStorage(context, uri, filename)
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
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val importsDir = File(context.filesDir, "imports").also { it.mkdirs() }
        val file = File(importsDir, targetName)
        val outputStream = FileOutputStream(file)
        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }
        file.absolutePath
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
            return resolved.absolutePath
        }
    }
    return context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
}
