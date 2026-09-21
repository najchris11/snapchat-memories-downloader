package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * For a tool whose entire job is producing a folder of media, nothing in the Library opened
 * that folder or revealed a file — despite `openUrl` existing since round 1 and `VideoPlayer`
 * already carrying a working `Desktop.open` helper.
 *
 * The capability is a parameter rather than read straight from `supportsFileManager` so the
 * absent case is testable: on desktop that constant is `true`, so a test could otherwise only
 * ever assert the present half, and the mobile no-op would be unverified.
 */
@OptIn(ExperimentalTestApi::class)
class RevealInFileManagerTest {

    private val memory = LibraryItem(
        id = "/vault/2026-01-01_memory.jpg",
        date = "JAN 01, 2026",
        title = "2026-01-01_memory",
        type = "photo",
        hasGps = false,
        hasOverlay = false,
    )

    @Test
    fun theInspectorRevealsTheSelectedFileWhereThePlatformSupportsIt() = runComposeUiTest {
        val revealed = mutableListOf<String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                InspectorItemDetail(
                    item = memory,
                    onPreview = {},
                    onClearSelection = {},
                    canRevealFiles = true,
                    onReveal = { revealed += it },
                )
            }
        }

        // The inspector is a scrolling column and the action sits below the metadata rows,
        // so in a test-sized window it starts off screen.
        onNodeWithContentDescription("Reveal in file manager")
            .assertHasClickAction()
            .performScrollTo()
            .performClick()
        assertEquals(listOf(memory.id), revealed)
    }

    // Android and iOS have no user-facing file manager to reveal into. Offering a control
    // that silently does nothing is worse than not offering one.
    @Test
    fun theActionIsAbsentWherePlatformSupportIsNot() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                InspectorItemDetail(
                    item = memory,
                    onPreview = {},
                    onClearSelection = {},
                    canRevealFiles = false,
                    onReveal = {},
                )
            }
        }

        onNodeWithContentDescription("Reveal in file manager").assertDoesNotExist()
    }

    @Test
    fun theHeaderOpensTheOutputFolderWhereThePlatformSupportsIt() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryHeaderActions(
                    downloadFolder = "/vault",
                    canRevealFiles = true,
                    onOpenOutputFolder = { opened += it },
                )
            }
        }

        onNodeWithContentDescription("Open output folder").assertHasClickAction().performClick()
        assertEquals(listOf("/vault"), opened)
    }

    @Test
    fun theHeaderActionIsAbsentWherePlatformSupportIsNot() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                LibraryHeaderActions(
                    downloadFolder = "/vault",
                    canRevealFiles = false,
                    onOpenOutputFolder = {},
                )
            }
        }

        onNodeWithContentDescription("Open output folder").assertDoesNotExist()
    }
}
