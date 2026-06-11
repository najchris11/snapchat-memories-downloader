package com.najdev.snapvault

expect fun loadThemePreference(): Boolean
expect fun saveThemePreference(dark: Boolean)

expect fun loadWorkersPreference(): Int
expect fun saveWorkersPreference(workers: Int)

expect fun loadLayoutOverride(): LayoutOverride
expect fun saveLayoutOverride(override: LayoutOverride)
