package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.RawHttp.Answer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The session that holds the production unlock, as the browser receives it. Asserting the
 * properties in application.yml says what was asked for; this says what the server sends: the
 * cookie's attributes on the wire, and that the session is carried by the cookie and by nothing
 * else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionOverTheServerTest {

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
        registry.add("config-store.allow-prod-writes", () -> "true");
    }

    @LocalServerPort
    int port;

    private Answer post(String address, String contentType, byte[] body, String... headers) throws IOException {
        String[] all = new String[headers.length + 3];
        all[0] = "Host: localhost:" + port;
        all[1] = "Content-Type: " + contentType;
        all[2] = "Content-Length: " + body.length;
        System.arraycopy(headers, 0, all, 3, headers.length);
        return RawHttp.send(port, RawHttp.head("POST " + address + " HTTP/1.1", all), body);
    }

    @Test
    void theUnlockIsHeldInACookieThatNoScriptAndNoOtherSiteGetsAndInNothingElse() throws IOException {
        byte[] environment;
        try (InputStream in = getClass().getResourceAsStream("/postman/onyx-production.postman_environment.json")) {
            environment = in.readAllBytes();
        }
        String boundary = "edge-session";
        byte[] upload = RawHttp.multipart(boundary, "kind", null, "prod".getBytes(StandardCharsets.UTF_8), "file",
                "onyx-production.postman_environment.json", environment);
        assertThat(post("/api/config/environments", "multipart/form-data; boundary=" + boundary, upload).status()).isEqualTo(200);
        assertThat(post("/api/config/environments/Onyx%20Production/activate", "application/json", new byte[0]).status()).isEqualTo(200);

        Answer unlocked = post("/api/config/writes", "application/json",
                "{\"action\":\"unlock\",\"environment\":\"Onyx Production\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(unlocked.status()).isEqualTo(200);
        assertThat(unlocked.json().get("writesAllowed").asBoolean()).isTrue();
        String cookie = unlocked.header("Set-Cookie");
        assertThat(cookie).matches("PAVESESSION=[0-9A-F]{32}; Path=/; HttpOnly; SameSite=Strict");
        String session = cookie.substring("PAVESESSION=".length(), cookie.indexOf(';'));

        // Carried by the cookie, the session holds the unlock.
        Answer withCookie = RawHttp.get(port, "GET /api/mode HTTP/1.1", "Host: localhost:" + port, "Cookie: PAVESESSION=" + session);
        assertThat(withCookie.json().at("/writeAccess/writesAllowed").asBoolean()).isTrue();
        // Written into the address, it is nothing: the session is tracked by the cookie only.
        Answer inTheAddress = RawHttp.get(port, "GET /api/mode;PAVESESSION=" + session + " HTTP/1.1", "Host: localhost:" + port);
        assertThat(inTheAddress.status()).isEqualTo(400);
        assertThat(inTheAddress.body()).doesNotContain("writesAllowed").doesNotContain(session);
        Answer asAParameter = RawHttp.get(port, "GET /api/mode?PAVESESSION=" + session + " HTTP/1.1", "Host: localhost:" + port);
        assertThat(asAParameter.json().at("/writeAccess/writesAllowed").asBoolean()).isFalse();
        assertThat(asAParameter.header("Set-Cookie")).isNull();

        Answer locked = post("/api/config/writes", "application/json", "{\"action\":\"lock\"}".getBytes(StandardCharsets.UTF_8),
                "Cookie: PAVESESSION=" + session);
        assertThat(locked.json().get("writesAllowed").asBoolean()).isFalse();
    }
}
