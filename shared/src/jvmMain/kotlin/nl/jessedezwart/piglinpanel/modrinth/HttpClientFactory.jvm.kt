package nl.jessedezwart.piglinpanel.modrinth

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    followRedirects = true
    defaultRequest {
        headers.append(HttpHeaders.UserAgent, ModrinthClient.USER_AGENT)
    }
}
