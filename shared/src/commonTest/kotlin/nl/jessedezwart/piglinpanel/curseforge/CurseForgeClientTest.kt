package nl.jessedezwart.piglinpanel.curseforge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.search.ModSource

class CurseForgeClientTest {
    @Test
    fun unavailableWhenApiKeyBlank() {
        val client = CurseForgeClient(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }), { "" })
        assertFalse(client.available)

        val withKey = CurseForgeClient(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }), { "key" })
        assertTrue(withKey.available)
    }

    @Test
    fun searchSendsApiKeyHeaderAndLoaderFilter() = runTest {
        var capturedHeader: String? = null
        var capturedQuery: String? = null
        val engine = MockEngine { request ->
            capturedHeader = request.headers["x-api-key"]
            capturedQuery = request.url.encodedQuery
            respond(
                """
                {
                  "data": [ {"id": 42, "name": "JEI", "summary": "Item viewer", "logo": {"thumbnailUrl": "http://localhost/logo.png"}, "authors": [{"name":"mezz"}], "downloadCount": 999} ],
                  "pagination": {"index": 0, "pageSize": 20, "resultCount": 1, "totalCount": 1}
                }
                """.trimIndent(),
                HttpStatusCode.OK,
            )
        }
        val client = CurseForgeClient(HttpClient(engine), { "test-key" }, "http://localhost/v1")

        val page = client.search("jei", loader = "fabric", mcVersion = "1.21.1", offset = 0, limit = 20)

        assertEquals("test-key", capturedHeader)
        assertTrue(capturedQuery!!.contains("modLoaderType=4"))
        assertEquals(1, page.hits.size)
        assertEquals("JEI", page.hits.first().title)
        assertEquals(ModSource.CURSEFORGE, page.hits.first().source)
        assertEquals("42", page.hits.first().projectId)
    }

    @Test
    fun throws403AsAuthException() = runTest {
        val engine = MockEngine { respond("""{"error":"forbidden"}""", HttpStatusCode.Forbidden) }
        val client = CurseForgeClient(HttpClient(engine), { "bad-key" }, "http://localhost/v1")

        assertFailsWith<CurseForgeAuthException> {
            client.search("jei", null, null, 0, 20)
        }
    }

    @Test
    fun latestCompatibleVersionPrefersReleaseAndMapsRequiredDependency() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/files") -> respond(
                    """
                    {
                      "data": [
                        {"id": 100, "modId": 42, "fileName": "jei-beta.jar", "displayName": "Beta", "downloadUrl": "http://localhost/beta.jar", "releaseType": 2, "fileDate": "2026-07-01T00:00:00Z", "dependencies": []},
                        {"id": 101, "modId": 42, "fileName": "jei-release.jar", "displayName": "Release", "downloadUrl": "http://localhost/release.jar", "releaseType": 1, "fileDate": "2026-06-01T00:00:00Z", "dependencies": [{"modId": 7, "relationType": 3}, {"modId": 8, "relationType": 2}, {"modId": 9, "relationType": 5}]}
                      ],
                      "pagination": {"index": 0, "pageSize": 50, "resultCount": 2, "totalCount": 2}
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK,
                )
                else -> respond("unexpected ${request.url}", HttpStatusCode.InternalServerError)
            }
        }
        val client = CurseForgeClient(HttpClient(engine), { "key" }, "http://localhost/v1")

        val version = client.latestCompatibleVersion("42", loader = "fabric", mcVersion = "1.21.1")

        requireNotNull(version)
        assertEquals("101", version.versionId)
        assertEquals("jei-release.jar", version.fileName)
        assertEquals(2, version.dependencies.size)
        val required = version.dependencies.single { it.required }
        assertEquals("7", required.projectId)
        val optional = version.dependencies.single { !it.required }
        assertEquals("8", optional.projectId)
    }

    @Test
    fun versionReturnsNullOn404() = runTest {
        val engine = MockEngine { respond("""{"error":"not_found"}""", HttpStatusCode.NotFound) }
        val client = CurseForgeClient(HttpClient(engine), { "key" }, "http://localhost/v1")

        assertNull(client.version("42", "999"))
    }
}
