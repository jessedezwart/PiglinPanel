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
import nl.jessedezwart.piglinpanel.search.ModSource

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
                        "game_versions" to body["game_versions"]!!.jsonArray.map { it.jsonPrimitive.content },
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

        // Told what the server runs, the same pack resolves against one target in one request,
        // so no mod can be offered an update for a different Minecraft version than its neighbours.
        updateRequests.clear()
        client.findSuggestedUpdates(listOf("hash-fabric", "hash-noloader"), loader = "neoforge", gameVersion = "1.21.1")

        assertEquals(1, updateRequests.size)
        assertEquals(listOf("neoforge"), updateRequests.single()["loaders"])
        assertEquals(listOf("1.21.1"), updateRequests.single()["game_versions"])
    }

    @Test
    fun searchEncodesFacetsForLoaderAndGameVersion() = runTest {
        var capturedQuery: String? = null
        val engine = MockEngine { request ->
            capturedQuery = request.url.encodedQuery
            respond(
                """
                {
                  "hits": [ {"project_id":"p1","title":"Sodium","description":"Optimization mod","icon_url":"http://localhost/icon.png","author":"jellysquid","downloads":5000000} ],
                  "offset": 0,
                  "limit": 20,
                  "total_hits": 1
                }
                """.trimIndent(),
                HttpStatusCode.OK,
            )
        }
        val client = ModrinthClient(HttpClient(engine), "http://localhost/v2")

        val page = client.search("sodium", loader = "fabric", mcVersion = "1.21.1", offset = 0, limit = 20)

        assertTrue(capturedQuery!!.contains("categories%3Afabric") || capturedQuery!!.contains("categories:fabric"))
        assertTrue(capturedQuery!!.contains("versions%3A1.21.1") || capturedQuery!!.contains("versions:1.21.1"))
        assertEquals(1, page.hits.size)
        assertEquals("Sodium", page.hits.first().title)
        assertEquals(ModSource.MODRINTH, page.hits.first().source)
        assertEquals(1, page.totalHits)
    }

    @Test
    fun latestCompatibleVersionPrefersReleaseOverBeta() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.fullPath.startsWith("/v2/project/p1/version") -> respond(
                    """
                    [
                      {"id":"beta1","project_id":"p1","name":"Beta","loaders":["fabric"],"game_versions":["1.21.1"],"files":[{"filename":"beta.jar","url":"http://localhost/beta.jar","primary":true}],"date_published":"2026-07-01T00:00:00Z","version_type":"beta"},
                      {"id":"rel1","project_id":"p1","name":"Release","loaders":["fabric"],"game_versions":["1.21.1"],"files":[{"filename":"rel.jar","url":"http://localhost/rel.jar","primary":true}],"date_published":"2026-06-01T00:00:00Z","version_type":"release","dependencies":[{"project_id":"dep1","dependency_type":"required"}]}
                    ]
                    """.trimIndent(),
                    HttpStatusCode.OK,
                )
                else -> respond("unexpected ${request.url}", HttpStatusCode.InternalServerError)
            }
        }
        val client = ModrinthClient(HttpClient(engine), "http://localhost/v2")

        val version = client.latestCompatibleVersion("p1", loader = "fabric", mcVersion = "1.21.1")

        requireNotNull(version)
        assertEquals("rel1", version.versionId)
        assertEquals("rel.jar", version.fileName)
        assertEquals(1, version.dependencies.size)
        assertEquals("dep1", version.dependencies.first().projectId)
        assertTrue(version.dependencies.first().required)
    }

    @Test
    fun readsGameVersionsOnceAndMarksReleases() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            when (request.url.fullPath) {
                "/v2/tag/game_version" -> {
                    calls++
                    respond(
                        """
                        [
                          {"version":"25w05a","version_type":"snapshot"},
                          {"version":"1.21.4","version_type":"release"},
                          {"version":"1.21.4-rc1","version_type":"beta"}
                        ]
                        """.trimIndent(),
                        HttpStatusCode.OK,
                    )
                }
                else -> respond("unexpected ${request.url}", HttpStatusCode.InternalServerError)
            }
        }
        val client = ModrinthClient(HttpClient(engine), "http://localhost/v2")

        val versions = client.gameVersions()
        client.gameVersions()

        assertEquals(listOf("25w05a", "1.21.4", "1.21.4-rc1"), versions.map { it.version })
        assertEquals(listOf("1.21.4"), versions.filter { it.release }.map { it.version })
        assertEquals(1, calls)
    }
}
