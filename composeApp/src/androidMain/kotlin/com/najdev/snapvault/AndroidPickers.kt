package com.najdev.snapvault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberPlatformPickers(): PlatformPickers {
    val context = LocalContext.current.applicationContext
    val access = remember(context) { AndroidDocumentAccess(context) }
    val scope = rememberCoroutineScope()
    val inspected = remember { mutableStateMapOf<String, AndroidExportDocument>() }
    var onFile by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onFolder by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var onFiles by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
    fun accept(document: AndroidExportDocument): String {
        inspected[document.source.path] = document
        return document.source.path
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            inspected.remove(uri.toString())
            val document = withContext(Dispatchers.IO) { runCatching { access.prepare(uri, false) }.getOrNull() }
            onFile?.invoke(document?.let(::accept) ?: uri.toString())
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            inspected.remove(uri.toString())
            val document = withContext(Dispatchers.IO) { runCatching { access.prepare(uri, true) }.getOrNull() }
            onFolder?.invoke(document?.let(::accept) ?: uri.toString())
        }
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            uris.forEach { inspected.remove(it.toString()) }
            val documents = withContext(Dispatchers.IO) { uris.mapNotNull { uri -> runCatching { access.prepare(uri, false) }.getOrNull()?.takeIf { it.source is ExportSource.ZipFile } } }
            documents.forEach(::accept)
            onFiles?.invoke(uris.map { it.toString() })
        }
    }
    return remember {
        object : PlatformPickers {
            override fun inspectExportSource(path: String): ExportSource? = inspected[path]?.source
            override fun sourceLabel(path: String): String? = inspected[path]?.label
            override fun pickExportFile(onResult: (String?) -> Unit) { onFile = onResult; fileLauncher.launch(arrayOf("*/*")) }
            override fun pickHtmlFile(onResult: (String?) -> Unit) = pickExportFile(onResult)
            override fun pickFolder(onResult: (String?) -> Unit) = pickOutputFolder(onResult)
            override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(androidWorkingLibrary(context))
            override fun pickZipFolder(onResult: (String?) -> Unit) { onFolder = onResult; folderLauncher.launch(null) }
            override fun pickMultipleZips(onResult: (List<String>) -> Unit) { onFiles = onResult; filesLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        }
    }
}
