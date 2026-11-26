package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

/**
 * F6 of the member data audit: the line the executor logs for a failure it cannot name in plain
 * words is not written for a lookup, whose client logs its outcome and HTTP status only, and for
 * every other client it holds the host and never the path or the query. A loopback server that
 * accepts a connection and closes it without answering produces such a failure.
 */
@ExtendWith(OutputCaptureExtension.class)
class FailedCallLogTest {

    private final ExchangeExecutor executor = new ExchangeExecutor();
    private final RestClient client = RestClients.withTimeouts(RestClient.builder(), 2000, 2000);

    /** Accepts every connection, reads what arrives and closes it with no answer. */
    private static ServerSocket closing() throws IOException {
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    try (Socket socket = server.accept()) {
                        socket.getInputStream().read(new byte[8192]);
                    }
                }
            } catch (IOException closed) {
                // The test closed the server socket.
            }
        });
        return server;
    }

    @Test
    void aFailedLookupLeavesNoLineOfTheExecutorAndAnotherClientsLineHoldsTheHostOnly(CapturedOutput output) throws IOException {
        try (ServerSocket server = closing()) {
            String base = "http://127.0.0.1:" + server.getLocalPort();
            VendorExchange lookup = executor.execute(client, TestPreparedCalls.to(EpaOperation.MEMBER_LOOKUP,
                    base + "/members/S123456789?memberId=S123456789"), null, ExchangeOptions.json(null));
            assertThat(lookup.failureType()).isNotNull();
            assertThat(output.getAll()).doesNotContain("ExchangeExecutor").doesNotContain("S123456789");

            VendorExchange inquiry = executor.execute(client, TestPreparedCalls.to(EpaOperation.PAS_INQUIRE,
                    base + "/fhir/Claim/$inquire?patient=S123456789"), ResolvedText.plain("{}"), ExchangeOptions.json("application/fhir+json"));
            assertThat(inquiry.failureType()).isNotNull();
            assertThat(output.getAll()).contains("A call to the host 127.0.0.1 failed in a way the result does not name")
                    .doesNotContain("S123456789").doesNotContain("/fhir/Claim").doesNotContain("patient=");
        }
    }
}
