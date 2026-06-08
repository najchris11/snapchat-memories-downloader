package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import com.najdev.snapvault.ui.LibraryItem

actual fun scanMediaFiles(folderPath: String): List<LibraryItem> = emptyList()
actual fun loadThumbnail(path: String): ImageBitmap? = null
