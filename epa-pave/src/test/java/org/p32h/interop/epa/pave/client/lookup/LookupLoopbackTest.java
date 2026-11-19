package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.web.client.RestClient;

/**
 * The live lookups against the real HTTP client, built the way the application builds it, with the
 * lookup's own timeouts. A mock server cannot show that those are the timeouts in force, nor what
 * a response that starts and does not finish looks like. Every socket here is on the loopback
 * interface. Nothing leaves the machine.
 */
class LookupLoopbackTest {

    /** Far longer than the lookup read timeout used below, and far shorter than the protocol read timeout of 15 seconds. */
    private static final Duration WELL_INSIDE_THE_PROTOCOL_TIMEOUT = Duration.ofSeconds(8);

    private static HttpMemberLookupClient members(int port, Map<String, String> lookup) {
        FakeGateway gateway = new FakeGateway().url(EpaOperation.MEMBER_LOOKUP, "http://127.0.0.1:" + port + "/api/members/{id}");
        // The public constructor, which is the one the application uses: it builds the HTTP client from the lookup timeouts.
        return new HttpMemberLookupClient(new ExchangeExecutor(),
                OnyxAccessTestFactory.tokens(OnyxProperties.defaults(), gateway, RestClient.builder().build(), Clock.systemUTC()), gateway,
                LookupProperties.of(lookup), RestClient.builder(), Clock.systemUTC());
    }

    private static HttpProviderLookupClient providers(int port, Map<String, String> lookup) {
        FakeGateway gateway = new FakeGateway().url(EpaOperation.PROVIDER_LOOKUP, "http://127.0.0.1:" + port + "/providers/{id}");
        return new HttpProviderLookupClient(new ExchangeExecutor(),
                OnyxAccessTestFactory.tokens(OnyxProperties.defaults(), gateway, RestClient.builder().build(), Clock.systemUTC()), gateway,
                LookupProperties.of(lookup), RestClient.builder());
    }

    /** Serves one connection: reads the request, writes the given bytes, then closes or holds the connection open. */
    private static ServerSocket serveOnce(byte[] response, boolean closeAfter, List<Socket> held, List<String> requests) throws IOException {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try {
                Socket socket = server.accept();
                held.add(socket);
                byte[] buffer = new byte[8192];
                // The request is small and arrives in one read.
                int read = socket.getInputStream().read(buffer);
                requests.add(new String(buffer, 0, Math.max(read, 0), StandardCharsets.US_ASCII));
                socket.getOutputStream().write(response);
                socket.getOutputStream().flush();
                if (closeAfter) {
                    socket.close();
                }
            } catch (IOException closed) {
                // The test closed the server socket.
            }
        });
        return server;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void aMemberApiThatNeverAnswersIsATimeoutAfterTheLookupsOwnShortReadTimeout() throws IOException {
        List<Socket> accepted = new ArrayList<>();
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(silent.accept());
                    }
                } catch (IOException closed) {
                    // The test closed the server socket.
                }
            });
            HttpMemberLookupClient client = members(silent.getLocalPort(), Map.of("member.read-timeout-ms", "300"));

            // Had the client been built with the protocol timeouts, this would still be waiting.
            MemberLookupResult result = assertTimeoutPreemptively(WELL_INSIDE_THE_PROTOCOL_TIMEOUT,
                    () -> client.lookup(MemberIds.forms("S123456789")));

            assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
            assertThat(result.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
            assertThat(result.message()).isEqualTo("The member API did not answer inside the read timeout.");
            assertThat(result.wire().request().sent()).isTrue();
            assertThat(result.wire().response().status()).isNull();
            acceptor.interrupt();
        } finally {
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    @Test
    void aDirectoryThatNeverAnswersIsATimeoutAfterTheProviderLookupsOwnReadTimeout() throws IOException {
        List<Socket> accepted = new ArrayList<>();
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(silent.accept());
                    }
                } catch (IOException closed) {
                    // The test closed the server socket.
                }
            });
            // Only the provider timeout is short. A client that read the member setting would wait five seconds and still pass
            // the bound below, so the member timeout is set long enough to fail it.
            HttpProviderLookupClient client = providers(silent.getLocalPort(),
                    Map.of("provider.read-timeout-ms", "300", "member.read-timeout-ms", "60000"));

            ProviderLookupResult result = assertTimeoutPreemptively(WELL_INSIDE_THE_PROTOCOL_TIMEOUT,
                    () -> client.lookup(Npi.withCheckDigit("123456789")));

            assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
            assertThat(result.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
            acceptor.interrupt();
        } finally {
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    @Test
    void aPortNothingListensOnIsUnreachableAndNotATimeout() throws IOException {
        int port;
        try (ServerSocket taken = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            port = taken.getLocalPort();
        }
        // The socket is closed, so the connection is refused.
        MemberLookupResult result = members(port, Map.of()).lookup(MemberIds.forms("S123456789"));
        assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(result.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);
        assertThat(result.message()).isEqualTo("The member API could not be reached.");
        assertThat(result.wire().response().status()).isNull();
    }

    @Test
    void theRequestOnTheWireIsAGetForTheEncodedIdWithNoBodyAndTheAnswerIsMapped() throws IOException {
        List<Socket> held = new ArrayList<>();
        List<String> requests = new ArrayList<>();
        byte[] body = ascii("{\"data\":{\"member_id\":\"P32H-TEST/1\",\"first_name\":\"Avery\",\"lob\":\"Medicaid\"}}");
        byte[] response = ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n" + new String(body, StandardCharsets.US_ASCII));
        try (ServerSocket server = serveOnce(response, true, held, requests)) {
            MemberLookupResult result = members(server.getLocalPort(), Map.of("member.read-timeout-ms", "4000"))
                    .lookup(MemberIds.forms("P32H-TEST/1"));

            assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
            assertThat(result.member().firstName()).isEqualTo("Avery");
            assertThat(result.member().lineOfBusiness()).isEqualTo("Medicaid");
            assertThat(result.attention()).isEmpty();
            // What actually went down the socket.
            assertThat(requests).singleElement().satisfies(request -> {
                assertThat(request).startsWith("GET /api/members/P32H-TEST%2F1 HTTP/1.1\r\n");
                assertThat(request).containsIgnoringCase("accept: application/json");
                assertThat(request).doesNotContainIgnoringCase("authorization").doesNotContainIgnoringCase("content-type")
                        .doesNotContainIgnoringCase("accept-encoding");
                assertThat(request).endsWith("\r\n\r\n");
            });
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    @Test
    void anAnswerThatStartsAndThenStopsIsAFaultThatKeepsItsStatusAndWhatArrived() throws IOException {
        List<Socket> held = new ArrayList<>();
        // The API answers 200, promises 200 bytes, sends part of a record and closes the connection.
        byte[] partial = ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 200\r\n\r\n{\"memberId\":\"S123456789\",\"firstN");
        try (ServerSocket server = serveOnce(partial, true, held, new ArrayList<>())) {
            MemberLookupResult result = members(server.getLocalPort(), Map.of("member.read-timeout-ms", "4000"))
                    .lookup(MemberIds.forms("S123456789"));

            // Not "could not be reached", and not a member read from half a record.
            assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
            assertThat(result.message()).isEqualTo("The member API started to answer with HTTP 200 and the answer did not arrive in full.");
            assertThat(result.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
            assertThat(result.error().httpStatus()).isEqualTo(200);
            assertThat(result.member()).isNull();
            assertThat(result.wire().response().status()).isEqualTo(200);
            assertThat(result.wire().response().body()).isEqualTo("{\"memberId\":\"S123456789\",\"firstN");
            // The part that arrived is on screen, with a note that it is incomplete.
            assertThat(result.additionalFields().get(0)).isEqualTo(AdditionalField.unparsedBody("{\"memberId\":\"S123456789\",\"firstN"));
            assertThat(result.additionalFields().get(1).kind()).isEqualTo(AdditionalField.Kind.NOTE);
            assertThat(result.additionalFields().get(1).value()).startsWith("The body is incomplete");
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }
}
