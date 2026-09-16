# Desktop audit reproductions

These temporary safety assertions were run against develop `7b0cfa2` on 2026-09-15. All four failed for the documented safety violations. No production code was changed. Kept here outside the active test source set so an audit-only change does not make CI red. To reproduce, copy the code to `composeApp/src/desktopTest/kotlin/com/najdev/snapvault/downloader/DesktopAuditProbeTest.kt` and run `./gradlew :composeApp:desktopTest --tests "*DesktopAuditProbeTest"`. Each probe uses synthetic files in a fresh temporary directory and cleans up that directory.

```kotlin
package com.najdev.snapvault.downloader

import com.najdev.snapvault.VaultIndex
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/** Temporary audit probes: assertions describe the safety users should receive. */
class DesktopAuditProbeTest {
    private fun inTemp(block: suspend (File) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("snapvault-audit-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
    private fun zip(dir: File, entries: Map<String, String>): File = File(dir, "unrelated.zip").also { archive ->
        ZipOutputStream(archive.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry()
            }
        }
    }
    @Test fun unrelatedArchiveWithCollidingEntriesMustBeRetained() = inTemp { dir ->
        val archive = zip(dir, linkedMapOf("one.jpg" to "first-photo", "two.jpg" to "second-photo"))
        ZipExtractEngine().extractDownloadedArchives(dir.path) {}
        assertTrue(archive.exists(), "Archive deleted; remaining files: ${dir.listFiles()?.map { it.name to it.readText() }}")
    }
    @Test fun thumbnailOnlyArchiveMustBeRetained() = inTemp { dir ->
        val archive = zip(dir, mapOf("thumbnail.jpg" to "only-copy"))
        ZipExtractEngine().extractDownloadedArchives(dir.path) {}
        assertTrue(archive.exists(), "Archive deleted without extracting any file")
    }
    @Test fun unrelatedPartFileMustBeRetained() = inTemp { dir ->
        val part = File(dir, "browser-download.part").apply { writeText("user-download") }
        ZipExtractEngine().extractAll(emptyMap(), dir.path, 1) {}
        assertTrue(part.exists(), "Unrelated .part deleted even with zero extraction tasks")
    }
    @Test fun corruptIndexMustNotBeOverwrittenByFavorite() = inTemp { dir ->
        val index = File(dir, VaultIndex.FILE_NAME).apply { writeText("{damaged-but-recoverable-user-data") }
        val original = index.readText()
        runCatching { VaultIndex.setFavorite(FileSystem.SYSTEM, dir.path, "new.jpg", true) }
        assertTrue(index.readText() == original, "Unreadable index replaced: ${index.readText()}")
    }
}
```

## Observed assertions

- corruptIndexMustNotBeOverwrittenByFavorite[desktop]: java.lang.AssertionError: Unreadable index replaced: {"new.jpg":{"hasGps":false,"hasOverlay":false,"favorited":true}}
- thumbnailOnlyArchiveMustBeRetained[desktop]: java.lang.AssertionError: Archive deleted without extracting any file
- unrelatedPartFileMustBeRetained[desktop]: java.lang.AssertionError: Unrelated .part deleted even with zero extraction tasks
- unrelatedArchiveWithCollidingEntriesMustBeRetained[desktop]: java.lang.AssertionError: Archive deleted; remaining files: [(unrelated-main.jpg, first-photo)]
