package com.najdev.snapvault

import platform.Foundation.NSUserDefaults

private val defaults get() = NSUserDefaults.standardUserDefaults

actual fun loadOnboardingCompleted(): Boolean = defaults.boolForKey("onboardingCompleted")

actual fun saveOnboardingCompleted(completed: Boolean) {
    defaults.setBool(completed, "onboardingCompleted")
}

// The app sandbox has no folder the user reaches from outside it, so there is nothing to
// offer to create; the flow points at the document picker instead.
actual fun defaultZipDropFolder(): String? = null
