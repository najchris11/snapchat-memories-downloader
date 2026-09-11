package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test

/**
 * Controls built as `clickable` Boxes announce themselves to assistive tech as text, and get
 * whatever hit area their padding happens to give — well under the 48dp minimum, which is a
 * live problem on the phone layout where these are finger targets.
 *
 * These assert the two properties that were missing, on the controls the audit named.
 */
@OptIn(ExperimentalTestApi::class)
class ControlSemanticsTest {

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    // A pipeline option is one control, not two. The row owns the interaction and the
    // Switch inside is passed onCheckedChange = null, so assistive tech sees a single
    // toggle rather than a row of text plus an unlabelled switch.
    @Test
    fun aPipelineOptionIsOneToggleWithASwitchRole() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                PipelineItem(
                    icon = Icons.Outlined.Info,
                    label = "Merge Video Overlays",
                    checked = true,
                    onCheckedChange = {},
                )
            }
        }

        onNodeWithText("Merge Video Overlays")
            .assert(hasRole(Role.Switch))
            .assertIsOn()
    }

    // The row's visual height is 13sp of text in 6dp padding. The touch target has to be
    // 48dp regardless, which minimumInteractiveComponentSize provides without changing what
    // is drawn.
    @Test
    fun aPipelineOptionMeetsTheMinimumTouchTarget() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                Box(Modifier.width(400.dp)) {
                    PipelineItem(
                        icon = Icons.Outlined.Info,
                        label = "Clean Duplicate Files",
                        checked = false,
                        onCheckedChange = {},
                    )
                }
            }
        }

        onNodeWithText("Clean Duplicate Files").assertTouchHeightIsEqualTo(48.dp)
    }

    // Filter tabs are a single-choice group, so they carry a selection role rather than
    // reading as three unrelated pieces of text.
    @Test
    fun libraryFilterTabsAnnounceThemselvesAsASelectableGroup() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                Box(Modifier.width(900.dp)) {
                    LibraryScreen(
                        downloadFolder = null,
                        onOpenFolder = {},
                        windowSize = WindowSize.Expanded,
                    )
                }
            }
        }

        listOf("All", "Photos", "Videos").forEach { tab ->
            onNodeWithText(tab)
                .assert(hasRole(Role.RadioButton))
                .assertTouchHeightIsEqualTo(48.dp)
        }
    }
}
