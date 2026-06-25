package com.najdev.snapvault

import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

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

    fun checkCommand(commandName: String): String? = synchronized(commandCache) {
        if (commandName in commandCache) commandCache[commandName]
        else resolveCommand(commandName).also { commandCache[commandName] = it }
    }

    private fun resolveCommand(commandName: String): String? {
        // 1. Check system PATH
        if (isCommandInPath(commandName)) {
            return commandName
        }

        // 2. Check local bin directory
        val exeName = if (getPlatform().startsWith("windows")) "$commandName.exe" else commandName
        val localFile = File(binDir, exeName)
        if (localFile.exists() && localFile.canExecute()) {
            return localFile.absolutePath
        }

        // 3. Extract from bundled zip in JAR resources
        val platform = getPlatform()
        val zipResourcePath = "/bin/$platform/$commandName.zip"
        val success = extractZipResource(zipResourcePath, binDir)
        if (success && localFile.exists()) {
            localFile.setExecutable(true, false)

            if (!platform.startsWith("windows")) {
                localFile.setExecutable(true, false)
                binDir.listFiles()?.forEach { file ->
                    if (file.isFile) file.setExecutable(true, false)
                }
            }

            return localFile.absolutePath
        }

        return null
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

    private fun extractZipResource(resourcePath: String, destDir: File): Boolean {
        val stream: InputStream = javaClass.getResourceAsStream(resourcePath) ?: return false
        return try {
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val filePath = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        filePath.mkdirs()
                    } else {
                        filePath.parentFile?.mkdirs()
                        FileOutputStream(filePath).use { output ->
                            zip.copyTo(output)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            true
        } catch (e: IOException) {
            false
        }
    }
}
