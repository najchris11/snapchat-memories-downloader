package com.najdev.snapvault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.Res
import snapchat_memories_downloader.composeapp.generated.resources.picker_history_title
import snapchat_memories_downloader.composeapp.generated.resources.picker_output_title
import snapchat_memories_downloader.composeapp.generated.resources.picker_zip_files_title
import snapchat_memories_downloader.composeapp.generated.resources.picker_zip_folder_title
import java.awt.FileDialog
import java.awt.Frame

/** Dialog titles, resolved from strings.xml where a composable can read them. */
data class PickerTitles(
    val historyFile: String,
    val outputFolder: String,
    val zipFolder: String,
    val zipFiles: String,
)

class DesktopPickers(private val titles: PickerTitles) : PlatformPickers {
    override fun pickHtmlFile(onResult: (String?) -> Unit) {
        val dialog = FileDialog(null as Frame?, titles.historyFile, FileDialog.LOAD)
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
        pickFolderInternal(titles.outputFolder, onResult)
    }

    override fun pickZipFolder(onResult: (String?) -> Unit) {
        pickFolderInternal(titles.zipFolder, onResult)
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
        val dialog = FileDialog(null as Frame?, titles.zipFiles, FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".zip", ignoreCase = true) }
        dialog.isMultipleMode = true
        dialog.isVisible = true
        val files = dialog.files?.map { it.absolutePath } ?: emptyList()
        onResult(files)
    }
}

@Composable
actual fun rememberPlatformPickers(): PlatformPickers {
    val titles = PickerTitles(
        historyFile = stringResource(Res.string.picker_history_title),
        outputFolder = stringResource(Res.string.picker_output_title),
        zipFolder = stringResource(Res.string.picker_zip_folder_title),
        zipFiles = stringResource(Res.string.picker_zip_files_title),
    )
    return remember(titles) { DesktopPickers(titles) }
}
