package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The HTTP layer every live client shares, driven through MockRestServiceServer. Nothing here touches a network. */
class ExchangeExecutorTest {

    private static final String URL = "https://onyx.example.invalid/cds-services/order-sign";
    private static final String REQUEST = "{\"hook\":\"order-sign\"}";

    private final ExchangeExecutor executor = new ExchangeExecutor();
    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    private VendorExchange post() {
        return executor.execute(client, TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, URL), ResolvedText.plain(REQUEST),
                ExchangeOptions.json("application/json"));
    }

    @Test
    void sendsTheBodyAndDefaultHeadersAndParsesASuccess() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", "application/json"))
                .andExpect(header("Content-Type", "application/json"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().string(REQUEST))
                .andRespond(withSuccess("{\"cards\":[]}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = post();

        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.httpStatus()).isEqualTo(200);
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(exchange.responseBody()).isEqualTo("{\"cards\":[]}");
        assertThat(exchange.json().get("cards").isArray()).isTrue();
        assertThat(exchange.parseError()).isNull();
        assertThat(exchange.request().sent()).isTrue();
        assertThat(exchange.request().method()).isEqualTo("POST");
        assertThat(exchange.request().url()).isEqualTo(URL);
        assertThat(exchange.request().body()).isEqualTo(REQUEST);
        assertThat(exchange.elapsedMillis()).isGreaterThanOrEqualTo(0);
        assertThat(exchange.wireResponse().byteLength()).isEqualTo(12L);
        assertThat(exchange.wireResponse().parsed()).isTrue();
    }

    @Test
    void anHttp400IsABadRequestAndItsOperationOutcomeIsKept() {
        String outcome = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\"}]}";
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body(outcome).contentType(MediaType.APPLICATION_JSON));

        VendorExchange exchange = post();

        assertThat(exchange.ok()).isFalse();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(exchange.httpStatus()).isEqualTo(400);
        assertThat(exchange.responseBody()).isEqualTo(outcome);
        assertThat(exchange.json().get("resourceType").asString()).isEqualTo("OperationOutcome");
        assertThat(exchange.failureDetail()).isEqualTo("The service answered HTTP 400.");
    }

    @Test
    void anHttp500IsAServerErrorAndANonJsonErrorBodyIsStillShown() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("<html>Bad Gateway</html>"));

        VendorExchange exchange = post();

        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);
        assertThat(exchange.httpStatus()).isEqualTo(500);
        assertThat(exchange.responseBody()).isEqualTo("<html>Bad Gateway</html>");
        assertThat(exchange.json()).isNull();
        assertThat(exchange.parseError()).startsWith("The body is not valid JSON");
        assertThat(exchange.wireResponse().parsed()).isFalse();
    }

    @Test
    void a503IsAServerErrorWithNoBody() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        VendorExchange exchange = post();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);
        assertThat(exchange.httpStatus()).isEqualTo(503);
        assertThat(exchange.responseBody()).isNull();
        assertThat(exchange.wireResponse().byteLength()).isZero();
    }

    @Test
    void aRedirectIsNotFollowedAndIsReportedAgainstTheRequest() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FOUND));
        VendorExchange exchange = post();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(exchange.httpStatus()).isEqualTo(302);
    }

    @Test
    void aReadTimeoutIsATimeoutWithNoStatus() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        VendorExchange exchange = post();

        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
        assertThat(exchange.httpStatus()).isNull();
        assertThat(exchange.responseBody()).isNull();
        assertThat(exchange.wireResponse().byteLength()).isNull();
        assertThat(exchange.failureDetail()).isEqualTo("No response arrived inside the read timeout.");
        // The request is still described, so the tester can see what was sent.
        assertThat(exchange.request().body()).isEqualTo(REQUEST);
    }

    @Test
    void theJdkClientTimeoutTypesAreToldApart() {
        assertThat(ExchangeExecutor.classifyType(new IllegalStateException(new HttpTimeoutException("request timed out"))))
                .isEqualTo(OnyxFailureType.TIMEOUT);
        // A connect timeout is a subtype of the request timeout. It means the service was never reached.
        assertThat(ExchangeExecutor.classifyType(new IllegalStateException(new HttpConnectTimeoutException("HTTP connect timed out"))))
                .isEqualTo(OnyxFailureType.UNAVAILABLE);
    }

    @Test
    void aRefusedConnectionIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("Connection refused")));
        VendorExchange exchange = post();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(exchange.httpStatus()).isNull();
        assertThat(exchange.failureDetail()).isEqualTo("The connection was refused or could not be opened.");
    }

    @Test
    void anUnknownHostAndATlsFailureAreUnavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new UnknownHostException("onyx.example.invalid")));
        assertThat(post().failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new SSLHandshakeException("PKIX path building failed")));
        VendorExchange tls = post();
        assertThat(tls.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(tls.failureDetail()).contains("trust store");
    }

    @Test
    void anyOtherIoFailureIsUnavailableAndNamesOnlyTheExceptionType() {
        server.expect(requestTo(URL)).andRespond(withException(new IOException("stream reset for member S123456789")));
        VendorExchange exchange = post();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        // The exception message can quote the url or data, so it is never copied into the result.
        assertThat(exchange.failureDetail()).doesNotContain("S123456789").doesNotContain("stream reset");
    }

    @Test
    void aSuccessWithABodyThatIsNotJsonIsMalformed() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"cards\": [", MediaType.APPLICATION_JSON));
        VendorExchange exchange = post();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
        assertThat(exchange.httpStatus()).isEqualTo(200);
        // The raw bytes stay available so the screen can show what could not be parsed.
        assertThat(exchange.responseBody()).isEqualTo("{\"cards\": [");
        assertThat(exchange.json()).isNull();
        assertThat(exchange.parseError()).startsWith("The body is not valid JSON");
    }

    @Test
    void aSuccessWithAnEmptyBodyIsMalformedWhenABodyIsRequired() {
        server.expect(requestTo(URL)).andRespond(withSuccess());
        VendorExchange exchange = post();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
        assertThat(exchange.responseBody()).isNull();
        assertThat(exchange.parseError()).isEqualTo("The body is empty.");
    }

    @Test
    void aSuccessWithAnEmptyBodyIsFineWhenNoBodyIsExpected() {
        String url = "https://onyx.example.invalid/fhir/Subscription/sub-1";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.DELETE)).andExpect(content().string(""))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        VendorExchange exchange = executor.execute(client, TestPreparedCalls.to(EpaOperation.SUBSCRIPTION_DELETE, url), null,
                ExchangeOptions.read("application/fhir+json").withBodyRequired(false));
        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.httpStatus()).isEqualTo(204);
        assertThat(exchange.request().body()).isNull();
    }

    @Test
    void aGetSendsNoBodyAndNoContentType() {
        String url = "https://onyx.example.invalid/cds-services";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET)).andExpect(headerDoesNotExist("Content-Type"))
                .andRespond(withSuccess("{\"services\":[]}", MediaType.APPLICATION_JSON));
        VendorExchange exchange = executor.execute(client, TestPreparedCalls.to(EpaOperation.CRD_DISCOVERY, url),
                ResolvedText.plain("ignored"), ExchangeOptions.json("application/json"));
        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.request().body()).isNull();
    }

    @Test
    void collectionHeadersReplaceDefaultsAndABearerTokenIsSentButNeverShown() {
        PreparedCall call = TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, "POST", URL,
                List.of(new ResolvedHeader("accept", "application/fhir+json", "application/fhir+json"),
                        new ResolvedHeader("X-Api-Key", "[secret apiKey]", "placeholder-key-value")),
                new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX));
        server.expect(requestTo(URL)).andExpect(header("Accept", "application/fhir+json"))
                .andExpect(header("X-Api-Key", "placeholder-key-value"))
                .andExpect(header("Authorization", "Bearer placeholder-access-token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = executor.execute(client, call, ResolvedText.plain(REQUEST),
                ExchangeOptions.json("application/json").withBearerToken("placeholder-access-token"));

        server.verify();
        List<WireHeader> shown = exchange.request().headers();
        assertThat(shown).contains(new WireHeader("Authorization", "Bearer [redacted]"));
        assertThat(shown).contains(new WireHeader("X-Api-Key", "[redacted]"));
        assertThat(shown.toString()).doesNotContain("placeholder-access-token").doesNotContain("placeholder-key-value");
        assertThat(shown).filteredOn(h -> h.name().equalsIgnoreCase("accept")).hasSize(1);
    }

    @Test
    void anAuthorizationHeaderFromTheCollectionWinsOverTheTokenAndIsRedactedEvenWhenTypedLiterally() {
        PreparedCall call = TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, "POST", URL,
                List.of(new ResolvedHeader("Authorization", "Basic cGxhY2Vob2xkZXI=", "Basic cGxhY2Vob2xkZXI=")),
                new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX));
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Basic cGxhY2Vob2xkZXI="))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = executor.execute(client, call, ResolvedText.plain(REQUEST),
                ExchangeOptions.json("application/json").withBearerToken("unused-token"));

        server.verify();
        // The literal credential has no variable name to redact by, so the header name decides.
        assertThat(exchange.request().headers()).contains(new WireHeader("Authorization", "Basic [redacted]"));
        assertThat(exchange.request().headers().toString()).doesNotContain("cGxhY2Vob2xkZXI=");
    }

    @Test
    void theWireViewShowsTheMaskedBodyAndUrlWhileTheRealOnesAreSent() {
        ResolvedUrl url = new ResolvedUrl("{{baseUrl}}/token?key={{apiKey}}", "https://onyx.example.invalid/token?key=[secret apiKey]",
                "https://onyx.example.invalid/token?key=placeholder-key-value",
                List.of(new VariableUse("apiKey", VariableSource.ENVIRONMENT, true, "[secret apiKey]")), List.of(), "test");
        PreparedCall call = TestPreparedCalls.with(EpaOperation.UNMAPPED, "POST", url, List.of(),
                new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX), "token request", false);
        ResolvedText body = new ResolvedText("{\"secret\":\"{{clientSecret}}\"}", "{\"secret\":\"[secret clientSecret]\"}",
                "{\"secret\":\"placeholder-secret-value\"}", List.of(), List.of());
        server.expect(requestTo("https://onyx.example.invalid/token?key=placeholder-key-value"))
                .andExpect(content().string("{\"secret\":\"placeholder-secret-value\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = executor.execute(client, call, body, ExchangeOptions.json("application/json"));

        server.verify();
        assertThat(exchange.request().url()).isEqualTo("https://onyx.example.invalid/token?key=[secret apiKey]");
        assertThat(exchange.request().urlTemplate()).isEqualTo("{{baseUrl}}/token?key={{apiKey}}");
        assertThat(exchange.request().body()).isEqualTo("{\"secret\":\"[secret clientSecret]\"}");
    }

    @Test
    void credentialResponseHeadersAreRedacted() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON)
                .header("Set-Cookie", "session=placeholder-cookie").header("X-Request-Id", "abc-123"));
        VendorExchange exchange = post();
        assertThat(exchange.responseHeaders()).contains(new WireHeader("X-Request-Id", "abc-123"));
        assertThat(exchange.responseHeaders().toString()).doesNotContain("placeholder-cookie");
    }

    @Test
    void aBodyThatStillHoldsAPlaceholderIsRefusedAndNeverSentAsNoBody() {
        // An unresolved text has no send form. Treating that as "no body" would send an empty
        // request and report it as sent, and the tester would see a vendor rejection with no cause.
        ResolvedText unresolved = new ResolvedText("{\"patient\":\"{{patientId}}\"}", null, null, List.of(), List.of("patientId"));
        assertThatThrownBy(() -> executor.execute(client, TestPreparedCalls.to(EpaOperation.PAS_SUBMIT, URL), unresolved,
                ExchangeOptions.json("application/fhir+json")))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> {
                    assertThat(e.variables()).containsExactly("patientId");
                    assertThat(e.operation()).isEqualTo(EpaOperation.PAS_SUBMIT);
                    assertThat(e.environmentName()).isEqualTo("Test Sandbox");
                });
        server.verify();
    }

    @Test
    void aCallPreparedUnderAnEarlierConfigurationIsRefused() {
        FakeGateway gateway = new FakeGateway().url(EpaOperation.PAS_SUBMIT, URL);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("gateway", gateway);
        ExchangeExecutor guarded = new ExchangeExecutor(beans.getBeanProvider(org.p32h.interop.epa.pave.service.config.OperationGateway.class));
        PreparedCall prepared = gateway.prepare(org.p32h.interop.epa.pave.service.config.CallIntent.of(EpaOperation.PAS_SUBMIT));

        // The tester switches environment, or relocks, between preparing and sending.
        gateway.changeConfiguration();

        assertThatThrownBy(() -> guarded.execute(client, prepared, ResolvedText.plain(REQUEST), ExchangeOptions.json("application/fhir+json")))
                .isInstanceOf(ConfigurationChangedException.class);
        server.verify();

        // A call prepared under the current configuration goes out.
        PreparedCall fresh = gateway.prepare(org.p32h.interop.epa.pave.service.config.CallIntent.of(EpaOperation.PAS_SUBMIT));
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(guarded.execute(client, fresh, ResolvedText.plain(REQUEST), ExchangeOptions.json("application/fhir+json")).ok()).isTrue();
    }

    @Test
    void refusesToSendWithoutACompleteUrl() {
        PreparedCall unconfigured = TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN);
        assertThatThrownBy(() -> executor.execute(client, unconfigured, ResolvedText.plain(REQUEST), ExchangeOptions.json("application/json")))
                .isInstanceOf(EndpointNotConfiguredException.class);

        ResolvedUrl unresolved = new ResolvedUrl("{{baseUrl}}/cds-services/x", null, null, List.of(), List.of("baseUrl"), "test");
        PreparedCall partial = TestPreparedCalls.with(EpaOperation.CRD_ORDER_SIGN, "POST", unresolved, List.of(),
                EnvironmentRef.none(), null, false);
        assertThatThrownBy(() -> executor.execute(client, partial, ResolvedText.plain(REQUEST), ExchangeOptions.json("application/json")))
                .isInstanceOf(EndpointNotConfiguredException.class);

        PreparedCall noHost = TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, "/cds-services/x");
        assertThatThrownBy(() -> executor.execute(client, noHost, ResolvedText.plain(REQUEST), ExchangeOptions.json("application/json")))
                .isInstanceOf(EndpointNotConfiguredException.class).hasMessageContaining("no scheme or host");

        PreparedCall notAUrl = TestPreparedCalls.to(EpaOperation.CRD_ORDER_SIGN, "https://bad host/with space");
        assertThatThrownBy(() -> executor.execute(client, notAUrl, ResolvedText.plain(REQUEST), ExchangeOptions.json("application/json")))
                .isInstanceOf(EndpointNotConfiguredException.class);
    }
}
