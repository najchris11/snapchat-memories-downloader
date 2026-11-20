package com.najdev.snapvault

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ContentResolver pipe: procfs cannot provide a seekable ZIP directory. */
@RunWith(AndroidJUnit4::class)
class NonSeekableDocumentTest {
    @Test(timeout = 30_000)
    fun aPipeOnlyZipFallsBackToReopenableStreamingWithoutCachingTheArchive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val uri = Uri.parse("content://${instrumentation.context.packageName}.pipezip/document/export.zip")
        val access = AndroidDocumentAccess(context)
        assertEquals(ExportSource.ZipFile(uri.toString()), requireNotNull(access.prepare(uri, false)).source)
        assertNull("A pipe cannot supply a seekable central directory", access.directory(uri.toString()))
        val cachedBefore = context.cacheDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet()
        val previousOpen = JvmDocumentSources.open
        val previousDirectory = JvmDocumentSources.directory
        try {
            JvmDocumentSources.open = { requireNotNull(context.contentResolver.openInputStream(Uri.parse(it))) }
            JvmDocumentSources.directory = access::directory
            // Regression: treating an unavailable seekable directory as empty loses valid pipe-only exports.
            assertEquals(listOf("memories/payload.bin", "memories_history.json"), listZipEntries(uri.toString()))
            assertEquals("{\"Saved Media\":[]}", readZipEntryText(uri.toString(), "memories_history.json"))
            assertNull(readZipEntryText(uri.toString(), "missing.json"))
            assertEquals(cachedBefore, context.cacheDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet())
        } finally {
            JvmDocumentSources.open = previousOpen
            JvmDocumentSources.directory = previousDirectory
        }
    }
}

