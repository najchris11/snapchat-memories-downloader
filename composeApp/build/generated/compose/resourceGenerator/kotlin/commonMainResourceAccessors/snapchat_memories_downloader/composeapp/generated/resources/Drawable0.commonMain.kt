@file:OptIn(org.jetbrains.compose.resources.InternalResourceApi::class)

package snapchat_memories_downloader.composeapp.generated.resources

import kotlin.OptIn
import org.jetbrains.compose.resources.DrawableResource

private object CommonMainDrawable0 {
  public val ic_launcher: DrawableResource by 
      lazy { init_ic_launcher() }
}

internal val Res.drawable.ic_launcher: DrawableResource
  get() = CommonMainDrawable0.ic_launcher

private fun init_ic_launcher(): DrawableResource = org.jetbrains.compose.resources.DrawableResource(
  "drawable:ic_launcher",
    setOf(
      org.jetbrains.compose.resources.ResourceItem(setOf(),
    "composeResources/snapchat_memories_downloader.composeapp.generated.resources/drawable/ic_launcher.png", -1, -1),
    )
)
