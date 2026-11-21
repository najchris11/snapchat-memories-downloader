package com.najdev.snapvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.najdev.snapvault.downloader.AndroidZipPipelineRunner
import com.najdev.snapvault.metadata.AndroidMediaProcessor
import okio.FileSystem

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        ContextHolder.context = appContext
        JvmDocumentSources.open = { path ->
            appContext.contentResolver.openInputStream(android.net.Uri.parse(path))
                ?: throw java.io.IOException("Could not open selected document")
        }
        JvmDocumentSources.directory = AndroidDocumentAccess(appContext)::directory
        setContent {
            val pickers = rememberPlatformPickers()
            val mediaProcessor = remember { AndroidMediaProcessor() }
            // Was NoOpZipPipelineRunner: the app could pick an export and then silently do
            // nothing with it. The runner shares desktop's extraction and composites
            // overlays with android.graphics.
            val zipPipelineRunner = remember(mediaProcessor) { AndroidZipPipelineRunner(mediaProcessor) }
            App(
                pickers = pickers,
                mediaProcessor = mediaProcessor,
                zipPipelineRunner = zipPipelineRunner,
                fileSystem = FileSystem.SYSTEM
            )
        }
    }
}
