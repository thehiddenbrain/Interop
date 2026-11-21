package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * The lookup endpoints through the running server, with its filters in front of them.
 *
 * <p>A controller test reaches the controller directly. It cannot show what matters most about an
 * endpoint that returns a member on the live profile: that no way of writing its address gets
 * past the guard which keeps other web pages away from the API. The server, the framework and the
 * filters each read an address in their own way, and only the three together give the answer. So
 * this starts the real server on a free port, on the mock profile, and speaks to it over a socket
 * on the loopback interface, with each request line written by hand so that nothing rewrites it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LookupOverTheServerTest {

    /** A name that could resolve to this machine and is not one the tool answers to. Never looked up: it is only sent as a header. */
    private static final String ANOTHER_HOST = "other-site.example.invalid";

    /** Values of the two synthetic records asked for here, which a refusal must not hold. */
    private static final List<String> RECORD_DATA = List.of("Placeholder-PaRequired", "Avery", "1950-01-31", "Placeholder-Provider",
            "Sleep Medicine");

    @LocalServerPort
    int port;

    private record Answer(int status, Map<String, String> headers, String body) {

        JsonNode json() {
            return JsonSafe.parse(body).node();
        }
    }

    private String ownHost() {
        return "localhost:" + port;
    }

    private Answer ask(String address, String host) throws IOException {
        byte[] received;
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout(10000);
            socket.getOutputStream().write(("GET " + address + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            received = socket.getInputStream().readAllBytes();
        }
        // One character per byte, so a place found in the text is the same place in the bytes.
        String raw = new String(received, StandardCharsets.ISO_8859_1);
        int endOfHead = raw.indexOf("\r\n\r\n");
        assertThat(endOfHead).as("a complete response head for " + address).isPositive();
        String[] head = raw.substring(0, endOfHead).split("\r\n");
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < head.length; i++) {
            int colon = head[i].indexOf(':');
            headers.put(head[i].substring(0, colon).strip(), head[i].substring(colon + 1).strip());
        }
        String body = raw.substring(endOfHead + 4);
        if ("chunked".equalsIgnoreCase(headers.get("Transfer-Encoding"))) {
            StringBuilder whole = new StringBuilder();
            int at = 0;
            while (true) {
                int endOfSize = body.indexOf("\r\n", at);
                int size = Integer.parseInt(body.substring(at, endOfSize).strip(), 16);
                if (size == 0) {
                    break;
                }
                whole.append(body, endOfSize + 2, endOfSize + 2 + size);
                at = endOfSize + 2 + size + 2;
            }
            body = whole.toString();
        }
        return new Answer(Integer.parseInt(head[0].split(" ")[1]), headers,
                new String(body.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
    }

    @Test
    void aLookupAskedTheOrdinaryWayIsAnsweredAndTheBrowserIsToldNotToStoreIt() throws IOException {
        Answer member = ask("/api/members/P32H-PA-REQ", ownHost());
        assertThat(member.status()).isEqualTo(200);
        assertThat(member.json().get("status").asString()).isEqualTo("FOUND");
        assertThat(member.json().at("/member/lastName").asString()).isEqualTo("Placeholder-PaRequired");
        // On the live profile this body is a real member. It must not be left in the browser's cache.
        assertThat(member.headers().get("Cache-Control")).isEqualTo("no-store");

        Answer provider = ask("/api/providers/1234567893", ownHost());
        assertThat(provider.status()).isEqualTo(200);
        assertThat(provider.json().at("/provider/lastName").asString()).isEqualTo("Placeholder-Provider");
        assertThat(provider.headers().get("Cache-Control")).isEqualTo("no-store");

        // Not found is an answer and not an error, through the server as well.
        Answer notFound = ask("/api/members/P32H-NOMEM", ownHost());
        assertThat(notFound.status()).isEqualTo(200);
        assertThat(notFound.json().get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(notFound.headers().get("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void noLookupIsAnsweredToAHostNameTheToolDoesNotAnswerToHoweverTheAddressIsWritten() throws IOException {
        // Written plainly, the request is refused by the guard in front of the API.
        for (String address : List.of("/api/members/P32H-PA-REQ", "/api/providers/1234567893", "/api/%6Dembers/P32H-PA-REQ")) {
            Answer refused = ask(address, ANOTHER_HOST);
            assertThat(refused.status()).as(address).isEqualTo(403);
            assertThat(refused.body()).as(address).doesNotContain(RECORD_DATA);
        }
        // Written any of these ways, the framework still finds the lookup. The guard checks every
        // request and sorts no address into API and not API, so each is refused all the same.
        for (String address : List.of("/%61pi/members/P32H-PA-REQ", "/ap%69/members/P32H-PA-REQ", "/%61%70%69/members/P32H-PA-REQ",
                "/api;x=1/members/P32H-PA-REQ", "/%61pi/providers/1234567893", "/api;x=1/providers/1234567893")) {
            Answer refused = ask(address, ANOTHER_HOST);
            assertThat(refused.status()).as(address).isEqualTo(403);
            assertThat(refused.body()).as(address).doesNotContain(RECORD_DATA);
        }
    }

    @Test
    void aLookupAnsweredUnderAnotherSpellingOfItsAddressIsNotLeftInTheBrowserEither() throws IOException {
        // From the tool's own host name these are answered like any other request. What matters is
        // that the answer is marked as one a browser must not store, whatever the spelling.
        for (String address : List.of("/%61pi/members/P32H-PA-REQ", "/ap%69/members/P32H-PA-REQ", "/%61pi/providers/1234567893")) {
            Answer answered = ask(address, ownHost());
            assertThat(answered.status()).as(address).isEqualTo(200);
            assertThat(answered.headers().get("Cache-Control")).as(address).isEqualTo("no-store");
        }
        // A semicolon is refused at the edge for every address, before any lookup, because it would
        // cut the identifier short.
        Answer cut = ask("/api;x=1/members/P32H-PA-REQ", ownHost());
        assertThat(cut.status()).isEqualTo(400);
        assertThat(cut.json().get("title").asString())
                .isEqualTo("The address holds a semicolon that is not percent-encoded. This tool takes none, so nothing was done.");
        assertThat(cut.headers().get("Cache-Control")).isEqualTo("no-store");
        assertThat(cut.body()).doesNotContain(RECORD_DATA).doesNotContain("P32H-PA-REQ");
    }

    @Test
    void anIdentifierReachesTheLookupWholeOrTheLookupIsNotMade() throws IOException {
        // The server and the framework cut an address at a semicolon. Looked up after the cut, this
        // would be the member S123456789, who exists, reported under an ID nobody typed. The edge
        // refuses it for every address, so no lookup is made.
        Answer cut = ask("/api/members/S123456789;x=1", ownHost());
        assertThat(cut.status()).isEqualTo(400);
        assertThat(cut.json().get("title").asString()).contains("semicolon");
        assertThat(cut.body()).doesNotContain("Placeholder").doesNotContain("S123456789");
        assertThat(ask("/api/providers/1234567893;jsessionid=abc", ownHost()).status()).isEqualTo(400);
        assertThat(ask("/api/members/S123456789;", ownHost()).status()).isEqualTo(400);

        // Percent-encoded, the same characters are part of the identifier.
        Answer whole = ask("/api/members/S123456789%3Bx=1", ownHost());
        assertThat(whole.status()).isEqualTo(200);
        assertThat(whole.json().at("/memberId/typed").asString()).isEqualTo("S123456789;x=1");
        assertThat(whole.json().get("status").asString()).isEqualTo("NOT_FOUND");

        // A Medicare ID written with spaces arrives as it was typed, and both forms come back.
        Answer spaced = ask("/api/members/S12345678%20%20%2001", ownHost());
        assertThat(spaced.status()).isEqualTo(200);
        assertThat(spaced.json().at("/memberId/typed").asString()).isEqualTo("S12345678   01");
        assertThat(spaced.json().at("/memberId/sent").asString()).isEqualTo("S1234567801");
        assertThat(spaced.json().at("/wire/memberId/typed").asString()).isEqualTo("S12345678   01");
        assertThat(spaced.json().get("status").asString()).isEqualTo("FOUND");
    }

    @Test
    void anIdentifierHoldingASlashIsLookedUpThroughTheQueryAndAnswersWithTheStatusInWords() throws IOException {
        // In the path the server refuses an encoded slash before any controller runs, with a page that says nothing true.
        assertThat(ask("/api/members/S123%2F456789", ownHost()).status()).isEqualTo(400);
        // In the query it is part of the identifier, and the lookup answers as for any other.
        Answer slashed = ask("/api/members?memberId=S123%2F456789", ownHost());
        assertThat(slashed.status()).isEqualTo(200);
        assertThat(slashed.json().at("/memberId/typed").asString()).isEqualTo("S123/456789");
        assertThat(slashed.json().get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(slashed.json().get("statusLabel").asString()).isEqualTo("Not found");
        Answer backslashed = ask("/api/members?memberId=S123%5C456789", ownHost());
        assertThat(backslashed.status()).isEqualTo(200);
        assertThat(backslashed.json().at("/memberId/typed").asString()).isEqualTo("S123\\456789");

        Answer found = ask("/api/members?memberId=P32H-PA-REQ", ownHost());
        assertThat(found.json().get("status").asString()).isEqualTo("FOUND");
        assertThat(found.json().get("statusLabel").asString()).isEqualTo("Found");
        assertThat(found.headers().get("Cache-Control")).isEqualTo("no-store");
        Answer provider = ask("/api/providers?npi=1234567893", ownHost());
        assertThat(provider.json().get("statusLabel").asString()).isEqualTo("Found");
        // Still one identifier and one record: with none, the same refusal as a blank one, and nothing is listed.
        Answer none = ask("/api/members", ownHost());
        assertThat(none.status()).isEqualTo(422);
        assertThat(none.json().at("/fieldErrors/0/field").asString()).isEqualTo("memberId");
        assertThat(ask("/api/providers?npi=1234567890", ownHost()).status()).isEqualTo(422);
    }

    @Test
    void anIdentifierRefusedBeforeAnyLookupIs422ThroughTheServerToo() throws IOException {
        Answer blank = ask("/api/members/%20%20", ownHost());
        assertThat(blank.status()).isEqualTo(422);
        assertThat(blank.json().at("/fieldErrors/0/field").asString()).isEqualTo("memberId");
        Answer badNpi = ask("/api/providers/1234567890", ownHost());
        assertThat(badNpi.status()).isEqualTo(422);
        assertThat(badNpi.json().at("/fieldErrors/0/message").asString()).contains("The last digit of this NPI does not match the other nine.");
    }
}
