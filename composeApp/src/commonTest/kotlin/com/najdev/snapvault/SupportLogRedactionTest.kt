package com.najdev.snapvault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// D18: "Copy logs" put the raw log on the clipboard for pasting into an issue. Legacy import
// diagnostics include Snapchat download links, whose query strings are the credentials that
// fetch a user's memories, and error messages include paths under the user's home folder,
// which carry their account name. Neither is needed to diagnose a problem.
class SupportLogRedactionTest {

    @Test
    fun aDownloadLinksQueryStringIsRemovedButItsHostAndPathStay() {
        val line = "[ERROR] Failed https://app.snapchat.com/dmd/memories?uid=abc123&sid=S3CR3T&mid=m-1&sig=deadbeef"

        val redacted = redactForSupport(line)

        assertEquals("[ERROR] Failed https://app.snapchat.com/dmd/memories?[redacted]", redacted)
    }

    @Test
    fun aLinkEmbeddedInQuotedDiagnosticTextIsRedactedUpToTheQuote() {
        val line = "[DEBUG] raw snippet: downloadMemories('https://app.snapchat.com/dmd/memories?uid=u&sig=s', this, true);"

        val redacted = redactForSupport(line)

        assertFalse("sig=s" in redacted, redacted)
        assertTrue(redacted.endsWith("?[redacted]', this, true);"), redacted)
    }

    // The diagnostic snippet is cut at a fixed length, so a link can arrive with its scheme
    // but without the rest of its query, or a credential can appear without its URL at all.
    @Test
    fun credentialParametersAreRedactedEvenOutsideACompleteUrl() {
        val line = "first onclick decoded=downloadMemories(uid=abc&sid=xyz&X-Amz-Signature=0123"

        val redacted = redactForSupport(line)

        listOf("abc", "xyz", "0123").forEach { assertFalse(it in redacted, "'$it' survived: $redacted") }
    }

    @Test
    fun homeFolderPathsLoseTheAccountName() {
        val lines = listOf(
            "/Users/jane.doe/Pictures/Snap/2024-01-01_x.jpg",
            "/home/jane/Pictures/Snap/2024-01-01_x.jpg",
            """C:\Users\Jane Doe\Pictures\Snap\2024-01-01_x.jpg""",
            "C:/Users/jane/Pictures/Snap/2024-01-01_x.jpg",
        )

        val redacted = lines.map(::redactForSupport)

        assertEquals(
            listOf(
                "~/Pictures/Snap/2024-01-01_x.jpg",
                "~/Pictures/Snap/2024-01-01_x.jpg",
                """~\Pictures\Snap\2024-01-01_x.jpg""",
                "~/Pictures/Snap/2024-01-01_x.jpg",
            ),
            redacted,
        )
    }

    // A space is legal in a Windows account name but also ends most log sentences, so the
    // Windows rule can only take the name up to the next separator. Unix names are taken up
    // to the next '/' or whitespace.
    @Test
    fun aPathMidSentenceKeepsTheRestOfTheSentence() {
        val line = "[ERROR] Could not write vault index: /Users/jane/out/vault_index.json (Permission denied)"

        assertEquals(
            "[ERROR] Could not write vault index: ~/out/vault_index.json (Permission denied)",
            redactForSupport(line),
        )
    }

    @Test
    fun ordinaryLinesAreUnchanged() {
        val line = "[INFO] Extracted 12 new, 3 already existed. See https://github.com/najchris11/snapchat-memories-downloader"

        assertEquals(line, redactForSupport(line))
    }
}
