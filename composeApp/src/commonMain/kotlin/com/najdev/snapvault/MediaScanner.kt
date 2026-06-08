package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import com.najdev.snapvault.ui.LibraryItem

expect fun scanMediaFiles(folderPath: String): List<LibraryItem>
expect fun loadThumbnail(path: String): ImageBitmap?
