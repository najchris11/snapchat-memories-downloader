package com.najdev.snapvault

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val RELEASE_DOWNLOAD_URL = "https://github.com/najchris11/snapchat-memories-downloader/releases/latest"

sealed interface UpdateCheckResult {
    data object Current : UpdateCheckResult
    data class Available(val version: String) : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

/** Creates a client only when explicitly called; owns and closes that client. */
class ReleaseUpdateChecker(
    private val currentVersion: String = AppBuildConfig.VERSION,
    private val clientFactory: () -> HttpClient = { HttpClient() },
) {
    suspend fun check(): UpdateCheckResult = try {
        withTimeoutOrNull(15_000) {
            clientFactory().use { client ->
                val response = client.get("https://api.github.com/repos/najchris11/snapchat-memories-downloader/releases/latest") {
                    header("Accept", "application/vnd.github+json")
                    header("User-Agent", "SnapVault/$currentVersion")
                }
                require(response.status.value == 200)
                val release = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                require(release["draft"]?.jsonPrimitive?.booleanOrNull == false)
                require(release["prerelease"]?.jsonPrimitive?.booleanOrNull == false)
                val tag = requireNotNull(release["tag_name"]?.jsonPrimitive?.content)
                val latest = versionParts(tag)
                val installed = versionParts(currentVersion)
                val difference = latest.zip(installed).firstOrNull { (a, b) -> a != b }
                if (difference?.let { (a, b) -> a > b } == true ||
                    (latest == installed && '-' in currentVersion)
                ) UpdateCheckResult.Available(tag) else UpdateCheckResult.Current
            }
        } ?: UpdateCheckResult.Failed
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        UpdateCheckResult.Failed
    }

    private fun versionParts(version: String): List<Int> {
        val core = version.removePrefix("v").substringBefore('+').substringBefore('-')
        require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(core))
        return core.split('.').map { it.toInt() }
    }
}
