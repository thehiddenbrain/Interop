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
 * the real client throws. These two cases use a socket on the loopback interface only. Nothing
 * leaves the machine.
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
