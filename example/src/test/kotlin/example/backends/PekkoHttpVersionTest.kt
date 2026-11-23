package example.backends

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers

/** On Pekko HTTP 2, whose default is HTTP/2 on, a Pelican server still answers a client asking to upgrade in 1.1. */
class PekkoHttpVersionTest {

    @Test
    fun `a request with a body that asks to upgrade to HTTP-2 is answered without the upgrade, in HTTP 1-1`() {
        val running = OnPekko.start(port = 0)
        try {
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("${running.baseUrl.trimEnd('/')}/nowhere"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                BodyHandlers.discarding(),
            )

            response.version() shouldBe HttpClient.Version.HTTP_1_1
        } finally {
            running.stop()
        }
    }
}
