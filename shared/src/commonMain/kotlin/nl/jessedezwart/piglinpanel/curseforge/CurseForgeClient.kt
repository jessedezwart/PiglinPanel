package nl.jessedezwart.piglinpanel.curseforge

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
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
) : ModSearchSource {
    companion object {
        const val API_BASE_URL = "https://api.curseforge.com/v1"

        /** Minecraft's CurseForge game id. */
        private const val MINECRAFT_GAME_ID = "432"

        /** The "Mods" class id within the Minecraft game. */
        private const val MOD_CLASS_ID = "6"
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
    ): ModVersionInfo? {
        val uri = "$apiBaseUrl/mods/${projectId.encodeURLParameter()}/files?" + queryString(
            "modLoaderType" to CurseForgeLoaderType.fromName(loader)?.toString(),
            "gameVersion" to mcVersion?.takeIf { it.isNotBlank() },
            "pageSize" to "50",
        )
        val response = get(uri)
        requireSuccess(response, uri)
        val result = json.decodeFromString<CurseForgeFilesResponse>(response.bodyText())
        val best = result.data
            .filter { !it.downloadUrl.isNullOrBlank() }
            .sortedWith(
                compareByDescending<CurseForgeFile> { it.releaseType == CurseForgeReleaseType.RELEASE }
                    .thenByDescending { it.fileDate.orEmpty() },
            )
            .firstOrNull()
        return best?.let { toVersionInfo(projectId, it) }
    }

    override suspend fun version(projectId: String, versionId: String): ModVersionInfo? {
        val uri = "$apiBaseUrl/mods/${projectId.encodeURLParameter()}/files/${versionId.encodeURLParameter()}"
        val response = get(uri)
        if (response.statusCode == 404) return null
        requireSuccess(response, uri)
        val file = json.decodeFromString<CurseForgeFileResponse>(response.bodyText()).data ?: return null
        return toVersionInfo(projectId, file)
    }

    override suspend fun downloadFile(version: ModVersionInfo): ByteArray {
        val response = get(version.downloadUrl)
        if (response.statusCode / 100 != 2) {
            throw CurseForgeHttpException(response.statusCode, version.downloadUrl)
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
}
