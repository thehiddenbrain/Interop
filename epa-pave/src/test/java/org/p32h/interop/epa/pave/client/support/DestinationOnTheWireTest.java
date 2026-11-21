package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.springframework.web.client.RestClient;

/**
 * What the tool calls the destination of a url is where the bytes arrive (B1 of the second
 * guardrail audit). Every spelling is sent by the real JDK client, through the executor every live
 * client uses, at a listener on a loopback port, and the destination the guardrail reads is
 * compared with what the listener received. Nothing leaves the machine.
 */
class DestinationOnTheWireTest {

    private final ExchangeExecutor executor = new ExchangeExecutor();
    private final RestClient client = RestClients.withTimeouts(RestClient.builder(), 1000, 1000);

    /** A listener that answers every request with an empty 200 and records its request line. */
    private static final class Listener implements AutoCloseable {
        final ServerSocket socket;
        final List<String> received = new CopyOnWriteArrayList<>();

        Listener(InetAddress address) throws IOException {
            socket = new ServerSocket(0, 20, address);
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        try (Socket accepted = socket.accept()) {
                            InputStream in = accepted.getInputStream();
                            byte[] buffer = new byte[8192];
                            int read = in.read(buffer);
                            String request = read < 0 ? "" : new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
                            // Recorded before the answer is written, so the call has returned only after it is here.
                            received.add(request.lines().findFirst().orElse(""));
                            accepted.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .getBytes(StandardCharsets.ISO_8859_1));
                            accepted.getOutputStream().flush();
                        }
                    }
                } catch (IOException closed) {
                    // The test closed the server socket.
                }
            });
        }

        int port() {
            return socket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** Sends the url and says whether the listener received it. */
    private boolean arrives(Listener listener, String url) {
        int before = listener.received.size();
        try {
            executor.execute(client, TestPreparedCalls.to(EpaOperation.PAS_INQUIRE, url), ResolvedText.plain("{}"),
                    ExchangeOptions.json("application/fhir+json"));
        } catch (EndpointNotConfiguredException refused) {
            // The executor refused the url before anything was sent.
            return false;
        }
        return listener.received.size() > before;
    }

    @Test
    void everySpellingOfTheListenersAddressArrivesThereAndIsReadAsIt() throws IOException {
        try (Listener listener = new Listener(InetAddress.getByName("127.0.0.1"))) {
            int port = listener.port();
            UrlNormal.Destination there = UrlNormal.destination("http://127.0.0.1:" + port + "/pa/fhir");
            List<String> spellings = List.of("http://127.0.0.1:" + port + "/pa/fhir/Claim/$inquire",
                    "http://127.0.0.1:00" + port + "/pa/fhir/Claim/$inquire", "http://127.0.0.1:0" + port + "/pa/fhir/Claim/$inquire",
                    "http://127.0.0.1:000000000" + port + "/pa/fhir/Claim/$inquire", "HTTP://127.0.0.1:" + port + "/pa/fhir/Claim/$inquire",
                    "http://LOCALHOST:" + port + "/pa/fhir/Claim/$inquire", "http://user@127.0.0.1:" + port + "/pa/fhir/Claim/$inquire",
                    "http://u:pw@127.0.0.1:" + port + "/pa/fhir/Claim/$inquire", "http://127.000.000.001:" + port + "/pa/fhir/Claim/$inquire",
                    "http://2130706433:" + port + "/pa/fhir/Claim/$inquire", "http://[::ffff:127.0.0.1]:" + port + "/pa/fhir/Claim/$inquire");
            for (String url : spellings) {
                UrlNormal.Destination read = UrlNormal.destination(url);
                assertThat(arrives(listener, url)).as(url).isTrue();
                assertThat(read.readable()).as(url).isTrue();
                assertThat(there.covers(read)).as(url).isTrue();
            }
        }
    }

    @Test
    void aSpellingTheClientWouldNotSendIsNotReadableAndNothingArrives() throws IOException {
        try (Listener listener = new Listener(InetAddress.getByName("127.0.0.1"))) {
            int port = listener.port();
            List<String> spellings = List.of("http://127.1:" + port + "/x", "http://0x7f.0.0.1:" + port + "/x",
                    "http://127.0.0.1:" + (port + 65536) + "/x", "http://127.0.0.1:" + (port + 4294967296L) + "/x",
                    "http://127.0.0.1:+" + port + "/x", "http://a@b@127.0.0.1:" + port + "/x", "http://127.0.0.1:" + port + ":80/x");
            for (String url : spellings) {
                UrlNormal.Destination read = UrlNormal.destination(url);
                assertThat(read == null || !read.readable()).as(url).isTrue();
                assertThat(UrlNormal.sendable(url)).as(url).isNull();
                assertThat(arrives(listener, url)).as(url).isFalse();
            }
            assertThat(listener.received).isEmpty();
        }
    }

    @Test
    void aSpellingThatArrivesAnywhereArrivesWhereTheToolSaysItGoes() throws IOException {
        // A trailing dot and an explicit default-looking spelling of other hosts: whatever the
        // resolver of this machine makes of them, if bytes arrive, the tool named this listener.
        try (Listener listener = new Listener(InetAddress.getByName("127.0.0.1"))) {
            int port = listener.port();
            UrlNormal.Destination there = UrlNormal.destination("http://127.0.0.1:" + port);
            for (String url : List.of("http://localhost.:" + port + "/x", "http://LOCALHOST.:" + port + "/x", "http://127.0.0.1.:" + port + "/x",
                    "http://localhost:" + port + "/x")) {
                boolean arrived = arrives(listener, url);
                UrlNormal.Destination read = UrlNormal.destination(url);
                if (arrived) {
                    assertThat(read.readable()).as(url).isTrue();
                    assertThat(there.covers(read)).as(url).isTrue();
                }
            }
        }
    }

    @Test
    void anIpv6LiteralIsReadAsTheClientReadsIt() throws IOException {
        InetAddress v6;
        try {
            v6 = InetAddress.getByName("::1");
            new ServerSocket(0, 1, v6).close();
        } catch (IOException noIpv6) {
            // This machine has no IPv6 loopback; the IPv4 spellings above still hold.
            return;
        }
        try (Listener listener = new Listener(v6)) {
            int port = listener.port();
            String url = "http://[0:0:0:0:0:0:0:1]:00" + port + "/x";
            assertThat(arrives(listener, url)).as(url).isTrue();
            UrlNormal.Destination read = UrlNormal.destination(url);
            assertThat(read.readable()).isTrue();
            assertThat(read.port()).isEqualTo(port);
            assertThat(read.sameOrigin(UrlNormal.destination("http://[::1]:" + port))).isTrue();
        }
    }

    @Test
    void aHostOfDigitsIsComparedAsTheJdkResolvesIt() throws IOException {
        for (String host : List.of("0177.0.0.1", "017.0.0.1", "2130706433", "127.000.000.001", "1.2.3", "4294967295", "0", "10.1")) {
            String jdk = InetAddress.getByName(host).getHostAddress();
            UrlNormal.Destination read = UrlNormal.destination("http://" + host + ":1/x");
            UrlNormal.Destination written = UrlNormal.destination("http://" + jdk + ":1/x");
            assertThat(read.sameOrigin(written)).as(host + " is " + jdk).isTrue();
        }
        assertThat(UrlNormal.destination("http://0177.0.0.1:1/x").sameOrigin(UrlNormal.destination("http://127.0.0.1:1/x"))).isFalse();
    }
}
