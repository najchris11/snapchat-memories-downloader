package com.najdev.snapvault.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsDependencyVisibilityTest {

    @Test
    fun dependencySectionOnlyShowsOnPlatformsThatUseExternalBinaries() {
        assertTrue(showsDependencySection(isAndroid = false, isIos = false))
        assertFalse(showsDependencySection(isAndroid = true, isIos = false))
        assertFalse(showsDependencySection(isAndroid = false, isIos = true))
        assertFalse(showsDependencySection(isAndroid = true, isIos = true))
    }
}
