package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletContext;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.p32h.interop.epa.pave.web.RawHttp.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The edge of the running server: who may ask, what a badly formed request gets, how large a
 * request may be, and that an upload never reaches the disk. Each request is written by hand over
 * a socket, because each of these is decided by the server, its filters and the framework
 * together, and a controller test reaches none of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class EdgeOverTheServerTest {

    private static final String CROSS_SITE_IMAGE = "Sec-Fetch-Site: cross-site\r\nSec-Fetch-Mode: no-cors\r\nSec-Fetch-Dest: image";
    private static final String CROSS_SITE_LINK = "Sec-Fetch-Site: cross-site\r\nSec-Fetch-Mode: navigate\r\nSec-Fetch-Dest: document"
            + "\r\nSec-Fetch-User: ?1\r\nAccept: text/html,application/xhtml+xml,*/*;q=0.8";
    private static final long MB = 1024 * 1024;

    @LocalServerPort
    int port;

    @Autowired
    ServletContext servletContext;

    private String host() {
        return "Host: localhost:" + port;
    }

    private Answer get(String address, String... headers) throws IOException {
        String[] all = new String[headers.length + 1];
        all[0] = host();
        System.arraycopy(headers, 0, all, 1, headers.length);
        return RawHttp.get(port, "GET " + address + " HTTP/1.1", all);
    }

    private Answer request(String requestLine, String body, String... headers) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String[] all = new String[headers.length + 2];
        all[0] = host();
        all[1] = "Content-Length: " + bytes.length;
        System.arraycopy(headers, 0, all, 2, headers.length);
        return RawHttp.send(port, RawHttp.head(requestLine, all), bytes);
    }

    // ----------------------------------------------------------------------------------------
    // E1: a read another site's page causes is refused, and a link from another site opens pages only
    // ----------------------------------------------------------------------------------------

    @Test
    void aReadAnotherSitesPageCausesIsRefusedBeforeAnythingIsLookedUpOrExported() throws IOException {
        // An image or a fetch on another site's page. On live this lookup would reach the payer
        // with this tool's credentials, for a member the other page chose.
        for (String address : List.of("/api/members/P32H-PA-REQ", "/api/providers/1234567893", "/api/crd/discover", "/api/runs/export.csv")) {
            Answer refused = get(address, CROSS_SITE_IMAGE);
            assertThat(refused.status()).as(address).isEqualTo(403);
            assertThat(refused.title()).isEqualTo("This request came from a page that is not part of this tool, so it was not carried out.");
            assertThat(refused.body()).doesNotContain("Placeholder").doesNotContain("recordType");
            refused.assertTheFiveHeaders();
            assertThat(RawHttp.get(port, "HEAD " + address + " HTTP/1.1", host(), "Sec-Fetch-Site: same-site").status()).as(address)
                    .isEqualTo(403);
        }
        // A browser too old to send Sec-Fetch-Site, stating the other origin.
        assertThat(get("/api/members/P32H-PA-REQ", "Origin: http://attacker.example").status()).isEqualTo(403);
    }

    @Test
    void aLinkFromAnotherSiteOpensNoAnswerOfTheApiAndTheExportIsNotSaved() throws IOException {
        // A link on another site, followed in the top-level window. The guard lets it past, so that
        // a link to the tool still opens it; the framework then hands it to a controller method and
        // the interceptor refuses it.
        for (String address : List.of("/api/runs/export.csv", "/api/members/P32H-PA-REQ", "/api/mode", "/%61pi/runs/export.csv")) {
            Answer refused = get(address, CROSS_SITE_LINK);
            assertThat(refused.status()).as(address).isEqualTo(403);
            assertThat(refused.header("Content-Disposition")).as(address).isNull();
            assertThat(refused.header("Content-Type")).startsWith("text/plain");
            assertThat(refused.body()).doesNotContain("recordType").doesNotContain("Placeholder")
                    .startsWith("This request came from a page that is not part of this tool, so it was not carried out.\n");
            refused.assertTheFiveHeaders();
        }
        // An address that is not a controller method is not refused for being linked to: the link
        // reaches the tool and gets the answer anybody gets there. Pages and static files are of this kind.
        Answer page = get("/no/such/page.html", CROSS_SITE_LINK);
        assertThat(page.status()).isEqualTo(404);
        // Only a link followed by GET in the top-level window. A form posted from another site, a
        // frame and a navigation by HEAD are refused by the guard.
        assertThat(request("POST /api/config/writes HTTP/1.1", "{\"action\":\"lock\"}", "Content-Type: application/json", CROSS_SITE_LINK)
                .status()).isEqualTo(403);
        assertThat(get("/api/runs/export.csv", "Sec-Fetch-Site: cross-site", "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: iframe")
                .status()).isEqualTo(403);
        assertThat(RawHttp.get(port, "HEAD /api/runs/export.csv HTTP/1.1", host(), CROSS_SITE_LINK).status()).isEqualTo(403);
    }

    @Test
    void theToolsOwnPagesAndTheAddressBarWorkAsBefore() throws IOException {
        Answer own = get("/api/runs/export.csv", "Sec-Fetch-Site: same-origin", "Sec-Fetch-Mode: cors", "Sec-Fetch-Dest: empty");
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.header("Content-Disposition")).startsWith("attachment");
        // Typed into the address bar or opened from a bookmark: the browser says "none".
        Answer typed = get("/api/mode", "Sec-Fetch-Site: none", "Sec-Fetch-Mode: navigate", "Sec-Fetch-Dest: document");
        assertThat(typed.status()).isEqualTo(200);
        assertThat(typed.body()).contains("\"mode\"");
        // curl, a script, a vendor's server: no browser, no page, nothing to refuse.
        assertThat(get("/api/members/P32H-PA-REQ").status()).isEqualTo(200);
    }

    // ----------------------------------------------------------------------------------------
    // E2: the tool's own plain form post
    // ----------------------------------------------------------------------------------------

    @Test
    void aPlainFormPostFromTheToolsOwnPageIsNotRefused() throws IOException {
        // Exactly what Chrome sent for a form on the tool's own page under the earlier no-referrer
        // policy: Origin null, and Sec-Fetch-Site saying it is the same origin.
        String boundary = "----WebKitFormBoundaryMkt0RFxXQcBg8UTl";
        byte[] body = RawHttp.multipart(boundary, "file", "cases.csv", "caseId,protocol,memberId\nc1,crd,P32H-PA-REQ\n".getBytes(StandardCharsets.UTF_8));
        Answer own = RawHttp.send(port, RawHttp.multipartHead("/api/suites/preview", port, boundary, body.length, "Origin: null",
                "Sec-Fetch-Dest: document", "Sec-Fetch-Mode: navigate", "Sec-Fetch-Site: same-origin"), body);
        assertThat(own.status()).isEqualTo(200);
        // The same post from another origin is still refused.
        Answer other = RawHttp.send(port, RawHttp.multipartHead("/api/suites/preview", port, boundary, body.length, "Origin: http://attacker.example",
                "Sec-Fetch-Dest: document", "Sec-Fetch-Mode: navigate", "Sec-Fetch-Site: cross-site"), body);
        assertThat(other.status()).isEqualTo(403);
        // The policy that lets a browser state the tool's own origin on such a post.
        assertThat(get("/api/mode").header("Referrer-Policy")).isEqualTo("same-origin");
    }

    // ----------------------------------------------------------------------------------------
    // E3, E7: a badly formed request is a 4xx in the shared shape, and the log holds nothing of it
    // ----------------------------------------------------------------------------------------

    @Test
    void aBadlyFormedAddressOrQueryIsA400AndNothingIsLogged(CapturedOutput output) throws IOException {
        int before = output.getAll().length();
        String[] addresses = {"/api/mode;x=%zz", "/api/members/x;note=50%S123456789", "/api/procedure-codes?q=99%", "/api/runs?from=%zz&chainId=S123456789",
            "/api/runs?=S123456789&=Zzlastname", "/api/runs/export.csv?from=%"};
        for (String address : addresses) {
            Answer answer = get(address);
            assertThat(answer.status()).as(address).isEqualTo(400);
            assertThat(answer.json().get("kind").asString()).isEqualTo("VALIDATION");
            assertThat(answer.body()).as(address).doesNotContain("S123456789").doesNotContain("Zzlastname").doesNotContain("%zz")
                    .doesNotContain("reference");
            answer.assertTheFiveHeaders();
        }
        StringBuilder many = new StringBuilder("/api/runs?");
        for (int i = 0; i <= 1000; i++) {
            many.append(i == 0 ? "" : "&").append('p').append(i).append("=1");
        }
        assertThat(get(many.toString()).status()).isEqualTo(400);
        assertThat(output.getAll().substring(before)).doesNotContain("S123456789").doesNotContain("Unhandled").doesNotContain("ERROR")
                .doesNotContain("Exception");
    }

    @Test
    void aContentTypeThatIsAWildcardIsA415AndAFormBodyIsNotReadByAnyFilter(CapturedOutput output) throws IOException {
        int before = output.getAll().length();
        for (String type : List.of("*/*", "application/*", "text/*")) {
            Answer answer = request("POST /api/crd HTTP/1.1", "{}", "Content-Type: " + type);
            assertThat(answer.status()).as(type).isEqualTo(415);
            assertThat(answer.json().get("kind").asString()).isEqualTo("VALIDATION");
        }
        // A form body with a bad escape was read by the framework's form filter, outside the exception handler.
        Answer form = request("PUT /api/config/collections/x/mapping HTTP/1.1", "a=%zz&id=S123456789", "Content-Type: application/x-www-form-urlencoded");
        assertThat(form.status()).isEqualTo(415);
        assertThat(output.getAll().substring(before)).doesNotContain("S123456789").doesNotContain("ERROR").doesNotContain("Exception");
    }

    // ----------------------------------------------------------------------------------------
    // E5, E8, E9, M6: the limits on what a request may carry
    // ----------------------------------------------------------------------------------------

    @Test
    void anUploadAtTheFileLimitIsReadAndOneByteOverIsA413ThatSaysWhichLimit() throws IOException {
        String boundary = "edge-limit";
        byte[] atLimit = RawHttp.multipart(boundary, "file", "cases.csv", filled(25 * MB));
        Answer accepted = RawHttp.send(port, RawHttp.multipartHead("/api/suites/preview", port, boundary, atLimit.length), atLimit);
        // Read, and refused by the suite reader for its own limit: the upload limit let it through.
        assertThat(accepted.status()).isEqualTo(422);
        assertThat(accepted.title()).contains("suite file is larger than 5 MB");

        byte[] over = RawHttp.multipart(boundary, "file", "cases.csv", filled(25 * MB + 1));
        Answer refused = RawHttp.send(port, RawHttp.multipartHead("/api/suites/preview", port, boundary, over.length), over);
        assertThat(refused.status()).isEqualTo(413);
        assertThat(refused.title()).isEqualTo("The uploaded file is larger than this tool accepts, which is 25MB for one file.");
        assertThat(refused.json().get("action").asString())
                .isEqualTo("Upload a smaller export, or raise spring.servlet.multipart.max-file-size and "
                        + "spring.servlet.multipart.max-request-size together in application.yml.");
        refused.assertTheFiveHeaders();
    }

    @Test
    void eachUploadLimitSaysWhichLimitItWasAndWhatWouldHelp() throws IOException {
        String boundary = "edge-parts";
        // More parts than the server takes.
        List<Object> parts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            parts.addAll(Arrays.asList("f" + i, null, new byte[] {'x'}));
        }
        parts.addAll(Arrays.asList("file", "cases.csv", "caseId\n".getBytes(StandardCharsets.UTF_8)));
        byte[] manyParts = RawHttp.multipart(boundary, parts.toArray());
        Answer tooManyParts = RawHttp.send(port, RawHttp.multipartHead("/api/suites/preview", port, boundary, manyParts.length), manyParts);
        assertThat(tooManyParts.status()).isEqualTo(413);
        assertThat(tooManyParts.title()).isEqualTo("The upload has more parts than this tool accepts.");
        assertThat(tooManyParts.json().get("action").asString()).contains("server.tomcat.max-part-count");

        // A file name longer than a part header may be.
        byte[] longName = RawHttp.multipart(boundary, "file", "x".repeat(5000) + ".csv", "caseId\n".getBytes(StandardCharsets.UTF_8));
        Answer longHeader = RawHttp.send(port, RawHttp.multipartHead("/api/suites/preview", port, boundary, longName.length), longName);
        assertThat(longHeader.status()).isEqualTo(413);
        assertThat(longHeader.title()).isEqualTo("A part of the upload has a header larger than this tool accepts, which is usually a very "
                + "long file name.");
        assertThat(longHeader.json().get("action").asString()).contains("shorter name").contains("server.tomcat.max-part-header-size");

        // A text field larger than the server's limit for the fields of a form.
        byte[] bigField = RawHttp.multipart(boundary, "testerRef", null, filled(3 * MB), "file", "cases.csv",
                "caseId\n".getBytes(StandardCharsets.UTF_8));
        Answer field = RawHttp.send(port, RawHttp.multipartHead("/api/suites/preview", port, boundary, bigField.length), bigField);
        assertThat(field.status()).isEqualTo(413);
        assertThat(field.title()).isEqualTo("The fields sent with the upload are larger than this tool accepts.");
        assertThat(field.json().get("action").asString()).contains("server.tomcat.max-http-form-post-size");

        // The whole request over its limit is refused before it is read.
        Answer whole = RawHttp.send(port, RawHttp.multipartHead("/api/suites/preview", port, boundary, 31 * MB), new byte[0]);
        assertThat(whole.status()).isEqualTo(413);
        assertThat(whole.title()).isEqualTo("The upload is larger than this tool accepts, which is 25MB for one file and 30MB in all. Nothing was done.");
    }

    @Test
    void anUploadIsReadOnlyByAHandlerThatTakesOne() throws IOException {
        // Over the file limit and under the request limit. Read eagerly, each of these would be a
        // 413 after the whole file had been read; read only when a handler asks, the address
        // decides first, and nothing is read.
        String boundary = "edge-lazy";
        byte[] body = RawHttp.multipart(boundary, "file", "cases.csv", filled(26 * MB));
        assertThat(RawHttp.send(port, RawHttp.multipartHead("/api/mode", port, boundary, body.length), body).status()).isEqualTo(405);
        assertThat(RawHttp.send(port, RawHttp.multipartHead("/api/no-such-endpoint", port, boundary, body.length), body).status())
                .isEqualTo(404);
    }

    @Test
    void aBodyThatIsNotAnUploadHasALimitToo() throws IOException {
        // Stated length over the limit: refused before a byte of the body is read.
        Answer stated = RawHttp.send(port, RawHttp.head("POST /api/config/writes HTTP/1.1", host(), "Content-Type: application/json",
                "Content-Length: " + (30 * MB + 1)), new byte[0]);
        assertThat(stated.status()).isEqualTo(413);
        assertThat(stated.title()).isEqualTo("The request is larger than this tool accepts, which is 30MB in all. Nothing was done.");
        assertThat(stated.json().get("action").asString())
                .isEqualTo("Send a smaller body. To accept larger ones, raise spring.servlet.multipart.max-request-size and spring.servlet.multipart.max-file-size together in application.yml.");
        stated.assertTheFiveHeaders();

        // Sent in chunks, with no length stated: counted as it is read.
        Answer chunked;
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(60000);
            OutputStream out = socket.getOutputStream();
            out.write(RawHttp.head("POST /api/config/writes HTTP/1.1", host(), "Content-Type: application/json", "Transfer-Encoding: chunked"));
            byte[] blanks = new byte[(int) MB];
            Arrays.fill(blanks, (byte) ' ');
            try {
                for (int i = 0; i < 32; i++) {
                    out.write((Integer.toHexString(blanks.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(blanks);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                out.write("10\r\n{\"action\":\"lock\"\r\n1\r\n}\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException closedEarly) {
                // The server answered and closed once the limit was passed.
            }
            chunked = RawHttp.read(socket.getInputStream());
        }
        assertThat(chunked.status()).isEqualTo(413);
        assertThat(chunked.title()).isEqualTo("The request is larger than this tool accepts, which is 30MB in all. Nothing was done.");
    }

    @Test
    void anUploadOfTheLargestAllowedSizeIsNeverWrittenToTheTemporaryFolder() throws IOException {
        // The server writes a part to its temporary folder once it passes a threshold. A hard kill
        // in the middle of an upload would leave it there, outside the ledger and the configuration
        // store, with whatever the file held.
        Path temporary = ((File) servletContext.getAttribute(ServletContext.TEMPDIR)).toPath();
        List<Path> already = filesUnder(temporary);
        String boundary = "edge-spool";
        byte[] body = RawHttp.multipart(boundary, "file", "cases.csv", filled(25 * MB));
        int sentFirst = (int) (24 * MB);
        List<Path> during;
        Answer answer;
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(60000);
            OutputStream out = socket.getOutputStream();
            out.write(RawHttp.multipartHead("/api/suites/preview", port, boundary, body.length));
            out.write(body, 0, sentFirst);
            out.flush();
            // The socket buffers hold a few megabytes at most, so by now the server has read most of
            // the 24MB already written, and an upload spooled to disk would be there to be seen.
            during = filesUnder(temporary);
            out.write(body, sentFirst, body.length - sentFirst);
            out.flush();
            answer = RawHttp.read(socket.getInputStream());
        }
        assertThat(answer.status()).isEqualTo(422);
        during.removeAll(already);
        assertThat(during).as("files under " + temporary + " while the upload was being read").isEmpty();
    }

    @Test
    void theUploadAndBodySettingsAreTheOnesDecided() {
        // Asserted as values as well, so that a change to one of them is a decision and not an accident.
        assertThat(servletContext).isNotNull();
        org.springframework.core.env.Environment environment = org.springframework.web.context.support.WebApplicationContextUtils
                .getRequiredWebApplicationContext(servletContext).getEnvironment();
        assertThat(environment.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("25MB");
        assertThat(environment.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("30MB");
        assertThat(environment.getProperty("spring.servlet.multipart.file-size-threshold")).isEqualTo("30MB");
        assertThat(environment.getProperty("spring.servlet.multipart.resolve-lazily")).isEqualTo("true");
        assertThat(environment.getProperty("spring.mvc.formcontent.filter.enabled")).isEqualTo("false");
    }

    // ----------------------------------------------------------------------------------------
    // E5: rules that had no test
    // ----------------------------------------------------------------------------------------

    @Test
    void aRefusalByTheGuardCarriesTheFiveHeadersBecauseTheHeaderFilterRunsFirst() throws IOException {
        Answer otherName = RawHttp.get(port, "GET /api/mode HTTP/1.1", "Host: attacker.example");
        assertThat(otherName.status()).isEqualTo(403);
        otherName.assertTheFiveHeaders();
        Answer otherSite = request("POST /api/config/writes HTTP/1.1", "{\"action\":\"lock\"}", "Content-Type: application/json",
                "Origin: http://attacker.example");
        assertThat(otherSite.status()).isEqualTo(403);
        otherSite.assertTheFiveHeaders();
    }

    @Test
    void anApiReadAskedForAsAPageIs406InTheSharedShape() throws IOException {
        Answer answer = get("/api/mode", "Accept: text/html");
        assertThat(answer.status()).isEqualTo(406);
        assertThat(answer.title()).isEqualTo("This endpoint cannot answer in the requested format.");
        answer.assertTheFiveHeaders();
    }

    @Test
    void theServerIsBoundToTheLoopbackAddressOnly() throws IOException {
        InetAddress other = null;
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    other = address;
                }
            }
        }
        Assumptions.assumeTrue(other != null, "this machine has no address but the loopback one");
        InetAddress reachable = other;
        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(reachable, port), 5000);
            }
        }).isInstanceOf(ConnectException.class);
        assertThat(get("/api/mode").status()).isEqualTo(200);
    }

    // ----------------------------------------------------------------------------------------
    // E12, E13
    // ----------------------------------------------------------------------------------------

    @Test
    void theFullyQualifiedSpellingOfLocalhostIsServed() throws IOException {
        assertThat(RawHttp.get(port, "GET /api/mode HTTP/1.1", "Host: localhost.:" + port).status()).isEqualTo(200);
        assertThat(RawHttp.get(port, "GET /api/mode HTTP/1.1", "Host: attacker.example.").status()).isEqualTo(403);
    }

    @Test
    void aMethodTheRequestMadeUpIsNotRepeated() throws IOException {
        Answer method = RawHttp.get(port, "S123456789 /api/mode HTTP/1.1", host());
        assertThat(method.status()).isEqualTo(405);
        assertThat(method.title()).isEqualTo("This endpoint does not accept that method.");
        assertThat(method.json().get("action").asString()).isEqualTo("Use GET.");
        assertThat(method.body()).doesNotContain("S123456789");
    }

    private static byte[] filled(long size) {
        byte[] bytes = new byte[(int) size];
        Arrays.fill(bytes, (byte) 'a');
        return bytes;
    }

    private static List<Path> filesUnder(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return new ArrayList<>();
        }
        try (Stream<Path> walk = Files.walk(folder)) {
            return new ArrayList<>(walk.filter(Files::isRegularFile).toList());
        }
    }
}
