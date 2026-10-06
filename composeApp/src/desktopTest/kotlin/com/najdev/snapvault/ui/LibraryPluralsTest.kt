package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import org.jetbrains.compose.resources.pluralStringResource
import snapchat_memories_downloader.composeapp.generated.resources.Res
import snapchat_memories_downloader.composeapp.generated.resources.lib_file_count
import snapchat_memories_downloader.composeapp.generated.resources.lib_photo_count
import snapchat_memories_downloader.composeapp.generated.resources.lib_video_count
import snapchat_memories_downloader.composeapp.generated.resources.zip_files_selected
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Five counts were pluralised as `"$n item${if (n == 1) "" else "s"}"` — English by
 * construction, and not localisable without rewriting the call site, since most languages
 * do not form plurals by appending a letter and several have more than two forms.
 *
 * These pin counts of 1 and 2 for every one of them. That does assert the default locale's
 * copy, deliberately: "1 files" is the exact defect hand-rolled pluralisation produces, and
 * only the rendered text shows it.
 *
 * The inspector's "items tagged" and "assets combined" counts now reuse lib_file_count; the
 * ZIP picker's "N ZIP files selected" was a literal until the verbiage audit.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryPluralsTest {

    @Test
    fun everyCountHasASeparateSingularAndPluralForm() = runComposeUiTest {
        val rendered = mutableMapOf<String, String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                listOf(
                    "file" to Res.plurals.lib_file_count,
                    "photo" to Res.plurals.lib_photo_count,
                    "video" to Res.plurals.lib_video_count,
                    "zip" to Res.plurals.zip_files_selected,
                ).forEach { (name, resource) ->
                    rendered["$name-1"] = pluralStringResource(resource, 1, 1)
                    rendered["$name-2"] = pluralStringResource(resource, 2, 2)
                }
            }
        }

        assertEquals("1 file", rendered["file-1"])
        assertEquals("2 files", rendered["file-2"])
        assertEquals("1 photo", rendered["photo-1"])
        assertEquals("2 photos", rendered["photo-2"])
        assertEquals("1 video", rendered["video-1"])
        assertEquals("2 videos", rendered["video-2"])
        assertEquals("1 ZIP file selected", rendered["zip-1"])
        assertEquals("2 ZIP files selected", rendered["zip-2"])
    }
}
