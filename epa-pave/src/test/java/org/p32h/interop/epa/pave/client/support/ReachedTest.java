package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.Reached;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ScenarioBehavior;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioSelection;
import org.p32h.interop.epa.pave.service.scenario.ScenarioStep;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Whether a request that failed can have reached the service.
 *
 * <p>For a write this is what the advice turns on: a request that never reached the payer can be
 * sent again, and one that may have reached it must be asked about first. "Did not reach it" is the
 * answer that lets a tester send a second prior authorization request, so these tests hold it to
 * the failures that happen before any of the request is written, and to nothing else.
 */
class ReachedTest {

    private static final String URL = "https://onyx.example.invalid/fhir/Claim/$submit";
    private static final ExchangeOptions FHIR = ExchangeOptions.json("application/fhir+json");
    private static final String TLS = "The certificate of the service was not accepted, or the secure connection could not be set up: "
            + "the TLS handshake failed. On a network with an inspection proxy, check the trust store.";

    private final ExchangeExecutor executor = new ExchangeExecutor();

    /** A submission through the executor, with the HTTP layer failing in the given way before any response. */
    private VendorExchange submitFailingWith(Exception failure) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(request -> {
            if (failure instanceof IOException io) {
                throw io;
            }
            throw (RuntimeException) failure;
        });
        return executor.execute(builder.build(), TestPreparedCalls.to(EpaOperation.PAS_SUBMIT, URL), ResolvedText.plain("{}"), FHIR);
    }

    // ---- the executor, by the kind of failure ----

    /**
     * A call through a stand-in proxy on this machine, by the real JDK client, so that the
     * exception is the one the JDK raises and not one written here.
     *
     * @param answer what the proxy writes back to the CONNECT, or null to close without a word
     */
    private VendorExchange throughProxy(String answer) throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread thread = new Thread(() -> {
                try (Socket socket = proxy.accept()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                            java.nio.charset.StandardCharsets.US_ASCII));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        // Reads the CONNECT and its headers, and nothing is done with them.
                    }
                    if (answer != null) {
                        socket.getOutputStream().write(answer.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                        Thread.sleep(200);
                    }
                } catch (IOException | InterruptedException ignored) {
                    // The test reads what the client made of it.
                }
            });
            thread.setDaemon(true);
            thread.start();
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .proxy(java.net.ProxySelector.of(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.getLocalPort())))
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
            org.springframework.http.client.JdkClientHttpRequestFactory factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(java.time.Duration.ofSeconds(3));
            RestClient client = RestClient.builder().requestFactory(factory).build();
            return executor.execute(client, TestPreparedCalls.to(EpaOperation.PAS_SUBMIT, URL), ResolvedText.plain("{}"), FHIR);
        }
    }

    @Test
    void aProxyThatRefusesTheTunnelIsNamedWithItsStatusAndTheServiceWasNotReached() throws Exception {
        VendorExchange exchange = throughProxy("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n");

        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(exchange.failureDetail()).isEqualTo("The proxy between this machine and the service refused the connection (it "
                + "answered 403). Check the proxy settings, or ask for the service's host to be allowed through the proxy.");
        assertThat(exchange.reached()).isEqualTo(Reached.NO);
        assertThat(exchange.httpStatus()).isNull();
    }

    @Test
    void aProxyThatSaysNothingIsNeverTakenForARefusedTunnel() throws Exception {
        VendorExchange exchange = throughProxy(null);

        // The JDK waits for an answer that never comes; whatever it makes of that, it is not a refusal by the proxy.
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(exchange.failureDetail()).doesNotStartWith("The proxy between this machine");
        // An IOException that is not the JDK's tunnel message keeps the fallback, which never says "not reached".
        VendorExchange other = submitFailingWith(new IOException("Tunnel closed by peer"));
        assertThat(other.reached()).isEqualTo(Reached.CANNOT_SAY);
        assertThat(other.failureDetail()).startsWith("The connection to the service failed before an answer arrived.");
    }

    @Test
    void aFailureBeforeAnyOfTheRequestIsWrittenHasNotReachedTheService() {
        Map<Exception, String> before = new LinkedHashMap<>();
        before.put(new ConnectException("Connection refused"), "The connection was refused or could not be opened.");
        before.put(new UnknownHostException("onyx.example.invalid"), "The name of the service's host could not be found.");
        before.put(new UnresolvedAddressException(), "The name of the service's host could not be found.");
        before.put(new NoRouteToHostException("No route to host"), "There is no network route to the host.");
        before.put(new HttpConnectTimeoutException("HTTP connect timed out"), "The connection attempt timed out before the service was reached.");
        // The handshake comes before the request, so a certificate that is not trusted means nothing was sent.
        before.put(new SSLHandshakeException("PKIX path building failed"), TLS);
        before.put(new SSLPeerUnverifiedException("peer not authenticated"), TLS);
        before.forEach((failure, detail) -> {
            VendorExchange exchange = submitFailingWith(failure);
            String what = failure.getClass().getSimpleName();
            assertThat(exchange.reached()).as(what).isEqualTo(Reached.NO);
            assertThat(exchange.failureType()).as(what).isEqualTo(OnyxFailureType.UNAVAILABLE);
            assertThat(exchange.failureDetail()).as(what).isEqualTo(detail).doesNotContain("Exception").doesNotContain("S123456789");
            assertThat(exchange.httpStatus()).as(what).isNull();
        });
    }

    @Test
    void aFailureAfterTheConnectionOpenedLeavesItOpenWhetherTheServiceWasReached() {
        Map<Exception, String> after = new LinkedHashMap<>();
        after.put(new SocketTimeoutException("Read timed out"), "No response arrived inside the read timeout.");
        after.put(new HttpTimeoutException("request timed out"), "No response arrived inside the read timeout.");
        // The connection was there and then it was not. The request may already have been on its way.
        // Worded for a business tester: no Java class is named, and the exception's message, which quotes a member ID here, never is.
        String unnamed = "The connection to the service failed before an answer arrived. This tool cannot tell at which point, so the "
                + "request may have reached the service. A proxy between this machine and the service is a common cause.";
        String secure = "The secure connection could not be set up, or failed after it was opened. On a network with an inspection "
                + "proxy, check the trust store.";
        after.put(new IOException("Connection reset for member S123456789"), unnamed);
        after.put(new SSLException("Connection reset"), secure);
        after.put(new SSLProtocolException("Read timed out"), secure);
        // Something this tool has no name for is never taken for "nothing was sent".
        after.put(new IllegalStateException("unexpected"), unnamed);
        after.forEach((failure, detail) -> {
            VendorExchange exchange = submitFailingWith(failure);
            String what = failure.getClass().getSimpleName();
            assertThat(exchange.reached()).as(what).isEqualTo(Reached.CANNOT_SAY);
            assertThat(exchange.failureDetail()).as(what).isEqualTo(detail).doesNotContain("Exception").doesNotContain("S123456789");
            assertThat(exchange.httpStatus()).as(what).isNull();
        });
        assertThat(submitFailingWith(new SocketTimeoutException("Read timed out")).failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
        assertThat(submitFailingWith(new IOException("Connection reset")).failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
    }

    /**
     * The tool's stop interrupts a thread that still waits for a service once its time is up. The
     * request had left and no answer had come: the line says so, and never that the service failed
     * or could not be reached. The real JDK client, against a socket on this machine that never answers.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aCallCutShortByTheStopSaysNoAnswerHadComeAndThatWhetherItArrivedIsNotKnown(CapturedOutput output) throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            java.util.concurrent.CountDownLatch accepted = new java.util.concurrent.CountDownLatch(1);
            Thread far = new Thread(() -> {
                try (Socket socket = silent.accept()) {
                    accepted.countDown();
                    // Reads what is sent and never answers, until the socket is closed under it.
                    while (socket.getInputStream().read() >= 0) {
                        // nothing
                    }
                } catch (IOException ignored) {
                    // The test reads what the client made of it.
                }
            });
            far.setDaemon(true);
            far.start();
            String url = "http://127.0.0.1:" + silent.getLocalPort() + "/fhir/Claim/$submit";
            RestClient client = RestClients.withTimeouts(RestClient.builder(), 3000, 60_000);
            java.util.concurrent.atomic.AtomicReference<VendorExchange> result = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean flagAfter = new java.util.concurrent.atomic.AtomicBoolean();
            Thread caller = new Thread(() -> {
                result.set(executor.execute(client, TestPreparedCalls.to(EpaOperation.PAS_SUBMIT, url), ResolvedText.plain("{}"), FHIR));
                flagAfter.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            assertThat(accepted.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(20_000);
            assertThat(caller.isAlive()).isFalse();

            VendorExchange exchange = result.get();
            assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
            assertThat(exchange.failureDetail()).isEqualTo("No answer had arrived when this tool was stopped, so whether the service "
                    + "received the request is not known.");
            assertThat(exchange.reached()).isEqualTo(Reached.CANNOT_SAY);
            assertThat(exchange.httpStatus()).isNull();
            // The code that called still sees that it was interrupted.
            assertThat(flagAfter.get()).isTrue();
            // Named, so it is not the line for a failure the result cannot name.
            assertThat(output.getAll()).doesNotContain("failed in a way the result does not name");
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailureTheResultCannotNameLeavesItsTypesAndTheHostInTheLogAndNothingOfTheUrlOrTheMessage(CapturedOutput output) {
        String url = "https://onyx.example.invalid/fhir/Patient/S123456789?identifier=S123456789";
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(url)).andRespond(request -> {
            throw new IOException("Connection reset while reading " + url);
        });
        VendorExchange exchange = executor.execute(builder.build(), TestPreparedCalls.to(EpaOperation.PAS_SUBMIT, url), ResolvedText.plain("{}"), FHIR);
        assertThat(exchange.failureDetail()).doesNotContain("Exception").doesNotContain("S123456789");
        assertThat(output.getAll()).contains("A call to the host onyx.example.invalid failed in a way the result does not name: ")
                .contains("java.io.IOException").doesNotContain("S123456789").doesNotContain("/fhir/Patient").doesNotContain("Connection reset");

        // A failure the result names in plain words needs no line of its own.
        int lines = output.getAll().split("A call to the host", -1).length;
        submitFailingWith(new ConnectException("Connection refused"));
        assertThat(output.getAll().split("A call to the host", -1).length).isEqualTo(lines);
    }

    @Test
    void aResponseOfAnyKindShowsThatTheRequestWasReceived() {
        Object[][] answers = {{200, "{\"resourceType\":\"Bundle\"}"}, {200, "{\"resourceType\": "}, {200, null}, {400, "{}"}, {404, null},
                {500, "<html>oops</html>"}, {503, null}};
        for (Object[] answer : answers) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            var response = withStatus(HttpStatus.valueOf((Integer) answer[0])).contentType(MediaType.APPLICATION_JSON);
            server.expect(requestTo(URL)).andRespond(answer[1] == null ? response : response.body((String) answer[1]));
            VendorExchange exchange = executor.execute(builder.build(), TestPreparedCalls.to(EpaOperation.PAS_SUBMIT, URL), ResolvedText.plain("{}"), FHIR);
            assertThat(exchange.reached()).as(answer[0] + " " + answer[1]).isEqualTo(Reached.YES);
        }
    }

    // ---- the real client, on the loopback interface only ----

    @Test
    void aClosedPortHasNotReachedTheServiceAccordingToTheRealClient() throws IOException {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        RestClient client = RestClients.withTimeouts(RestClient.builder(), 2000, 2000);

        VendorExchange exchange = executor.execute(client, TestPreparedCalls.to(EpaOperation.PAS_SUBMIT, "http://127.0.0.1:" + port + "/fhir/Claim/$submit"),
                ResolvedText.plain("{}"), FHIR);

        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(exchange.failureDetail()).isEqualTo("The connection was refused or could not be opened.");
        assertThat(exchange.reached()).isEqualTo(Reached.NO);
    }

    @Test
    void aServerThatTakesTheRequestAndHangsUpWithoutAnsweringMayHaveReceivedItAccordingToTheRealClient() throws IOException {
        // The request is read in full and the connection is then closed. No status ever arrives, and
        // the service has had the submission all the same: this must never read as "nothing was sent".
        List<Socket> held = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try {
                    Socket socket = server.accept();
                    held.add(socket);
                    byte[] buffer = new byte[8192];
                    int read = socket.getInputStream().read(buffer);
                    synchronized (seen) {
                        seen.add(new String(buffer, 0, Math.max(read, 0), java.nio.charset.StandardCharsets.US_ASCII));
                    }
                    socket.close();
                } catch (IOException closed) {
                    // The test closed the server socket.
                }
            });
            RestClient client = RestClients.withTimeouts(RestClient.builder(), 5000, 5000);

            VendorExchange exchange = executor.execute(client,
                    TestPreparedCalls.to(EpaOperation.PAS_SUBMIT, "http://127.0.0.1:" + server.getLocalPort() + "/fhir/Claim/$submit"),
                    ResolvedText.plain("{\"resourceType\":\"Bundle\"}"), FHIR);

            assertThat(exchange.httpStatus()).isNull();
            assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
            assertThat(exchange.reached()).isEqualTo(Reached.CANNOT_SAY);
            // And the far side did have it.
            synchronized (seen) {
                assertThat(seen).singleElement().satisfies(request -> assertThat(request).startsWith("POST /fhir/Claim/$submit"));
            }
        } finally {
            for (Socket socket : held) {
                socket.close();
            }
        }
    }

    // ---- the mock, and a call that was never sent ----

    private static ScenarioSelection scenario(ScenarioBehavior behavior) {
        ScenarioStep step = new ScenarioStep(behavior, behavior == ScenarioBehavior.RESPOND ? 200 : null, 0L,
                behavior == ScenarioBehavior.RESPOND ? "{}" : null, null);
        return new ScenarioSelection(new Scenario("test-01", 1, "Test", "d", null, null, step, Map.of(), null, "Synthetic.", null, null, "test.json"),
                "chosen in the scenario selector");
    }

    @Test
    void theMockSaysWhatTheBehaviourOfItsScenarioWouldMeanOnLive() {
        MockExchanges mock = new MockExchanges(AppProperties.of(Map.of("mock-latency-enabled", "false")));
        Map<ScenarioBehavior, Reached> models = new LinkedHashMap<>();
        // An answer: the service was reached. A refused connection: it was not. A read timeout: it may have been.
        models.put(ScenarioBehavior.RESPOND, Reached.YES);
        models.put(ScenarioBehavior.UNAVAILABLE, Reached.NO);
        models.put(ScenarioBehavior.TIMEOUT, Reached.CANNOT_SAY);
        assertThat(models).containsOnlyKeys(ScenarioBehavior.values());
        models.forEach((behavior, reached) -> {
            VendorExchange exchange = mock.answer(TestPreparedCalls.unconfigured(EpaOperation.PAS_SUBMIT), ResolvedText.plain("{}"), FHIR,
                    scenario(behavior), null);
            assertThat(exchange.reached()).as(behavior.name()).isEqualTo(reached);
            // Nothing left this machine on mock, whatever the scenario models, and the request says so.
            assertThat(exchange.request().sent()).isFalse();
        });
    }

    @Test
    void aCallThatWasNotSentBecauseNoTokenCouldBeObtainedHasNotReachedTheService() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FakeGateway gateway = new FakeGateway().url(EpaOperation.PAS_SUBMIT, URL);
        OnyxProperties onyx = OnyxProperties.of(Map.of("auth.enabled", "true", "auth.token-url", "https://auth.example.invalid/oauth/token",
                "auth.client-id", "placeholder-client", "auth.client-secret", "placeholder-client-secret"));
        server.expect(requestTo("https://auth.example.invalid/oauth/token")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        VendorExchange exchange = OnyxAccessTestFactory.create(executor, onyx, gateway, builder.build(), Clock.systemUTC())
                .send(gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)), ResolvedText.plain("{}"), FHIR);

        // Only the token endpoint was called. The submission never left.
        server.verify();
        assertThat(exchange.request().sent()).isFalse();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(exchange.reached()).isEqualTo(Reached.NO);
    }

    // ---- the exchange itself ----

    @Test
    void anExchangeSaysTheServiceWasReachedWhenAResponseArrivedAndOnlyThen() {
        WireRequest request = new WireRequest("POST", URL, URL, List.of(), "{}", true, null);
        // A response and "not reached", or no response and "reached": neither can have happened.
        for (Reached wrong : List.of(Reached.NO, Reached.CANNOT_SAY)) {
            assertThatThrownBy(() -> new VendorExchange(request, 200, List.of(), "{}", null, JsonSafe.parse("{}").node(), null, 5,
                    OnyxFailureType.NONE, null, Set.of(), null, null, List.of(), wrong)).as(wrong.name()).isInstanceOf(IllegalArgumentException.class);
        }
        for (OnyxFailureType none : List.of(OnyxFailureType.TIMEOUT, OnyxFailureType.UNAVAILABLE)) {
            assertThatThrownBy(() -> new VendorExchange(request, null, List.of(), null, null, null, null, 5, none, null, Set.of(), null, null,
                    List.of(), Reached.YES)).as(none.name()).isInstanceOf(IllegalArgumentException.class);
        }
        // Left unsaid, it follows from the status, and is then never "no": that answer tells a tester a write can go again.
        assertThat(new VendorExchange(request, 500, List.of(), null, null, null, null, 5, OnyxFailureType.SERVER_ERROR, null, Set.of(), null,
                null, List.of()).reached()).isEqualTo(Reached.YES);
        for (OnyxFailureType none : List.of(OnyxFailureType.TIMEOUT, OnyxFailureType.UNAVAILABLE)) {
            assertThat(new VendorExchange(request, null, List.of(), null, null, null, null, 5, none, null, Set.of(), null, null, List.of())
                    .reached()).as(none.name()).isEqualTo(Reached.CANNOT_SAY);
            assertThat(new VendorExchange(request, null, List.of(), null, null, null, null, 5, none, null, Set.of(), null, null, List.of(), null)
                    .reached()).as(none.name()).isEqualTo(Reached.CANNOT_SAY);
        }
    }

    @Test
    void theThreeWaysToDescribeACallWithNoResponseEachSayWhetherItCanHaveArrivedAndACopyKeepsIt() {
        WireRequest request = new WireRequest("POST", URL, URL, List.of(), "{}", true, null);
        VendorExchange refused = Exchanges.unavailable(request, 3L, "The connection was refused or could not be opened.");
        VendorExchange broken = Exchanges.cutOff(request, 3L, "The request could not be completed (IOException).");
        VendorExchange timeout = Exchanges.timeout(request, 3L);
        assertThat(refused.reached()).isEqualTo(Reached.NO);
        assertThat(broken.reached()).isEqualTo(Reached.CANNOT_SAY);
        assertThat(timeout.reached()).isEqualTo(Reached.CANNOT_SAY);
        // Both are the same failure type, which is why the type alone cannot carry the advice.
        assertThat(refused.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(broken.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        for (VendorExchange exchange : List.of(refused, broken, timeout)) {
            assertThat(exchange.withScenario("s", "how").withSignals(Set.of()).withElapsedMillis(9).reached()).isEqualTo(exchange.reached());
        }
        assertThat(Exchanges.fromResponse(request, 200, List.of(), "{}", 1L, true).withJson(JsonSafe.parse("{\"a\":1}").node()).reached())
                .isEqualTo(Reached.YES);
    }
}
