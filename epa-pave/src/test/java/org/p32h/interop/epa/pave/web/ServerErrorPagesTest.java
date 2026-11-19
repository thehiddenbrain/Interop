package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * No error may reach a browser as an HTML page or with a stack trace, including the errors the
 * embedded server raises before a request reaches the application. Those cannot be produced
 * through MockMvc, so this starts the real server on a free port and speaks to it over a socket
 * on the loopback interface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerErrorPagesTest {

    @LocalServerPort
    int port;

    private String exchange(String requestLine) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write((requestLine + "\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void assertSharedErrorShape(String response, String status) {
        assertThat(response).startsWith("HTTP/1.1 " + status);
        assertThat(response.toLowerCase()).contains("content-type: application/json");
        assertThat(response).contains("\"kind\"").contains("\"title\"").contains("\"action\"");
        assertThat(response).doesNotContain("<html").doesNotContain("<!doctype").doesNotContain("Apache Tomcat")
                .doesNotContain("Exception").doesNotContain("\tat ").doesNotContain("Whitelabel");
    }

    @Test
    void aRequestTheServerItselfRefusesIsAnsweredInTheSharedShape() throws IOException {
        // Characters a url may not hold. The server refuses this before any controller sees it.
        assertSharedErrorShape(exchange("GET /api/mode{bad}|x HTTP/1.1"), "400");
        assertSharedErrorShape(exchange("GET /api/%ZZ HTTP/1.1"), "400");
    }

    @Test
    void anAddressThatDoesNotExistIsAnsweredInTheSharedShape() throws IOException {
        assertSharedErrorShape(exchange("GET /api/no-such-endpoint HTTP/1.1"), "404");
        assertSharedErrorShape(exchange("GET /no/such/page HTTP/1.1"), "404");
        assertSharedErrorShape(exchange("GET /error HTTP/1.1"), "404");
    }
}
