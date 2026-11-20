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
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ServerErrorPagesTest {

    @LocalServerPort
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.core.env.Environment environment;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.boot.logging.LoggingSystem loggingSystem;

    private String exchange(String requestLine) throws IOException {
        return exchange(requestLine, "Host: localhost");
    }

    private String exchange(String requestLine, String headers) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write((requestLine + "\r\n" + headers + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
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

    /** The five headers every response carries, the answers the server gives before the filters included. */
    static void assertTheFiveHeaders(String response) {
        String head = response.substring(0, Math.max(response.indexOf("\r\n\r\n"), 0)).toLowerCase();
        assertThat(head).contains("\r\ncache-control: no-store\r\n").contains("\r\nx-content-type-options: nosniff\r\n")
                .contains("\r\nx-frame-options: deny\r\n").contains("\r\nreferrer-policy: same-origin\r\n")
                .contains("\r\ncontent-security-policy: default-src 'self';");
    }

    @Test
    void aRequestTheServerItselfRefusesIsAnsweredInTheSharedShapeWithTheHeadersEveryAnswerCarries() throws IOException {
        // Characters a url may not hold. The server refuses this before any controller sees it.
        assertSharedErrorShape(exchange("GET /api/mode{bad}|x HTTP/1.1"), "400");
        assertSharedErrorShape(exchange("GET /api/%ZZ HTTP/1.1"), "400");
        // Each of these is answered before any filter runs, or on the error dispatch the filters
        // used to skip. Each must still say that the browser keeps nothing.
        String[][] refused = {
            {"400", "GET /api/members/S12%2F34 HTTP/1.1", "Host: localhost"},
            {"400", "GET /api/mode HTTP/1.1", "Host: localhost\r\nHost: localhost"},
            {"405", "TRACE /api/members/S123456789 HTTP/1.1", "Host: localhost"},
            {"404", "GET /WEB-INF/S123456789.css HTTP/1.1", "Host: localhost"},
            {"404", "GET /META-INF/MANIFEST.MF HTTP/1.1", "Host: localhost"},
            {"417", "GET /api/mode HTTP/1.1", "Host: localhost\r\nExpect: S123456789-Zz"},
            {"501", "POST /api/crd HTTP/1.1", "Host: localhost\r\nTransfer-Encoding: zz"},
        };
        for (String[] one : refused) {
            String response = exchange(one[1], one[2]);
            assertSharedErrorShape(response, one[0]);
            assertTheFiveHeaders(response);
            // httpStatus in the shared shape is a vendor's status. An error of the server's own has no vendor.
            assertThat(response).as(one[1]).doesNotContain("httpStatus").contains("\"fieldErrors\":[]")
                    .contains("\"issues\":[]").doesNotContain("S123456789");
        }
    }

    @Test
    void theRunningServerAppliesTheGuardAndTheHeadersToEveryResponse() throws IOException {
        // The two filters are unit tested apart. This checks they are in the running server's chain, in front of everything.
        String api = exchange("GET /api/no-such-endpoint HTTP/1.1").toLowerCase();
        assertThat(api).contains("cache-control: no-store").contains("x-content-type-options: nosniff")
                .contains("content-security-policy: default-src 'self'").contains("x-frame-options: deny");
        String page = exchange("GET /no/such/page HTTP/1.1").toLowerCase();
        assertThat(page).contains("cache-control: no-store").contains("x-content-type-options: nosniff");

        String otherName = exchange("GET /api/mode HTTP/1.1", "Host: attacker.example");
        assertSharedErrorShape(otherName, "403");
        assertThat(otherName).contains("a host name this tool does not answer to");
        String otherSite = exchange("POST /api/crd HTTP/1.1", "Host: localhost:" + port + "\r\nOrigin: https://attacker.example\r\nContent-Length: 0");
        assertSharedErrorShape(otherSite, "403");
        assertThat(otherSite).contains("a page that is not part of this tool");
        // The same request from the tool's own page is not stopped by the guard. It goes on to an ordinary answer.
        String ownPage = exchange("POST /api/crd HTTP/1.1", "Host: localhost:" + port + "\r\nOrigin: http://localhost:" + port + "\r\nContent-Length: 0");
        assertThat(ownPage).doesNotStartWith("HTTP/1.1 403");
    }

    @Test
    void noWayOfWritingAnAddressGetsPastTheGuardOrLeavesAnAnswerInTheBrowser() throws IOException {
        // The framework finds the controller for each of these spellings of /api/mode. An earlier
        // guard read the address as written, took none of them for the API, and let them through.
        for (String address : new String[] {"/api/mode", "/%61pi/mode", "/ap%69/mode", "/%61%70%69/mode", "/api;x=1/mode", "/api/%6Dode"}) {
            String own = exchange("GET " + address + " HTTP/1.1", "Host: localhost:" + port);
            if (address.contains(";")) {
                // The tool takes no path parameters, so an address with a semicolon is refused at the edge.
                assertSharedErrorShape(own, "400");
            } else {
                assertThat(own).as(address).startsWith("HTTP/1.1 200").contains("\"mode\"");
            }
            assertThat(own.toLowerCase()).as(address).contains("cache-control: no-store");

            String otherName = exchange("GET " + address + " HTTP/1.1", "Host: attacker.example");
            assertSharedErrorShape(otherName, "403");
            assertThat(otherName).as(address).doesNotContain("\"mode\"");

            String otherSite = exchange("POST " + address + " HTTP/1.1",
                    "Host: localhost:" + port + "\r\nOrigin: https://attacker.example\r\nContent-Length: 0");
            assertSharedErrorShape(otherSite, "403");
            assertThat(otherSite).as(address).contains("a page that is not part of this tool");
        }
        // A page and a static file are refused under another name too, and are not kept either.
        for (String address : new String[] {"/", "/index.html"}) {
            assertThat(exchange("GET " + address + " HTTP/1.1", "Host: attacker.example")).as(address).startsWith("HTTP/1.1 403");
            assertThat(exchange("GET " + address + " HTTP/1.1", "Host: localhost:" + port).toLowerCase()).as(address)
                    .contains("cache-control: no-store").doesNotContain("cache-control: no-cache");
        }
    }

    @Test
    void theServerAndTheFrameworkDoNotWriteARefusedAddressToTheLog(
            org.springframework.boot.test.system.CapturedOutput output) throws IOException {
        // An address can hold a member ID. With their default levels the server writes the first of
        // these to the log and the framework the second. The server only does so the first time on
        // each connection handler, so the levels are asserted as well as the output.
        assertThat(exchange("GET /api/members/S123456789{11 HTTP/1.1")).startsWith("HTTP/1.1 400");
        assertThat(exchange("GET /api/members/x/../S123456789 HTTP/1.1")).doesNotStartWith("HTTP/1.1 200");
        assertThat(exchange("GET /api/members/S123456789/no/such/address HTTP/1.1")).startsWith("HTTP/1.1 404");
        // A character the platform's file name encoding may not represent, in an address no
        // controller takes: the server's file lookup writes the whole address at WARN when it cannot.
        assertThat(exchange("GET /api/members/S123456789%C2%A0/ HTTP/1.1")).startsWith("HTTP/1.1 404");
        assertThat(exchange("GET /api/members/S123456789%C3%A9/x HTTP/1.1")).startsWith("HTTP/1.1 404");
        // A cookie another tool on localhost set, in a form the cookie parser refuses. It writes the
        // name and value at INFO the first time it meets one.
        assertThat(exchange("GET /api/mode HTTP/1.1", "Host: localhost\r\nCookie: recentMember={\"id\":\"S123456789\",\"dob\":\"1961-07-23\"}"))
                .startsWith("HTTP/1.1 200");
        assertThat(exchange("GET /api/mode HTTP/1.1", "Host: localhost\r\nCookie: other_session=S123456789 with space; theme=dark"))
                .startsWith("HTTP/1.1 200");
        // A path parameter with a bad escape was decoded by the framework outside the exception
        // handler: a 500, and the server wrote the exception message, which quotes the request.
        assertThat(exchange("GET /api/members/x;note=50%S123456789 HTTP/1.1")).startsWith("HTTP/1.1 400");
        assertThat(exchange("GET /api/mode;x=%zzS123456789 HTTP/1.1")).startsWith("HTTP/1.1 400");
        assertThat(output.getAll()).doesNotContain("S123456789").doesNotContain("1961-07-23");
        assertThat(loggingSystem.getLoggerConfiguration("org.apache.coyote.http11.Http11Processor").getEffectiveLevel())
                .isEqualTo(org.springframework.boot.logging.LogLevel.WARN);
        assertThat(loggingSystem.getLoggerConfiguration("org.springframework.web.servlet.resource.ResourceHandlerUtils").getEffectiveLevel())
                .isEqualTo(org.springframework.boot.logging.LogLevel.ERROR);
        assertThat(loggingSystem.getLoggerConfiguration("org.apache.catalina.webresources.AbstractFileResourceSet").getEffectiveLevel())
                .isEqualTo(org.springframework.boot.logging.LogLevel.ERROR);
        assertThat(loggingSystem.getLoggerConfiguration("org.apache.tomcat.util.http.parser.Cookie").getEffectiveLevel())
                .isEqualTo(org.springframework.boot.logging.LogLevel.WARN);
        assertThat(loggingSystem.getLoggerConfiguration("org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver")
                .getEffectiveLevel()).isEqualTo(org.springframework.boot.logging.LogLevel.ERROR);
    }

    @Test
    void theServerListensOnThisMachineOnly() {
        // Set in application.yml, so a default start is not offered to the network.
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("strict");
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(environment.getProperty("server.servlet.session.tracking-modes")).isEqualTo("cookie");
    }

    @Test
    void anAddressThatDoesNotExistIsAnsweredInTheSharedShape() throws IOException {
        assertSharedErrorShape(exchange("GET /api/no-such-endpoint HTTP/1.1"), "404");
        assertSharedErrorShape(exchange("GET /no/such/page HTTP/1.1"), "404");
        assertSharedErrorShape(exchange("GET /error HTTP/1.1"), "404");
    }
}
