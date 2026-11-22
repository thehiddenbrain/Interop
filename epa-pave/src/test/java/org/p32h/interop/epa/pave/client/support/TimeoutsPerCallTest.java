package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.TimedCalls;
import org.p32h.interop.epa.pave.service.config.Timeouts;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.web.client.RestClient;

/**
 * Each call is sent with the timeouts it carries (plan 5.2): two environments, two read timeouts,
 * one loopback server that answers only when the test lets it. No wall-clock assertion: the short
 * timeout is far below the server's hold, the long one far above the moment the test releases it.
 */
class TimeoutsPerCallTest {

    private HttpServer server;
    private CountDownLatch release;
    private String url;

    @BeforeEach
    void start() throws IOException {
        release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try {
                // Held until the test releases it, or for ten seconds at the most.
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"cards\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/cds-services/order-sign";
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void twoEnvironmentsTwoReadTimeouts() throws Exception {
        FakeGateway gateway = new FakeGateway();
        OnyxProperties onyx = OnyxProperties.defaults();
        OnyxTokenService tokens = OnyxAccessTestFactory.tokens(onyx, gateway, RestClient.builder().build(), Clock.systemUTC());
        OnyxAccess access = new OnyxAccess(new ExchangeExecutor(), tokens, gateway, onyx, RestClient.builder());

        PreparedCall impatient = TimedCalls.to(EpaOperation.CRD_ORDER_SIGN, url, "Placeholder Short",
                new Timeouts(2000, 200, Timeouts.FROM_ENVIRONMENT, Timeouts.FROM_ENVIRONMENT));
        PreparedCall patient = TimedCalls.to(EpaOperation.CRD_ORDER_SIGN, url, "Placeholder Long",
                new Timeouts(2000, 30000, Timeouts.FROM_ENVIRONMENT, Timeouts.FROM_ENVIRONMENT));

        VendorExchange shortWait = access.send(impatient, ResolvedText.plain("{}"), ExchangeOptions.json("application/json"));
        assertThat(shortWait.failureType()).isEqualTo(OnyxFailureType.TIMEOUT);

        Thread releaser = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        VendorExchange longWait = access.send(patient, ResolvedText.plain("{}"), ExchangeOptions.json("application/json"));
        releaser.join();
        assertThat(longWait.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(longWait.httpStatus()).isEqualTo(200);
    }

    @Test
    void theClientForAPairOfTimeoutsIsBuiltOnceAndOnlyForItsOwnBuilder() {
        RestClient.Builder builder = RestClient.builder();
        RestClient first = RestClients.cached(builder, 1000, 2000);
        assertThat(RestClients.cached(builder, 1000, 2000)).isSameAs(first);
        assertThat(RestClients.cached(builder, 1000, 3000)).isNotSameAs(first);
        assertThat(RestClients.cached(RestClient.builder(), 1000, 2000)).isNotSameAs(first);
    }

    @Test
    void theRawClientUsesTheCallsTimeoutsToo() {
        FakeGateway gateway = new FakeGateway();
        OnyxProperties onyx = OnyxProperties.defaults();
        OnyxTokenService tokens = OnyxAccessTestFactory.tokens(onyx, gateway, RestClient.builder().build(), Clock.systemUTC());
        org.p32h.interop.epa.pave.client.raw.HttpRawRequestClient raw = new org.p32h.interop.epa.pave.client.raw.HttpRawRequestClient(
                new ExchangeExecutor(), tokens, gateway, onyx, RestClient.builder());
        PreparedCall impatient = TimedCalls.to(EpaOperation.CRD_ORDER_SIGN, url, "Placeholder Short",
                new Timeouts(2000, 200, Timeouts.FROM_ENVIRONMENT, Timeouts.FROM_ENVIRONMENT));
        assertThat(raw.send(impatient, ResolvedText.plain("{}")).failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
    }
}
