package nl.jessedezwart.piglinpanel.modrinth

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import okio.IOException

/**
 * The request plumbing the repository clients share: a GET or a JSON POST that hands back the
 * status and the body, with any failure to get an answer turned into an [IOException] naming the
 * request.
 */
internal class JsonApi(
    @PublishedApi internal val http: HttpClient,
    @PublishedApi internal val json: Json,
    /** How the service is named in error messages, e.g. `Modrinth`. */
    private val service: String,
    /**
     * Headers every request carries. Read before each request is sent, so it may throw an
     * [IOException] to refuse one — that reaches the caller as it is, not wrapped.
     */
    @PublishedApi internal val headers: () -> Map<String, String> = { emptyMap() },
    /** The exception for an answer that is not a 2xx. */
    private val httpError: (statusCode: Int, uri: String) -> IOException,
) {
    class Response(val statusCode: Int, val body: ByteArray) {
        val successful: Boolean get() = statusCode / 100 == 2

        fun bodyText(): String = body.decodeToString()
    }

    suspend fun get(uri: String): Response {
        val headers = headers()
        return send(uri) { http.get(uri) { headers { headers.forEach(::append) } } }
    }

    suspend inline fun <reified T> postJson(uri: String, body: T): Response {
        val headers = headers()
        return send(uri) {
            http.post(uri) {
                headers { headers.forEach(::append) }
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(body))
            }
        }
    }

    fun requireSuccess(response: Response, uri: String) {
        if (!response.successful) throw httpError(response.statusCode, uri)
    }

    @PublishedApi
    internal suspend fun send(uri: String, request: suspend () -> HttpResponse): Response = try {
        val response = request()
        Response(response.status.value, response.readRawBytes())
    } catch (e: Exception) {
        throw IOException("$service request failed for $uri", e)
    }

    companion object {
        /** `key=value` pairs joined for a URL, leaving out the ones without a value. */
        fun queryString(vararg params: Pair<String, String?>): String =
            params.filter { it.second != null }
                .joinToString("&") { (key, value) -> "$key=${value!!.encodeURLParameter()}" }
    }
}
