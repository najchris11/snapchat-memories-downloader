package com.najdev.snapvault

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import com.najdev.snapvault.metadata.AndroidMediaProcessor
import okio.FileSystem
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val mediaProcessor = remember { AndroidMediaProcessor() }
            
            var onHtmlResult by remember { mutableStateOf<((String?) -> Unit)?>(null) }
            var onFolderResult by remember { mutableStateOf<((String?) -> Unit)?>(null) }
            var onZipsResult by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }

            val htmlPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                val path = uri?.let { copyUriToInternalStorage(context, it, "memories_history.html") }
                onHtmlResult?.invoke(path)
            }

            val folderPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri: Uri? ->
                val path = context.getExternalFilesDir(null)?.absolutePath
                onFolderResult?.invoke(path)
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

            val pickers = remember {
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
                        zipsPickerLauncher.launch("application/zip")
                    }
                }
            }

            App(
                pickers = pickers,
                mediaProcessor = mediaProcessor,
                zipPipelineRunner = NoOpZipPipelineRunner,
                fileSystem = FileSystem.SYSTEM
            )
        }
    }

    private fun copyUriToInternalStorage(context: Context, uri: Uri, targetName: String): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val file = File(context.cacheDir, targetName)
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
}
