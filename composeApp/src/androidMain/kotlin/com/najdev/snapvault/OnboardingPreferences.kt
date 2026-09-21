package com.najdev.snapvault

import android.content.Context

private val prefs get() = ContextHolder.context?.getSharedPreferences("snapvault", Context.MODE_PRIVATE)

actual fun loadOnboardingCompleted(): Boolean = prefs?.getBoolean("onboardingCompleted", false) ?: false

actual fun saveOnboardingCompleted(completed: Boolean) {
    prefs?.edit()?.putBoolean("onboardingCompleted", completed)?.apply()
}

// Scoped storage means there is no folder the user can drag zips into from a file manager and
// SnapVault can then read without a picker grant, so the flow offers the picker instead.
actual fun defaultZipDropFolder(): String? = null
