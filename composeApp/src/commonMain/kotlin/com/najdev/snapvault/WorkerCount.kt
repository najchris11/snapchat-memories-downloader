package com.najdev.snapvault

/**
 * The most pipeline workers SnapVault runs at once, however many CPUs there are.
 *
 * Workers scaled with 75% of the CPUs and nothing else (D13). Each can hold a fully decoded
 * image while combining, so on a 64-core workstation that was 48 full-resolution decodes at
 * once — memory, not CPU, was the budget that ran out, and the machine went unresponsive
 * before the run did. The work is mostly I/O and child processes anyway; past a handful of
 * workers, more of them mostly means more memory.
 */
internal const val MAX_WORKERS = 8

internal fun workerCountFor(processors: Int): Int = (processors * 0.75).toInt().coerceIn(1, MAX_WORKERS)
