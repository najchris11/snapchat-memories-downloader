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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.getCachedThumbnail
import com.najdev.snapvault.ui.theme.MediaColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.Res
import snapchat_memories_downloader.composeapp.generated.resources.video_open_failed
import snapchat_memories_downloader.composeapp.generated.resources.video_open_in_player
import snapchat_memories_downloader.composeapp.generated.resources.video_opening
import snapchat_memories_downloader.composeapp.generated.resources.video_preview_cd
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Composable
actual fun VideoPlayer(videoPath: String, modifier: Modifier) {
    ExternalVideoPlayer(
        videoPath = videoPath,
        modifier = modifier,
        openExternally = { withContext(Dispatchers.IO) { openInVideoPlayer(it) } },
    )
}

private sealed interface VideoLaunch {
    data object Idle : VideoLaunch
    data object Opening : VideoLaunch
    data class Failed(val reason: String) : VideoLaunch
}

/**
 * The desktop Library's video panel. It does not play anything: it shows the cached frame and
 * hands the file to the system's video player (D18).
 *
 * It used to say "Click to Play Video", then "Opening Video..." for two seconds whatever
 * happened, and print any launch error to a console nobody sees — so a file with no player
 * associated, or one deleted since the scan, looked as though it had opened. Now the label says
 * another app will open it, "Opening…" lasts exactly as long as the launch, and a failure stays
 * on the panel with its reason until the next attempt.
 */
@Composable
internal fun ExternalVideoPlayer(
    videoPath: String,
    modifier: Modifier,
    openExternally: suspend (String) -> Unit,
) {
    val thumbnail by produceState<ImageBitmap?>(null, videoPath) {
        value = withContext(Dispatchers.Default) { getCachedThumbnail(videoPath) }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var launch by remember(videoPath) { mutableStateOf<VideoLaunch>(VideoLaunch.Idle) }
    val scope = rememberCoroutineScope()
    val openLabel = stringResource(Res.string.video_open_in_player)

    Box(
        modifier = modifier
            .background(MediaColors.letterbox)
            .hoverable(interactionSource)
            .clickable(
                enabled = launch != VideoLaunch.Opening,
                onClickLabel = openLabel,
                role = Role.Button,
            ) {
                launch = VideoLaunch.Opening
                scope.launch {
                    launch = try {
                        openExternally(videoPath)
                        VideoLaunch.Idle
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        VideoLaunch.Failed(e.message ?: e::class.simpleName ?: "unknown error")
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        thumbnail?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = stringResource(Res.string.video_preview_cd),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }

        // Dark overlay on hover
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isHovered) MediaColors.scrimHoverStrong else MediaColors.scrimHover)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(if (isHovered) 80.dp else 72.dp)
                    .clip(CircleShape)
                    .background(MediaColors.onMedia)
                    .padding(if (isHovered) 4.dp else 0.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    // The label below already says what the button does.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Text(
                text = if (launch == VideoLaunch.Opening) stringResource(Res.string.video_opening) else openLabel,
                color = MediaColors.onMedia,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )

            (launch as? VideoLaunch.Failed)?.let { failed ->
                Text(
                    text = stringResource(Res.string.video_open_failed, failed.reason),
                    color = MediaColors.onMedia,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Hands [path] to the system's default application for it, throwing with a reason a user can
 * act on when that cannot happen.
 */
internal fun openInVideoPlayer(path: String) {
    val file = File(path)
    // Checked here rather than left to the OS, which reports a missing file differently on
    // every platform, or not at all.
    if (!file.isFile) throw IOException("the file no longer exists")

    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
        // Throws IOException when no application is associated with the file type.
        Desktop.getDesktop().open(file)
        return
    }

    val os = System.getProperty("os.name").lowercase()
    val command = when {
        "mac" in os -> listOf("open", file.absolutePath)
        "nix" in os || "nux" in os -> listOf("xdg-open", file.absolutePath)
        else -> throw IOException("this system offers no way to open a file in another app")
    }
    val process = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    // Both launchers exit as soon as the player has been started, with a non-zero code when
    // nothing could open the file. One still running after the wait has launched something
    // that is taking its time, which is not a failure — and is not killed, since that could
    // take the player down with it.
    if (process.waitFor(LAUNCH_WAIT_SECONDS, TimeUnit.SECONDS) && process.exitValue() != 0) {
        throw IOException("${command.first()} could not find an app for this file (exit code ${process.exitValue()})")
    }
}

private const val LAUNCH_WAIT_SECONDS = 5L
