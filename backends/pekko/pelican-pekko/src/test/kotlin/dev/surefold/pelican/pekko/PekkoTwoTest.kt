package dev.surefold.pelican.pekko

import com.typesafe.config.ConfigFactory
import io.kotest.matchers.shouldBe
import org.apache.pekko.http.javadsl.model.HttpMethods
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.headers.Host
import org.junit.jupiter.api.Test

/** What a service on Pekko HTTP 2 sends that one on 1.x did not: HTTP/2 by upgrade, and its own `QUERY`. */
class PekkoTwoTest {

    @Test
    fun `an HTTP-2 request carries its host as the authority, and that is its Host`() {
        val request = HttpRequest.create().withUri("http://shop.example:8080/notes")

        PekkoView(request).header("Host") shouldBe "shop.example:8080"
    }

    @Test
    fun `an authority without a port gives a Host without one`() {
        PekkoView(HttpRequest.create().withUri("http://shop.example/notes")).header("Host") shouldBe "shop.example"
    }

    @Test
    fun `a Host header, where the request has one, is read as sent`() {
        val request = HttpRequest.create().withUri("/notes").addHeader(Host.create("shop.example", 8080))

        PekkoView(request).header("Host") shouldBe "shop.example:8080"
    }

    @Test
    fun `HTTP-2 is off where only Pekko's defaults speak, as Pekko 1 had it`() {
        serverConfig(ConfigFactory.defaultReference()).getBoolean(ENABLE_HTTP2) shouldBe false
    }

    @Test
    fun `HTTP-2 is on where the service turned it on`() {
        val chosen = ConfigFactory.parseString("$ENABLE_HTTP2 = on").withFallback(ConfigFactory.defaultReference())

        serverConfig(chosen).getBoolean(ENABLE_HTTP2) shouldBe true
    }

    @Test
    fun `QUERY is Pekko's own where Pekko has one, so the parser's method and the route's are equal`() {
        HttpMethods.lookup("QUERY").map { it == PEKKO_QUERY }.orElse(true) shouldBe true
        PEKKO_QUERY.name() shouldBe "QUERY"
    }
}
