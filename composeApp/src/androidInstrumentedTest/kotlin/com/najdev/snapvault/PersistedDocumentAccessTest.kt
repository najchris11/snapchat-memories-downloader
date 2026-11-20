package com.najdev.snapvault

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a tree selected through the real picker before this fresh-process test runs. */
@RunWith(AndroidJUnit4::class)
class PersistedDocumentAccessTest {
    @Test
    fun aPersistedReadGrantReopensArchivesWithoutAnActivityOrNewPickerGrant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val tree = requireNotNull(InstrumentationRegistry.getArguments().getString("nativeFixtureTree")) {
            "Select the synthetic two-archive folder first; supply its content URI as nativeFixtureTree"
        }
        val resolver = context.contentResolver
        val permission = resolver.persistedUriPermissions.single { it.uri.toString() == tree }
        assertTrue("The grant must survive process restart", permission.isReadPermission)
        assertFalse("Only source read access may be persisted", permission.isWritePermission)
        val access = AndroidDocumentAccess(context)
        val archives = access.archives(tree)
        assertEquals(2, archives.size)
        val descriptor = requireNotNull(access.prepare(Uri.parse(archives.first()), false))
        assertEquals(ExportSource.ZipFile(archives.first()), descriptor.source)
        val entries = StreamingZipReader {
            requireNotNull(resolver.openInputStream(Uri.parse(archives.first())))
        }.entries()
        assertTrue(entries.contains("memories/2026-11-08_clip-main.mp4"))
        assertTrue(entries.contains("memories/2026-11-08_photo-main.jpg"))
        assertFalse("Selected ZIPs must not be cached wholesale", context.cacheDir.walkTopDown().any { it.extension == "zip" })
    }

    @Test
    fun aSourceOutsideThePersistedTreeIsNotReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outside = requireNotNull(InstrumentationRegistry.getArguments().getString("nativeOutsideDocument")) {
            "Supply an existing synthetic document outside the selected tree as nativeOutsideDocument"
        }
        assertNull(AndroidDocumentAccess(context).directory(outside))
    }
}
