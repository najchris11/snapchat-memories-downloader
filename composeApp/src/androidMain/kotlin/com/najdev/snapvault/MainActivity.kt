package com.najdev.snapvault

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val pickers = rememberAndroidPickers()
            App(pickers = pickers)
        }
    }
}

@Composable
fun rememberAndroidPickers(): PlatformPickers {
    val context = LocalContext.current
    var onHtmlResult: ((String?) -> Unit)? = null
    var onFolderResult: ((String?) -> Unit)? = null
    var onZipsResult: ((List<String>) -> Unit)? = null

    val htmlPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val path = uri?.let { copyUriToInternalStorage(context, it, "memories_history.html") }
        onHtmlResult?.invoke(path)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        onFolderResult?.invoke(uri?.toString())
    }

    val zipsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val paths = uris.mapNotNull { uri ->
            val filename = "snapchat_export_${System.currentTimeMillis()}_${uris.indexOf(uri)}.zip"
            copyUriToInternalStorage(context, uri, filename)
        }
        onZipsResult?.invoke(paths)
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

            override fun pickOutputFolder(onResult: (String?) -> Unit) {
                onFolderResult = onResult
                folderPickerLauncher.launch(null)
            }

            override fun pickZipFolder(onResult: (String?) -> Unit) {
                onFolderResult = onResult
                folderPickerLauncher.launch(null)
            }

            override fun pickMultipleZips(onResult: (List<String>) -> Unit) {
                onZipsResult = onResult
                zipsPickerLauncher.launch("application/zip")
            }
        }
    }
}

private fun copyUriToInternalStorage(context: Context, uri: Uri, targetFilename: String): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val outputFile = File(context.cacheDir, targetFilename)
        FileOutputStream(outputFile).use { output ->
            inputStream.copyTo(output)
        }
        outputFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
