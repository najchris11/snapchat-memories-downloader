package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.Screen
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sidebar read `nav_dashboard` / `nav_library` / `nav_settings` from resources while
 * `PhoneRoot` passed the same three words as literals — one navigation, two sources of truth,
 * and the literals could not be translated at all.
 *
 * Asserting that both render the same strings would have caught the divergence but not
 * prevented it; both now read the label off [Screen], so there is one place to change. This
 * pins that the resource actually resolves, which a literal would trivially satisfy and an
 * unresolved resource would not.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationLabelTest {

    @Test
    fun everyScreenHasANonEmptyLabelFromResources() = runComposeUiTest {
        val labels = mutableMapOf<Screen, String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                Screen.entries.forEach { labels[it] = it.navLabel() }
            }
        }

        assertEquals(Screen.entries.size, labels.size)
        assertEquals(
            Screen.entries.size,
            labels.values.toSet().size,
            "each destination needs its own label, or the nav reads as duplicates: $labels",
        )
        labels.forEach { (screen, label) ->
            assertEquals(
                true,
                label.isNotBlank(),
                "$screen resolved to a blank label — the resource key is probably wrong",
            )
        }
    }
}
