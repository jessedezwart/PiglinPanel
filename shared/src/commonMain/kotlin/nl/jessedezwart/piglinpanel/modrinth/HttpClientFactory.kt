package nl.jessedezwart.piglinpanel.modrinth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders

/** Platform HTTP client with the Piglin Panel user agent applied. */
expect fun createHttpClient(): HttpClient

fun HttpClient.withPiglinDefaults(): HttpClient = config {
    defaultRequest {
        headers.append(HttpHeaders.UserAgent, ModrinthClient.USER_AGENT)
    }
}
