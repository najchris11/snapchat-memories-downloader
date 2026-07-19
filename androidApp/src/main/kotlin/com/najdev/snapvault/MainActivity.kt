package com.najdev.snapvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.najdev.snapvault.downloader.AndroidZipPipelineRunner
import com.najdev.snapvault.metadata.AndroidMediaProcessor
import okio.FileSystem

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextHolder.context = applicationContext
        setContent {
            val mediaProcessor = remember { AndroidMediaProcessor() }
            val zipPipelineRunner = remember { AndroidZipPipelineRunner(mediaProcessor) }
            val pickers = rememberPlatformPickers()

            App(
                pickers = pickers,
                mediaProcessor = mediaProcessor,
                zipPipelineRunner = zipPipelineRunner,
                fileSystem = FileSystem.SYSTEM
            )
        }
    }
}
