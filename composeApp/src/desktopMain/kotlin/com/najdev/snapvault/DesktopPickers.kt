package com.najdev.snapvault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame

class DesktopPickers : PlatformPickers {
    override fun pickHtmlFile(onResult: (String?) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Select memories_history.json or .html", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            name.endsWith(".json", ignoreCase = true) || name.endsWith(".html", ignoreCase = true)
        }
        dialog.isVisible = true
        val result = if (dialog.file != null) dialog.directory + dialog.file else null
        onResult(result)
    }

    override fun pickFolder(onResult: (String?) -> Unit) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        val dialog = FileDialog(null as Frame?, "Select Output Folder", FileDialog.LOAD)
        dialog.isVisible = true
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
        val result = if (dialog.file != null) dialog.directory + dialog.file else null
        onResult(result)
    }

    override fun pickZipFile(onResult: (String?) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Select Snapchat ZIP File", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
        dialog.isVisible = true
        val result = if (dialog.file != null) dialog.directory + dialog.file else null
        onResult(result)
    }
}

@Composable
actual fun rememberPlatformPickers(): PlatformPickers = remember { DesktopPickers() }
