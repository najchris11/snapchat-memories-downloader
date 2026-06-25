package com.najdev.snapvault.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.getCachedThumbnail
import com.najdev.snapvault.ui.theme.SnapVaultColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.io.File

@Composable
actual fun VideoPlayer(videoPath: String, modifier: Modifier) {
    val thumbnail by produceState<ImageBitmap?>(null, videoPath) {
        value = withContext(Dispatchers.Default) { getCachedThumbnail(videoPath) }
    }
    
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    
    var isOpening by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .background(Color.Black)
            .hoverable(interactionSource)
            .clickable {
                isOpening = true
                openVideoLocally(videoPath)
            },
        contentAlignment = Alignment.Center
    ) {
        thumbnail?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = "Video preview",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        
        // Dark overlay on hover
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isHovered) Color.Black.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.2f))
        )
        
        // Large glassmorphic play button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (isHovered) 80.dp else 72.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.85f))
                    .padding(if (isHovered) 4.dp else 0.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play Video",
                    tint = SnapVaultColors.electricPurple,
                    modifier = Modifier.size(36.dp)
                )
            }
            
            Text(
                text = if (isOpening) "Opening Video..." else "Click to Play Video",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    
    // Reset opening state after short delay
    LaunchedEffect(isOpening) {
        if (isOpening) {
            delay(2000)
            isOpening = false
        }
    }
}

private fun openVideoLocally(path: String) {
    try {
        val file = File(path)
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().open(file)
        } else {
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("mac") -> Runtime.getRuntime().exec(arrayOf("open", path))
                os.contains("nix") || os.contains("nux") -> Runtime.getRuntime().exec(arrayOf("xdg-open", path))
                else -> {}
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
