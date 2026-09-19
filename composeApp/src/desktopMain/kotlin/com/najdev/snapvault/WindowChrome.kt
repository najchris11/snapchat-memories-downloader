package com.najdev.snapvault

/**
 * Whether the window wears the system's title bar or draws its own (D21).
 *
 * @param undecorated ask the OS for a borderless window.
 * @param customWindowControls draw close/minimize/maximize glyphs in the app's top bar.
 * @param dragFromContent let a drag anywhere on the top bar move the window.
 */
internal data class DesktopWindowChrome(
    val undecorated: Boolean,
    val customWindowControls: Boolean,
    val dragFromContent: Boolean,
)

/**
 * macOS keeps its native title bar; every other platform keeps SnapVault's own.
 *
 * A borderless window on macOS has no native fullscreen button, and window managers use that to
 * tell an application window from a dialog: AeroSpace floats what it reads as a dialog instead
 * of tiling it, which is what a user reported. The same window also had no Mission Control
 * fullscreen and no standard accessibility contract. Windows and Linux windows are not
 * classified that way and keep the custom title bar they were designed around.
 */
internal fun windowChromeFor(osName: String): DesktopWindowChrome {
    val isMac = "mac" in osName.lowercase() || "darwin" in osName.lowercase()
    return DesktopWindowChrome(
        undecorated = !isMac,
        customWindowControls = !isMac,
        dragFromContent = !isMac,
    )
}

internal val platformWindowChrome: DesktopWindowChrome by lazy {
    windowChromeFor(System.getProperty("os.name").orEmpty())
}
