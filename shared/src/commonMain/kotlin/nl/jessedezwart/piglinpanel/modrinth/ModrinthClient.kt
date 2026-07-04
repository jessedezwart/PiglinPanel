package nl.jessedezwart.piglinpanel.modrinth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.jessedezwart.piglinpanel.cache.SuspendCache
import nl.jessedezwart.piglinpanel.model.ModUpdate
import okio.IOException

class ModrinthHttpException(val statusCode: Int, uri: String) :
    IOException("Modrinth request failed with HTTP $statusCode for $uri")

class ModrinthClient(
    private val http: HttpClient,
    private val apiBaseUrl: String = API_BASE_URL,
) : ModUpdateSource {
    companion object {
        const val API_BASE_URL = "https://api.modrinth.com/v2"
        const val USER_AGENT = "Piglinpanel/0.1"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val projectsCache =
        SuspendCache<String, Map<String, ModrinthProject>>(ttl = 24.hours, maxEntries = 50)

    suspend fun findSuggestedUpdate(sha1: String): ModUpdate? =
        findSuggestedUpdates(listOf(sha1))[sha1]

    /**
     * Resolves suggested updates for the given file hashes. Hashes Modrinth doesn't know are
     * absent from the result; known-but-current mods map to an update whose [ModUpdate.available]
     * is false.
     */
    override suspend fun findSuggestedUpdates(sha1s: List<String>): Map<String, ModUpdate> {
        val requestedHashes = sha1s.filter { it.isNotBlank() }.distinct()
        if (requestedHashes.isEmpty()) {
            return emptyMap()
        }

        val currentVersions = versionsByHashes(requestedHashes)

        val projectIds = currentVersions.values.mapNotNull { it.projectId }.filter { it.isNotBlank() }.distinct()
        val projects = getProjects(projectIds)

        // Group hashes by their update criteria so one bulk call per loader/game-version set.
        val hashesByCriteria = LinkedHashMap<UpdateCriteria, MutableList<String>>()
        for ((hash, version) in currentVersions) {
            hashesByCriteria.getOrPut(UpdateCriteria.from(version)) { mutableListOf() }.add(hash)
        }

        val latestVersions = LinkedHashMap<String, ModrinthVersion>()
        for ((criteria, hashes) in hashesByCriteria) {
            latestVersions.putAll(latestVersionsForHashes(hashes, criteria.loaders, criteria.gameVersions))
        }

        val updates = LinkedHashMap<String, ModUpdate>()
        for (sha1 in requestedHashes) {
            val currentVersion = currentVersions[sha1] ?: continue
            updates[sha1] = toModUpdate(currentVersion, latestVersions[sha1], projects[currentVersion.projectId])
        }
        return updates
    }

    suspend fun getProjects(projectIds: List<String>): Map<String, ModrinthProject> {
        if (projectIds.isEmpty()) {
            return emptyMap()
        }
        return projectsCache.getOrPut(projectIds.sorted().joinToString(",")) {
            val idsJson = json.encodeToString(projectIds)
            val uri = "$apiBaseUrl/projects?ids=${idsJson.encodeURLParameter()}"
            val response = get(uri)
            requireSuccess(response, uri)
            val projects = json.decodeFromString<List<ModrinthProject>>(response.bodyText())
            buildMap {
                for (project in projects) {
                    val id = project.id ?: continue
                    if (!containsKey(id)) put(id, project)
                }
            }
        }
    }

    suspend fun download(uri: String): ByteArray {
        val response = get(uri)
        if (response.statusCode / 100 != 2) {
            throw ModrinthHttpException(response.statusCode, uri)
        }
        return response.body
    }

    override suspend fun downloadUpdate(update: ModUpdate): ByteArray {
        if (update.downloadUrl != null) {
            try {
                return download(update.downloadUrl)
            } catch (e: ModrinthHttpException) {
                if (e.statusCode != 404 ||
                    update.projectId == null ||
                    update.latestVersionId == null ||
                    update.fileName.isNullOrBlank()
                ) {
                    throw e
                }
                // Stored URL is stale; refresh version metadata below and retry.
            }
        }
        if (update.projectId == null || update.latestVersionId == null) {
            throw IOException("No download metadata is available for update $update")
        }
        val version = version(update.projectId, update.latestVersionId)
        val file = version.fileByName(update.fileName)
            ?: version.primaryFile()
            ?: throw IOException("No downloadable file found for Modrinth version ${update.latestVersionId}")
        if (file.url.isNullOrBlank()) {
            throw IOException("Modrinth version ${update.latestVersionId} did not provide a download URL")
        }
        return download(file.url)
    }

    private suspend fun version(projectId: String, versionId: String): ModrinthVersion {
        val uri = "$apiBaseUrl/project/${projectId.encodeURLParameter()}/version/${versionId.encodeURLParameter()}"
        val response = get(uri)
        requireSuccess(response, uri)
        return json.decodeFromString(response.bodyText())
    }

    private suspend fun versionsByHashes(sha1s: List<String>): Map<String, ModrinthVersion> {
        val uri = "$apiBaseUrl/version_files"
        val response = postJson(uri, HashesRequest(hashes = sha1s, algorithm = "sha1"))
        requireSuccess(response, uri)
        return readVersionMap(response.bodyText())
    }

    private suspend fun latestVersionsForHashes(
        sha1s: List<String>,
        loaders: List<String>,
        gameVersions: List<String>,
    ): Map<String, ModrinthVersion> {
        val uri = "$apiBaseUrl/version_files/update"
        val response = postJson(
            uri,
            UpdateRequest(hashes = sha1s, algorithm = "sha1", loaders = loaders, gameVersions = gameVersions),
        )
        requireSuccess(response, uri)
        return readVersionMap(response.bodyText())
    }

    private fun toModUpdate(
        currentVersion: ModrinthVersion,
        latestVersion: ModrinthVersion?,
        project: ModrinthProject?,
    ): ModUpdate {
        val latestFile = latestVersion?.primaryFile()
        val hasDownload = latestFile?.url != null && latestFile.filename != null
        return ModUpdate(
            projectId = currentVersion.projectId,
            projectName = project?.title,
            currentVersionId = currentVersion.id,
            latestVersionId = latestVersion?.id ?: currentVersion.id,
            latestVersionName = latestVersion?.name ?: currentVersion.name,
            lastUpdatedAt = (latestVersion ?: currentVersion).publishedAtInstant(),
            downloadUrl = if (hasDownload) latestFile?.url else null,
            fileName = if (hasDownload) latestFile?.filename else null,
            iconUrl = project?.iconUrl,
            clientSide = project?.clientSide,
            serverSide = project?.serverSide,
            downloads = project?.downloads ?: 0,
        )
    }

    private fun readVersionMap(body: String): Map<String, ModrinthVersion> =
        json.decodeFromString<Map<String, ModrinthVersion?>>(body)
            .mapNotNull { (key, value) -> value?.let { key to it } }
            .toMap(LinkedHashMap())

    private class Response(val statusCode: Int, val body: ByteArray) {
        fun bodyText(): String = body.decodeToString()
    }

    private suspend fun get(uri: String): Response = try {
        toResponse(http.get(uri))
    } catch (e: ModrinthHttpException) {
        throw e
    } catch (e: Exception) {
        throw IOException("Modrinth request failed for $uri", e)
    }

    private suspend inline fun <reified T> postJson(uri: String, body: T): Response = try {
        toResponse(
            http.post(uri) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(body))
            },
        )
    } catch (e: Exception) {
        throw IOException("Modrinth request failed for $uri", e)
    }

    private suspend fun toResponse(response: HttpResponse): Response =
        Response(response.status.value, response.readRawBytes())

    private fun requireSuccess(response: Response, uri: String) {
        if (response.statusCode / 100 != 2) {
            throw ModrinthHttpException(response.statusCode, uri)
        }
    }

    @Serializable
    private data class HashesRequest(val hashes: List<String>, val algorithm: String)

    @Serializable
    private data class UpdateRequest(
        val hashes: List<String>,
        val algorithm: String,
        val loaders: List<String>,
        @kotlinx.serialization.SerialName("game_versions") val gameVersions: List<String>,
    )

    private data class UpdateCriteria(val loaders: List<String>, val gameVersions: List<String>) {
        companion object {
            fun from(version: ModrinthVersion): UpdateCriteria = UpdateCriteria(
                loaders = version.loaders?.takeIf { it.isNotEmpty() } ?: listOf("neoforge"),
                gameVersions = version.gameVersions ?: emptyList(),
            )
        }
    }
}
