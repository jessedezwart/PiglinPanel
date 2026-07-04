package nl.jessedezwart.piglinpanel.modrinth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.jessedezwart.piglinpanel.model.ModUpdate

class ModrinthClientTest {
    private fun requestBodyJson(request: HttpRequestData) =
        Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    @Test
    fun refreshesVersionMetadataWhenStoredDownloadUrlReturns404() = runTest {
        var staleDownloadCalls = 0
        val engine = MockEngine { request ->
            when (request.url.fullPath) {
                "/stale/latest.jar" -> {
                    staleDownloadCalls++
                    respond("""{"error":"not_found"}""", HttpStatusCode.NotFound)
                }
                "/v2/project/project/version/latest" -> respond(
                    """
                    {
                      "id":"latest",
                      "project_id":"project",
                      "name":"Latest",
                      "loaders":["fabric"],
                      "game_versions":["1.21.1"],
                      "files":[{"filename":"latest.jar","url":"http://localhost/downloads/latest.jar","primary":true}],
                      "date_published":"2026-06-10T12:00:00Z"
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK,
                )
                "/downloads/latest.jar" -> respond("jar-bytes", HttpStatusCode.OK)
                else -> respond("unexpected ${request.url}", HttpStatusCode.InternalServerError)
            }
        }
        val client = ModrinthClient(HttpClient(engine), "http://localhost/v2")

        val bytes = client.downloadUpdate(
            ModUpdate(
                projectId = "project",
                projectName = "Project",
                currentVersionId = "current",
                latestVersionId = "latest",
                latestVersionName = "Latest",
                lastUpdatedAt = Instant.parse("2026-06-10T12:00:00Z"),
                downloadUrl = "http://localhost/stale/latest.jar",
                fileName = "latest.jar",
                iconUrl = null,
                clientSide = null,
                serverSide = null,
                downloads = 0,
            ),
        )

        assertEquals("jar-bytes", bytes.decodeToString())
        assertEquals(1, staleDownloadCalls)
    }

    @Test
    fun batchesUpdateLookupsByLoaderAndGameVersionCriteria() = runTest {
        val updateRequests = mutableListOf<Map<String, Any?>>()
        val engine = MockEngine { request ->
            when (request.url.fullPath) {
                "/v2/version_files" -> respond(
                    """
                    {
                      "hash-fabric": {"id":"v1","project_id":"p1","name":"1.0","loaders":["fabric"],"game_versions":["1.21.1"],"files":[],"date_published":"2026-01-01T00:00:00Z"},
                      "hash-noloader": {"id":"v2","project_id":"p2","name":"1.0","loaders":[],"game_versions":[],"files":[],"date_published":"2026-01-01T00:00:00Z"}
                    }
                    """.trimIndent(),
                    HttpStatusCode.OK,
                )
                "/v2/version_files/update" -> {
                    val body = requestBodyJson(request)
                    updateRequests += mapOf(
                        "hashes" to body["hashes"]!!.jsonArray.map { it.jsonPrimitive.content },
                        "loaders" to body["loaders"]!!.jsonArray.map { it.jsonPrimitive.content },
                    )
                    respond(
                        """
                        {
                          "hash-fabric": {"id":"v9","project_id":"p1","name":"2.0","loaders":["fabric"],"game_versions":["1.21.1"],"files":[{"filename":"new.jar","url":"http://localhost/new.jar","primary":true}],"date_published":"2026-06-01T00:00:00Z"}
                        }
                        """.trimIndent(),
                        HttpStatusCode.OK,
                    )
                }
                else -> {
                    assertTrue(request.url.fullPath.startsWith("/v2/projects"), "unexpected ${request.url}")
                    respond(
                        """[{"id":"p1","title":"Project One","downloads":1500000},{"id":"p2","title":"Project Two","downloads":10}]""",
                        HttpStatusCode.OK,
                    )
                }
            }
        }
        val client = ModrinthClient(HttpClient(engine), "http://localhost/v2")

        val updates = client.findSuggestedUpdates(listOf("hash-fabric", "hash-noloader", "hash-unknown"))

        // Two criteria groups: fabric/[1.21.1] and the neoforge fallback for the empty loader list.
        assertEquals(2, updateRequests.size)
        val loaderGroups = updateRequests.map { it["loaders"] }.toSet()
        assertTrue(listOf("fabric") in loaderGroups)
        assertTrue(listOf("neoforge") in loaderGroups)

        val fabricUpdate = updates.getValue("hash-fabric")
        assertTrue(fabricUpdate.available())
        assertEquals("2.0", fabricUpdate.latestVersionName)
        assertEquals("Project One", fabricUpdate.projectName)
        assertEquals("1.5M", fabricUpdate.formattedDownloads())

        // Known version but no newer one: update present but not available.
        val noLoaderUpdate = updates.getValue("hash-noloader")
        assertEquals(noLoaderUpdate.currentVersionId, noLoaderUpdate.latestVersionId)
        assertTrue(!noLoaderUpdate.available())

        // Unknown hash: absent entirely.
        assertTrue("hash-unknown" !in updates)
    }
}
