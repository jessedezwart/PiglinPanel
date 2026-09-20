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
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.jessedezwart.piglinpanel.cache.SuspendCache
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.search.BulkCompatibilitySource
import nl.jessedezwart.piglinpanel.search.GameVersion
import nl.jessedezwart.piglinpanel.search.ModDependency
import nl.jessedezwart.piglinpanel.search.ModSearchHit
import nl.jessedezwart.piglinpanel.search.ModSearchPage
import nl.jessedezwart.piglinpanel.search.ModSearchSource
import nl.jessedezwart.piglinpanel.search.ModSource
import nl.jessedezwart.piglinpanel.search.ModVersionInfo
import okio.IOException

class ModrinthHttpException(val statusCode: Int, uri: String) :
    IOException("Modrinth request failed with HTTP $statusCode for $uri")

class ModrinthClient(
    private val http: HttpClient,
    private val apiBaseUrl: String = API_BASE_URL,
) : ModUpdateSource, ModSearchSource, BulkCompatibilitySource {
    companion object {
        const val API_BASE_URL = "https://api.modrinth.com/v2"
        const val USER_AGENT = "Piglinpanel/0.1"

        private const val GAME_VERSIONS_KEY = "game_versions"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val projectsCache =
        SuspendCache<String, Map<String, ModrinthProject>>(ttl = 24.hours, maxEntries = 50)
    private val gameVersionsCache = SuspendCache<String, List<GameVersion>>(ttl = 24.hours, maxEntries = 1)

    /**
     * The Minecraft versions Modrinth indexes, newest first. Cached for a day: the list only grows
     * when Mojang ships something, and every screen that offers a version picker asks for it.
     */
    suspend fun gameVersions(): List<GameVersion> = gameVersionsCache.getOrPut(GAME_VERSIONS_KEY) {
        val uri = "$apiBaseUrl/tag/game_version"
        val response = get(uri)
        requireSuccess(response, uri)
        json.decodeFromString<List<ModrinthGameVersion>>(response.bodyText())
            .map {
                GameVersion(
                    version = it.version,
                    release = it.versionType == "release",
                    releasedAt = it.releasedAtInstant(),
                )
            }
            // Sorted here rather than trusted from the API, so every caller can rely on "newest first".
            .sortedByDescending { it.releasedAt ?: Instant.DISTANT_PAST }
    }

    suspend fun findSuggestedUpdate(sha1: String): ModUpdate? =
        findSuggestedUpdates(listOf(sha1))[sha1]

    /**
     * Resolves suggested updates for the given file hashes. Hashes Modrinth doesn't know are
     * absent from the result; known-but-current mods map to an update whose [ModUpdate.available]
     * is false.
     */
    override suspend fun findSuggestedUpdates(
        sha1s: List<String>,
        loader: String?,
        gameVersion: String?,
    ): Map<String, ModUpdate> {
        val requestedHashes = sha1s.filter { it.isNotBlank() }.distinct()
        if (requestedHashes.isEmpty()) {
            return emptyMap()
        }

        val currentVersions = versionsByHashes(requestedHashes)

        val projectIds = currentVersions.values.mapNotNull { it.projectId }.filter { it.isNotBlank() }.distinct()
        val projects = getProjects(projectIds)

        // Group hashes by their update criteria so one bulk call per loader/game-version set.
        // With the profile's loader and version known this collapses to a single group, which is
        // the point: every mod is then asked about against the same target.
        val hashesByCriteria = LinkedHashMap<UpdateCriteria, MutableList<String>>()
        for ((hash, version) in currentVersions) {
            hashesByCriteria.getOrPut(UpdateCriteria.from(version, loader, gameVersion)) { mutableListOf() }.add(hash)
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
        val version = fetchVersion(update.projectId, update.latestVersionId)
        val file = version.fileByName(update.fileName)
            ?: version.primaryFile()
            ?: throw IOException("No downloadable file found for Modrinth version ${update.latestVersionId}")
        if (file.url.isNullOrBlank()) {
            throw IOException("Modrinth version ${update.latestVersionId} did not provide a download URL")
        }
        return download(file.url)
    }

    override suspend fun changelog(update: ModUpdate): String? {
        update.changelog?.let { return it }
        if (update.projectId == null || update.latestVersionId == null) return null
        return fetchVersion(update.projectId, update.latestVersionId).changelog?.takeIf { it.isNotBlank() }
    }

    override val source: ModSource = ModSource.MODRINTH

    /** Modrinth's public API needs no key. */
    override val available: Boolean = true

    override suspend fun search(
        query: String,
        loader: String?,
        mcVersion: String?,
        offset: Int,
        limit: Int,
    ): ModSearchPage {
        val facets = json.encodeToString(searchFacets(loader, mcVersion))
        val uri = "$apiBaseUrl/search?" + queryString(
            "query" to query,
            "facets" to facets,
            "offset" to offset.toString(),
            "limit" to limit.toString(),
        )
        val response = get(uri)
        requireSuccess(response, uri)
        val result = json.decodeFromString<ModrinthSearchResponse>(response.bodyText())
        return ModSearchPage(
            hits = result.hits.mapNotNull { hit ->
                val projectId = hit.projectId ?: return@mapNotNull null
                ModSearchHit(
                    source = ModSource.MODRINTH,
                    projectId = projectId,
                    title = hit.title?.takeIf { it.isNotBlank() } ?: hit.slug ?: projectId,
                    description = hit.description.orEmpty(),
                    iconUrl = hit.iconUrl,
                    author = hit.author,
                    downloads = hit.downloads,
                )
            },
            totalHits = result.totalHits,
            offset = result.offset,
        )
    }

    override suspend fun latestCompatibleVersion(
        projectId: String,
        loader: String?,
        mcVersion: String?,
    ): ModVersionInfo? {
        val best = projectVersions(projectId, loader, mcVersion)
            .filter { it.primaryFile() != null }
            .sortedWith(
                compareByDescending<ModrinthVersion> { it.versionType == "release" }
                    .thenByDescending { it.publishedAtInstant()?.toEpochMilliseconds() ?: 0L },
            )
            .firstOrNull()
        return best?.let { toVersionInfo(projectId, it) }
    }

    override suspend fun supportedGameVersions(projectId: String, loader: String?): Set<String> =
        projectVersions(projectId, loader, mcVersion = null)
            .filter { it.primaryFile() != null }
            .flatMapTo(LinkedHashSet()) { it.gameVersions.orEmpty() }

    /** A project's versions, narrowed by the repository to [loader] and [mcVersion] where given. */
    private suspend fun projectVersions(
        projectId: String,
        loader: String?,
        mcVersion: String?,
    ): List<ModrinthVersion> {
        val query = queryString(
            "loaders" to loader?.takeIf { it.isNotBlank() }?.let { json.encodeToString(listOf(it)) },
            "game_versions" to mcVersion?.takeIf { it.isNotBlank() }?.let { json.encodeToString(listOf(it)) },
        )
        val uri = "$apiBaseUrl/project/${projectId.encodeURLParameter()}/version" +
            query.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        val response = get(uri)
        requireSuccess(response, uri)
        return json.decodeFromString(response.bodyText())
    }

    /**
     * One request for the whole pack: Modrinth's update endpoint already answers "newest version
     * matching these loaders and game versions" per hash, so a hash it leaves out has no version for
     * the target (or is not a Modrinth file at all).
     */
    override suspend fun findCompatibleVersions(
        sha1s: List<String>,
        loader: String?,
        gameVersion: String,
    ): Map<String, ModVersionInfo> {
        val hashes = sha1s.filter { it.isNotBlank() }.distinct()
        if (hashes.isEmpty()) {
            return emptyMap()
        }
        val versions = latestVersionsForHashes(
            sha1s = hashes,
            loaders = listOfNotNull(loader?.takeIf { it.isNotBlank() }),
            gameVersions = listOf(gameVersion),
        )
        val compatible = LinkedHashMap<String, ModVersionInfo>()
        for ((hash, version) in versions) {
            val projectId = version.projectId ?: continue
            val info = toVersionInfo(projectId, version) ?: continue
            compatible[hash] = info
        }
        return compatible
    }

    override suspend fun version(projectId: String, versionId: String): ModVersionInfo? = try {
        toVersionInfo(projectId, fetchVersion(projectId, versionId))
    } catch (e: ModrinthHttpException) {
        null
    }

    override suspend fun downloadFile(version: ModVersionInfo): ByteArray = download(version.downloadUrl)

    private fun toVersionInfo(projectId: String, version: ModrinthVersion): ModVersionInfo? {
        val versionId = version.id ?: return null
        val file = version.primaryFile() ?: return null
        val fileName = file.filename ?: return null
        val url = file.url ?: return null
        return ModVersionInfo(
            source = ModSource.MODRINTH,
            projectId = projectId,
            versionId = versionId,
            versionName = version.name,
            fileName = fileName,
            downloadUrl = url,
            dependencies = version.dependencies.orEmpty().mapNotNull { dep ->
                val depProjectId = dep.projectId ?: return@mapNotNull null
                if (dep.dependencyType != "required" && dep.dependencyType != "optional") return@mapNotNull null
                ModDependency(
                    source = ModSource.MODRINTH,
                    projectId = depProjectId,
                    versionId = dep.versionId,
                    required = dep.dependencyType == "required",
                )
            },
        )
    }

    private fun searchFacets(loader: String?, mcVersion: String?): List<List<String>> = buildList {
        add(listOf("project_type:mod"))
        loader?.takeIf { it.isNotBlank() }?.let { add(listOf("categories:$it")) }
        mcVersion?.takeIf { it.isNotBlank() }?.let { add(listOf("versions:$it")) }
    }

    private fun queryString(vararg params: Pair<String, String?>): String =
        params.filter { it.second != null }.joinToString("&") { (key, value) -> "$key=${value!!.encodeURLParameter()}" }

    private suspend fun fetchVersion(projectId: String, versionId: String): ModrinthVersion {
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
            library = project?.isLibrary() == true,
            changelog = latestVersion?.changelog?.takeIf { it.isNotBlank() },
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
            /**
             * What the profile states wins; the file's own publication targets fill in whatever it
             * leaves unsaid, which is the best guess available for a profile that has not been
             * told which Minecraft version it runs.
             */
            fun from(version: ModrinthVersion, loader: String?, gameVersion: String?): UpdateCriteria = UpdateCriteria(
                loaders = loader?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                    ?: version.loaders?.takeIf { it.isNotEmpty() }
                    ?: listOf("neoforge"),
                gameVersions = gameVersion?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                    ?: version.gameVersions
                    ?: emptyList(),
            )
        }
    }
}
