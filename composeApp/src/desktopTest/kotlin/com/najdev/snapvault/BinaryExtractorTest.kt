package com.najdev.snapvault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BinaryExtractorTest {

    @Test
    fun testGetPlatform() {
        val platform = BinaryExtractor.getPlatform()
        assertTrue(
            platform == "darwin-arm64" || 
            platform == "darwin-x64" || 
            platform == "windows-x64" || 
            platform == "linux-x64",
            "Platform must be one of the supported KMP desktop targets, got: $platform"
        )
    }

    @Test
    fun testBinDir() {
        val binDir = BinaryExtractor.binDir
        val expected = ".snapvault${File.separator}bin"
        assertEquals(expected, binDir.toString().substringAfter(System.getProperty("user.home") + File.separator))
    }

    @Test
    fun testCheckCommandSystemPath() {
        // Since we are running JUnit/Kotlin tests, 'java' must be in the system PATH.
        val javaPath = BinaryExtractor.checkCommand("java")
        assertNotNull(javaPath, "Should find 'java' command in the system PATH")
    }
}
