package nl.jessedezwart.piglinpanel.curseforge

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.search.ModDependency
import nl.jessedezwart.piglinpanel.search.ModSearchHit
import nl.jessedezwart.piglinpanel.search.ModSearchPage
import nl.jessedezwart.piglinpanel.search.ModSearchSource
import nl.jessedezwart.piglinpanel.search.ModSource
import nl.jessedezwart.piglinpanel.search.ModVersionInfo
import okio.IOException

class CurseForgeHttpException(val statusCode: Int, uri: String) :
    IOException("CurseForge request failed with HTTP $statusCode for $uri")

/** Thrown for 403s, which usually mean a missing/invalid API key. */
class CurseForgeAuthException(uri: String) : IOException("CurseForge rejected the request for $uri; check your API key")

/**
 * CurseForge mod search/install client. [apiKey] is read lazily so a key entered in Settings takes
 * effect immediately without recreating this client.
 */
class CurseForgeClient(
    private val http: HttpClient,
    private val apiKey: () -> String?,
    private val apiBaseUrl: String = API_BASE_URL,
) : ModSearchSource, ModFingerprintUpdateSource {
    companion object {
        const val API_BASE_URL = "https://api.curseforge.com/v1"

        /** Minecraft's CurseForge game id. */
        private const val MINECRAFT_GAME_ID = "432"

        /** The "Mods" class id within the Minecraft game. */
        private const val MOD_CLASS_ID = "6"

        /** How many of a project's files one listing reads. */
        private const val FILE_PAGE_SIZE = 50
    }

    private val json = Json { ignoreUnknownKeys = true }

    override val source: ModSource = ModSource.CURSEFORGE

    override val available: Boolean
        get() = !apiKey().isNullOrBlank()

    override suspend fun search(
        query: String,
        loader: String?,
        mcVersion: String?,
        offset: Int,
        limit: Int,
    ): ModSearchPage {
        val uri = "$apiBaseUrl/mods/search?" + queryString(
            "gameId" to MINECRAFT_GAME_ID,
            "classId" to MOD_CLASS_ID,
            "searchFilter" to query,
            "sortField" to "2",
            "sortOrder" to "desc",
            "index" to offset.toString(),
            "pageSize" to limit.toString(),
            "modLoaderType" to CurseForgeLoaderType.fromName(loader)?.toString(),
            "gameVersion" to mcVersion?.takeIf { it.isNotBlank() },
        )
        val response = get(uri)
        requireSuccess(response, uri)
        val result = json.decodeFromString<CurseForgeSearchResponse>(response.bodyText())
        return ModSearchPage(
            hits = result.data.mapNotNull { mod ->
                val id = mod.id ?: return@mapNotNull null
                ModSearchHit(
                    source = ModSource.CURSEFORGE,
                    projectId = id.toString(),
                    title = mod.name ?: id.toString(),
                    description = mod.summary.orEmpty(),
                    iconUrl = mod.logo?.thumbnailUrl,
                    author = mod.authors.firstOrNull()?.name,
                    downloads = mod.downloadCount,
                )
            },
            totalHits = result.pagination.totalCount,
            offset = result.pagination.index,
        )
    }

    override suspend fun latestCompatibleVersion(
        projectId: String,
        loader: String?,
        mcVersion: String?,
    ): ModVersionInfo? = latestFile(projectId, loader, mcVersion)?.let { toVersionInfo(projectId, it) }

    /**
     * The Minecraft versions among a project's recent files. CurseForge mixes loader names into the
     * same list, so anything not starting with a digit is dropped; only the newest [FILE_PAGE_SIZE]
     * files are read, which covers the versions a mod is still being published for.
     */
    override suspend fun supportedGameVersions(projectId: String, loader: String?): Set<String> =
        files(projectId, loader, mcVersion = null)
            .filter { !it.downloadUrl.isNullOrBlank() }
            .flatMap { it.gameVersions }
            .filterTo(LinkedHashSet()) { it.firstOrNull()?.isDigit() == true }

    private suspend fun latestFile(projectId: String, loader: String?, mcVersion: String?): CurseForgeFile? =
        bestFile(files(projectId, loader, mcVersion), loader = null, mcVersion = null)

    private suspend fun files(projectId: String, loader: String?, mcVersion: String?): List<CurseForgeFile> {
        val uri = "$apiBaseUrl/mods/${projectId.encodeURLParameter()}/files?" + queryString(
            "modLoaderType" to CurseForgeLoaderType.fromName(loader)?.toString(),
            "gameVersion" to mcVersion?.takeIf { it.isNotBlank() },
            "pageSize" to FILE_PAGE_SIZE.toString(),
        )
        val response = get(uri)
        requireSuccess(response, uri)
        return json.decodeFromString<CurseForgeFilesResponse>(response.bodyText()).data
    }

    /**
     * Newest downloadable file, preferring stable releases. [loader]/[mcVersion] filter unfiltered
     * lists (such as `latestFiles`); pass nulls when the server already filtered.
     */
    private fun bestFile(files: List<CurseForgeFile>, loader: String?, mcVersion: String?): CurseForgeFile? = files
        .filter { !it.downloadUrl.isNullOrBlank() }
        .filter { file -> loader == null || loaderOf(file) == null || loaderOf(file) == loader }
        .filter { file -> mcVersion == null || file.gameVersions.contains(mcVersion) }
        .sortedWith(
            compareByDescending<CurseForgeFile> { it.releaseType == CurseForgeReleaseType.RELEASE }
                .thenByDescending { it.fileDate.orEmpty() },
        )
        .firstOrNull()

    override suspend fun version(projectId: String, versionId: String): ModVersionInfo? {
        val uri = "$apiBaseUrl/mods/${projectId.encodeURLParameter()}/files/${versionId.encodeURLParameter()}"
        val response = get(uri)
        if (response.statusCode == 404) return null
        requireSuccess(response, uri)
        val file = json.decodeFromString<CurseForgeFileResponse>(response.bodyText()).data ?: return null
        return toVersionInfo(projectId, file)
    }

    override suspend fun downloadFile(version: ModVersionInfo): ByteArray = download(version.downloadUrl)

    /**
     * Resolves suggested updates for the given file fingerprints (see [CurseForgeFingerprint]).
     * Fingerprints CurseForge doesn't know are absent from the result; known-but-current mods map
     * to an update whose [ModUpdate.available] is false.
     */
    override suspend fun findSuggestedUpdates(fingerprints: List<Long>): Map<Long, ModUpdate> {
        val requested = fingerprints.distinct()
        if (requested.isEmpty()) {
            return emptyMap()
        }

        val matches = exactMatches(requested)
        if (matches.isEmpty()) {
            return emptyMap()
        }

        val mods = getMods(matches.mapNotNull { it.id }.distinct())

        val updates = LinkedHashMap<Long, ModUpdate>()
        for (match in matches) {
            val modId = match.id ?: continue
            val current = match.file ?: continue
            val fingerprint = current.fileFingerprint ?: continue
            if (fingerprint !in requested) continue
            val mod = mods[modId]
            val loader = loaderOf(current)
            val mcVersion = mcVersionOf(current)
            // The bulk /mods response already carries the newest files, so the common case needs no
            // extra request; only mods whose latestFiles hold nothing compatible are queried.
            val latest = bestFile(mod?.latestFiles.orEmpty(), loader, mcVersion)
                ?: try {
                    latestFile(modId.toString(), loader, mcVersion)
                } catch (e: IOException) {
                    null
                }
            updates[fingerprint] = toModUpdate(modId, current, latest, mod)
        }
        return updates
    }

    override suspend fun downloadUpdate(update: ModUpdate): ByteArray {
        val url = update.downloadUrl
            ?: throw IOException("No download URL is available for CurseForge update $update")
        return download(url)
    }

    override suspend fun changelog(update: ModUpdate): String? {
        update.changelog?.let { return it }
        val modId = update.projectId ?: return null
        val fileId = update.latestVersionId ?: return null
        val uri = "$apiBaseUrl/mods/${modId.encodeURLParameter()}/files/${fileId.encodeURLParameter()}/changelog"
        val response = get(uri)
        if (response.statusCode == 404) return null
        requireSuccess(response, uri)
        val html = json.decodeFromString<CurseForgeChangelogResponse>(response.bodyText()).data
        return html?.let(::htmlToMarkdown)?.takeIf { it.isNotBlank() }
    }

    private suspend fun exactMatches(fingerprints: List<Long>): List<CurseForgeFingerprintMatch> {
        val uri = "$apiBaseUrl/fingerprints"
        val response = postJson(uri, FingerprintsRequest(fingerprints))
        requireSuccess(response, uri)
        return json.decodeFromString<CurseForgeFingerprintsResponse>(response.bodyText()).data.exactMatches
    }

    private suspend fun getMods(modIds: List<Int>): Map<Int, CurseForgeMod> {
        if (modIds.isEmpty()) {
            return emptyMap()
        }
        val uri = "$apiBaseUrl/mods"
        val response = postJson(uri, ModsRequest(modIds))
        requireSuccess(response, uri)
        return json.decodeFromString<CurseForgeModsResponse>(response.bodyText()).data
            .mapNotNull { mod -> mod.id?.let { it to mod } }
            .toMap(LinkedHashMap())
    }

    private fun toModUpdate(
        modId: Int,
        current: CurseForgeFile,
        latest: CurseForgeFile?,
        mod: CurseForgeMod?,
    ): ModUpdate {
        val hasDownload = !latest?.downloadUrl.isNullOrBlank() && latest?.fileName != null
        return ModUpdate(
            source = ModSource.CURSEFORGE,
            projectId = modId.toString(),
            projectName = mod?.name,
            currentVersionId = current.id?.toString(),
            latestVersionId = latest?.id?.toString() ?: current.id?.toString(),
            latestVersionName = (latest ?: current).displayName ?: (latest ?: current).fileName,
            lastUpdatedAt = parseInstant((latest ?: current).fileDate),
            downloadUrl = if (hasDownload) latest?.downloadUrl else null,
            fileName = if (hasDownload) latest?.fileName else null,
            iconUrl = mod?.logo?.thumbnailUrl,
            clientSide = null,
            serverSide = null,
            downloads = mod?.downloadCount ?: 0,
            library = mod?.isLibrary() == true,
        )
    }

    private fun parseInstant(value: String?): Instant? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun loaderOf(file: CurseForgeFile): String? =
        file.gameVersions.map { it.lowercase() }.firstOrNull { it in CURSEFORGE_LOADER_NAMES }

    private fun mcVersionOf(file: CurseForgeFile): String? =
        file.gameVersions.firstOrNull { it.firstOrNull()?.isDigit() == true }

    private suspend fun download(url: String): ByteArray {
        val response = get(url)
        if (response.statusCode / 100 != 2) {
            throw CurseForgeHttpException(response.statusCode, url)
        }
        return response.body
    }

    private fun toVersionInfo(projectId: String, file: CurseForgeFile): ModVersionInfo? {
        val versionId = file.id ?: return null
        val fileName = file.fileName ?: return null
        val url = file.downloadUrl ?: return null
        return ModVersionInfo(
            source = ModSource.CURSEFORGE,
            projectId = projectId,
            versionId = versionId.toString(),
            versionName = file.displayName ?: fileName,
            fileName = fileName,
            downloadUrl = url,
            dependencies = file.dependencies.mapNotNull { dep ->
                val depModId = dep.modId ?: return@mapNotNull null
                val required = when (dep.relationType) {
                    CurseForgeRelationType.REQUIRED_DEPENDENCY -> true
                    CurseForgeRelationType.OPTIONAL_DEPENDENCY -> false
                    else -> return@mapNotNull null
                }
                ModDependency(
                    source = ModSource.CURSEFORGE,
                    projectId = depModId.toString(),
                    versionId = null,
                    required = required,
                )
            },
        )
    }

    private fun queryString(vararg params: Pair<String, String?>): String =
        params.filter { it.second != null }.joinToString("&") { (key, value) -> "$key=${value!!.encodeURLParameter()}" }

    private class Response(val statusCode: Int, val body: ByteArray) {
        fun bodyText(): String = body.decodeToString()
    }

    private suspend fun get(uri: String): Response = try {
        val key = apiKey()
        if (key.isNullOrBlank()) {
            throw IOException("CurseForge API key is not configured")
        }
        toResponse(http.get(uri) { header("x-api-key", key) })
    } catch (e: CurseForgeHttpException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("CurseForge request failed for $uri", e)
    }

    private suspend inline fun <reified T> postJson(uri: String, body: T): Response = try {
        val key = apiKey()
        if (key.isNullOrBlank()) {
            throw IOException("CurseForge API key is not configured")
        }
        toResponse(
            http.post(uri) {
                header("x-api-key", key)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(body))
            },
        )
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("CurseForge request failed for $uri", e)
    }

    private suspend fun toResponse(response: HttpResponse): Response =
        Response(response.status.value, response.readRawBytes())

    private fun requireSuccess(response: Response, uri: String) {
        if (response.statusCode == 403) {
            throw CurseForgeAuthException(uri)
        }
        if (response.statusCode / 100 != 2) {
            throw CurseForgeHttpException(response.statusCode, uri)
        }
    }

    @Serializable
    data class FingerprintsRequest(val fingerprints: List<Long>)

    @Serializable
    data class ModsRequest(val modIds: List<Int>)
}
