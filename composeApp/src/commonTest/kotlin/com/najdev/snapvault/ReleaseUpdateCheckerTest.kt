package com.najdev.snapvault

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseUpdateCheckerTest {
    @Test
    fun checksOnlyOnRequestAndComparesVersionComponentsNumerically() = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            requests++
            assertEquals("https://api.github.com/repos/najchris11/snapchat-memories-downloader/releases/latest", request.url.toString())
            respond("""{"tag_name":"v1.0.16","draft":false,"prerelease":false}""")
        })
        val checker = ReleaseUpdateChecker("1.0.9") { client }
        assertEquals(0, requests)
        assertEquals(UpdateCheckResult.Available("v1.0.16"), checker.check())
        assertEquals(1, requests)
    }
    @Test
    fun equalAndOlderVersionsAreCurrentAndFailuresAreRetryableResults() = runBlocking {
        suspend fun check(tag: String, status: HttpStatusCode = HttpStatusCode.OK): UpdateCheckResult =
            ReleaseUpdateChecker("1.0.16") {
                HttpClient(MockEngine { respond(tag, status) })
            }.check()
        assertEquals(UpdateCheckResult.Current, check("""{"tag_name":"v1.0.16","draft":false,"prerelease":false}"""))
        assertEquals(UpdateCheckResult.Current, check("""{"tag_name":"v1.0.9","draft":false,"prerelease":false}"""))
        assertEquals(UpdateCheckResult.Failed, check("rate limited", HttpStatusCode.Forbidden))
        assertEquals(UpdateCheckResult.Failed, check("not JSON"))
        assertEquals(UpdateCheckResult.Failed, check("""{"tag_name":"unknown","draft":false,"prerelease":false}"""))
        assertEquals(UpdateCheckResult.Failed, check("""{"tag_name":"v2.0.0","draft":false,"prerelease":true}"""))
    }
}
