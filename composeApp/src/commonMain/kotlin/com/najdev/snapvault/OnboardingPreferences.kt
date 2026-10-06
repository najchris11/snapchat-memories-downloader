package com.najdev.snapvault

// Whether the getting-started flow has been finished or skipped. Kept beside the other
// preferences rather than inside the vault index, because it describes this installation
// rather than a library — a user pointing SnapVault at a second output folder has still seen
// the flow, and resetting the index must not bring the takeover back.
expect fun loadOnboardingCompleted(): Boolean
expect fun saveOnboardingCompleted(completed: Boolean)

// The folder the getting-started flow offers to create for incoming zips. Null where the
// platform has no user-visible one to drop files into, which is both mobile targets — the
// step then renders its instructions without a create button.
expect fun defaultZipDropFolder(): String?
