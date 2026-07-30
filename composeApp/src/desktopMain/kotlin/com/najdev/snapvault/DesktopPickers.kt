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
        val result = if (dialog.file != null) java.io.File(dialog.directory, dialog.file).absolutePath else null
        onResult(result)
    }

    override fun pickFolder(onResult: (String?) -> Unit) {
        pickOutputFolder(onResult)
    }

    override fun pickOutputFolder(onResult: (String?) -> Unit) {
        pickFolderInternal("Select Output Folder", onResult)
    }

    override fun pickZipFolder(onResult: (String?) -> Unit) {
        pickFolderInternal("Select ZIP Source Folder", onResult)
    }

    private fun pickFolderInternal(title: String, onResult: (String?) -> Unit) {
        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        if (isMac) {
            System.setProperty("apple.awt.fileDialogForDirectories", "true")
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isVisible = true
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
            val result = if (dialog.file != null) java.io.File(dialog.directory, dialog.file).absolutePath else null
            onResult(result)
        } else {
            val chooser = javax.swing.JFileChooser()
            chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            chooser.dialogTitle = title
            val result = if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.absolutePath
            } else null
            onResult(result)
        }
    }

    override fun pickMultipleZips(onResult: (List<String>) -> Unit) {
        val dialog = FileDialog(null as Frame?, "Select Snapchat ZIP Files", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
        dialog.isMultipleMode = true
        dialog.isVisible = true
        val files = dialog.files?.map { it.absolutePath } ?: emptyList()
        onResult(files)
    }
}

@Composable
actual fun rememberPlatformPickers(): PlatformPickers = remember { DesktopPickers() }
