package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Proves the timeout and refused-connection classification against the real JDK HTTP client.
 *
 * <p>MockRestServiceServer replaces the request factory, so it cannot show which exception types
 * the real client throws, nor what happens when a response starts and does not finish. These cases
 * use a socket on the loopback interface only. Nothing leaves the machine.
 */
class RestClientsLoopbackTest {

    private final ExchangeExecutor executor = new ExchangeExecutor();

    @Test
    void aServerThatAcceptsAndNeverAnswersIsATimeout() throws IOException {
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
            RestClient client = RestClients.withTimeouts(RestClient.builder(), 2000, 300);
            String url = "http://127.0.0.1:" + silent.getLocalPort() + "/cds-services/x";

            VendorExchange exchange = executor.execute(client, TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, url),
                    ResolvedText.plain("{}"), ExchangeOptions.json("application/json"));

            assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
            assertThat(exchange.httpStatus()).isNull();
            assertThat(exchange.elapsedMillis()).isGreaterThanOrEqualTo(250);
            acceptor.interrupt();
        } finally {
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    /** Serves one connection: reads the request, writes the given bytes, then either closes or holds the connection open. */
    private static ServerSocket serveOnce(byte[] response, boolean closeAfter, List<Socket> held) throws IOException {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try {
                Socket socket = server.accept();
                held.add(socket);
                // The request is small and arrives in one read. It is drained so the client is not left waiting to write.
                socket.getInputStream().read(new byte[8192]);
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
        return text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    @Test
    void aResponseThatStartsAndThenStallsKeepsItsStatusAndWhatArrived() throws IOException {
        // The service answers 200 and promises 100 bytes, sends ten, and stops. That is not "could
        // not be reached": the status arrived, and so did part of the body.
        List<Socket> held = new ArrayList<>();
        byte[] partial = ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"cards\":");
        try (ServerSocket server = serveOnce(partial, false, held)) {
            RestClient client = RestClients.withTimeouts(RestClient.builder(), 2000, 400);
            VendorExchange exchange = executor.execute(client,
                    TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, "http://127.0.0.1:" + server.getLocalPort() + "/cds-services/x"),
                    ResolvedText.plain("{}"), ExchangeOptions.json("application/json"));

            assertThat(exchange.httpStatus()).isEqualTo(200);
            assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
            assertThat(exchange.responseBody()).isEqualTo("{\"cards\":");
            assertThat(exchange.wireResponse().byteLength()).isEqualTo(9L);
            assertThat(exchange.failureDetail()).isEqualTo("The response started with HTTP 200 and 9 bytes of its body arrived before the "
                    + "connection ended or the read timeout passed.");
            assertThat(exchange.parseError()).isEqualTo("The body is incomplete.");
            assertThat(exchange.json()).isNull();
            assertThat(exchange.bodyNotes()).anySatisfy(note -> assertThat(note).startsWith("The body is incomplete"));
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    @Test
    void aConnectionThatEndsPartWayThroughTheBodyKeepsItsStatusAndWhatArrived() throws IOException {
        List<Socket> held = new ArrayList<>();
        byte[] partial = ascii("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/plain\r\nContent-Length: 100\r\n\r\nupstream ");
        try (ServerSocket server = serveOnce(partial, true, held)) {
            RestClient client = RestClients.withTimeouts(RestClient.builder(), 2000, 2000);
            VendorExchange exchange = executor.execute(client,
                    TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, "http://127.0.0.1:" + server.getLocalPort() + "/cds-services/x"),
                    ResolvedText.plain("{}"), ExchangeOptions.json("application/json"));

            // The status decides the kind of failure. What arrived of the body is still shown.
            assertThat(exchange.httpStatus()).isEqualTo(500);
            assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);
            assertThat(exchange.responseBody()).isEqualTo("upstream ");
        }
    }

    @Test
    void theClientAddsNoHeadersOfItsOwnBeyondTheTwoItMustWrite() throws IOException {
        // The wire view lists what this tool sends. If the client added an Accept-Encoding of its
        // own, the list would be wrong and the bytes shown would not be the bytes that arrived.
        List<Socket> held = new ArrayList<>();
        java.io.ByteArrayOutputStream seen = new java.io.ByteArrayOutputStream();
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try {
                Socket socket = server.accept();
                held.add(socket);
                byte[] buffer = new byte[8192];
                int read = socket.getInputStream().read(buffer);
                synchronized (seen) {
                    seen.write(buffer, 0, Math.max(read, 0));
                }
                socket.getOutputStream().write(ascii("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 12\r\n\r\n{\"cards\":[]}"));
                socket.getOutputStream().flush();
            } catch (IOException closed) {
                // The test closed the server socket.
            }
        });
        try (server) {
            RestClient client = RestClients.withTimeouts(RestClient.builder(), 2000, 2000);
            VendorExchange exchange = executor.execute(client,
                    TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, "http://127.0.0.1:" + server.getLocalPort() + "/cds-services/x"),
                    ResolvedText.plain("{}"), ExchangeOptions.json("application/json"));
            assertThat(exchange.ok()).isTrue();
            String request;
            synchronized (seen) {
                request = seen.toString(java.nio.charset.StandardCharsets.US_ASCII);
            }
            List<String> names = request.lines().skip(1).takeWhile(line -> !line.isEmpty())
                    .map(line -> line.substring(0, line.indexOf(':')).toLowerCase(java.util.Locale.ROOT)).toList();
            List<String> shown = exchange.request().headers().stream().map(h -> h.name().toLowerCase(java.util.Locale.ROOT)).toList();
            // Everything on the wire is either shown in the wire view or one of the headers the client must write.
            assertThat(names).doesNotContain("accept-encoding");
            assertThat(names.stream().filter(n -> !shown.contains(n)).toList())
                    .isSubsetOf("host", "content-length", "user-agent", "connection", "http2-settings", "upgrade");
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    @Test
    void aClosedPortIsUnavailable() throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        RestClient client = RestClients.withTimeouts(RestClient.builder(), 2000, 2000);
        String url = "http://127.0.0.1:" + port + "/cds-services/x";

        VendorExchange exchange = executor.execute(client, TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, url),
                ResolvedText.plain("{}"), ExchangeOptions.json("application/json"));

        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(exchange.httpStatus()).isNull();
    }
}
