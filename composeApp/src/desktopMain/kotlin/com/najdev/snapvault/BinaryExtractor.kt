package com.najdev.snapvault

import java.io.File

object BinaryExtractor {

    private val userHome = System.getProperty("user.home")
    val binDir = File(userHome, ".snapvault/bin")

    private val commandCache = HashMap<String, String?>()

    init {
        if (!binDir.exists()) {
            binDir.mkdirs()
        }
    }

    fun getPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") -> {
                if (arch.contains("aarch64") || arch.contains("arm64")) "darwin-arm64" else "darwin-x64"
            }
            os.contains("win") -> "windows-x64"
            else -> "linux-x64"
        }
    }

    // Only successful resolutions are cached: caching a negative result would make the
    // Settings "Refresh" button permanently blind to a tool the user installs while the
    // app is running.
    fun checkCommand(commandName: String): String? = synchronized(commandCache) {
        commandCache[commandName]
            ?: resolveCommand(commandName)?.also { commandCache[commandName] = it }
    }

    private val installer by lazy {
        ToolInstaller(binDir, getPlatform()) { path -> javaClass.getResourceAsStream(path) }
    }

    private fun resolveCommand(commandName: String): String? {
        // 1. A tool on the user's PATH is theirs to choose and theirs to keep up to date.
        if (isCommandInPath(commandName)) {
            return commandName
        }

        // 2. The copy bundled with this build, installed per version (D15). An executable left
        //    in the cache by an earlier build is no longer used just because it exists.
        return installer.install(commandName)?.absolutePath
    }

    private fun isCommandInPath(command: String): Boolean {
        return try {
            val checkCmd = if (System.getProperty("os.name").lowercase().contains("win")) {
                arrayOf("where", command)
            } else {
                arrayOf("which", command)
            }
            val process = ProcessBuilder(*checkCmd).start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
}
