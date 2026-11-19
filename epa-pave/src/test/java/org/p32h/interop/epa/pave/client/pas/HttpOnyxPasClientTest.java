package org.p32h.interop.epa.pave.client.pas;

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

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.pas.PasJson;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The live PAS client at the HTTP layer. Nothing here touches a network. */
class HttpOnyxPasClientTest {

    private static final String SUBMIT_URL = "https://onyx.example.invalid/fhir/Claim/$submit";
    private static final String INQUIRE_URL = "https://onyx.example.invalid/fhir/Claim/$inquire";
    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String FHIR_JSON = "application/fhir+json";
    private static final MediaType FHIR = MediaType.parseMediaType(FHIR_JSON);
    private static final String BODY = PasJson.request("claim-test-1", "95810");
    private static final String APPROVED = PasJson.bundle(PasJson.claimResponse(PasJson.items(PasJson.decided(1, "A1"))));

    private final FakeGateway gateway = new FakeGateway().environment("Onyx Sandbox", EnvironmentKind.SANDBOX)
            .variable("baseUrl", "https://onyx.example.invalid/fhir")
            .url(EpaOperation.PAS_SUBMIT, "{{baseUrl}}/Claim/$submit")
            .url(EpaOperation.PAS_INQUIRE, "{{baseUrl}}/Claim/$inquire");
    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private HttpOnyxPasClient client(Map<String, String> auth) {
        Map<String, String> properties = new HashMap<>();
        auth.forEach((key, value) -> properties.put("auth." + key, value));
        return new HttpOnyxPasClient(OnyxAccessTestFactory.create(new ExchangeExecutor(), OnyxProperties.of(properties), gateway, restClient,
                Clock.systemUTC()));
    }

    private static Map<String, String> authOn() {
        return Map.of("enabled", "true", "token-url", "{{tokenUrl}}", "client-id", "{{clientId}}", "client-secret", "{{clientSecret}}");
    }

    private void withCredentials() {
        gateway.variable("tokenUrl", TOKEN_URL).variable("clientId", "placeholder-client").variable("clientSecret", "placeholder-secret");
    }

    private PreparedCall submitCall() {
        return gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT));
    }

    private PreparedCall inquireCall() {
        return gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE));
    }

    @Test
    void aSubmissionIsPostedAsFhirJsonToTheResolvedSubmitUrl() {
        server.expect(requestTo(SUBMIT_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", FHIR_JSON)).andExpect(header("Content-Type", FHIR_JSON))
                .andExpect(headerDoesNotExist("Authorization")).andExpect(content().string(BODY))
                .andRespond(withSuccess(APPROVED, FHIR));

        VendorExchange exchange = client(Map.of()).submit(submitCall(), ResolvedText.plain(BODY), MockSelection.of(null, "S123456789", "1234567893"));

        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.httpStatus()).isEqualTo(200);
        assertThat(exchange.request().sent()).isTrue();
        assertThat(exchange.request().method()).isEqualTo("POST");
        assertThat(exchange.request().url()).isEqualTo(SUBMIT_URL);
        assertThat(exchange.request().urlTemplate()).isEqualTo("{{baseUrl}}/Claim/$submit");
        assertThat(exchange.request().body()).isEqualTo(BODY);
        assertThat(exchange.request().headers()).contains(new WireHeader("Accept", FHIR_JSON), new WireHeader("Content-Type", FHIR_JSON));
        assertThat(exchange.json().get("resourceType").asString()).isEqualTo("Bundle");
        // A real answer is never attributed to a mock scenario.
        assertThat(exchange.scenarioId()).isNull();
        assertThat(exchange.signals()).isEmpty();
    }

    @Test
    void anInquiryIsPostedAsFhirJsonToTheResolvedInquireUrl() {
        server.expect(requestTo(INQUIRE_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", FHIR_JSON)).andExpect(header("Content-Type", FHIR_JSON)).andExpect(content().string(BODY))
                .andRespond(withSuccess(APPROVED, FHIR));
        VendorExchange exchange = client(Map.of()).inquire(inquireCall(), ResolvedText.plain(BODY), null);
        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.request().url()).isEqualTo(INQUIRE_URL);
    }

    @Test
    void aScenarioChoiceIsIgnoredOnLive() {
        // Whatever the selection says, the request is the same and the answer is the service's.
        server.expect(requestTo(SUBMIT_URL)).andExpect(content().string(BODY)).andRespond(withSuccess(APPROVED, FHIR));
        VendorExchange exchange = client(Map.of()).submit(submitCall(), ResolvedText.plain(BODY), MockSelection.of("pas-03-denied", "P32H-PAS-DENIED", null));
        server.verify();
        assertThat(exchange.scenarioId()).isNull();
        assertThat(exchange.responseBody()).isEqualTo(APPROVED);
    }

    @Test
    void theAccessTokenIsFetchedAndSentAsABearerCredentialThatIsNeverShown() {
        withCredentials();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"access_token\":\"placeholder-access-token\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(SUBMIT_URL)).andExpect(header("Authorization", "Bearer placeholder-access-token"))
                .andExpect(header("Content-Type", FHIR_JSON)).andRespond(withSuccess(APPROVED, FHIR));

        VendorExchange exchange = client(authOn()).submit(submitCall(), ResolvedText.plain(BODY), null);

        server.verify();
        assertThat(exchange.request().headers()).contains(new WireHeader("Authorization", "Bearer [redacted]"));
        assertThat(exchange.request().headers().toString()).doesNotContain("placeholder-access-token");
    }

    @Test
    void whenNoTokenCanBeObtainedNothingIsSubmittedAndTheExchangeSaysWhy() {
        withCredentials();
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        VendorExchange exchange = client(authOn()).submit(submitCall(), ResolvedText.plain(BODY), null);

        // Only the token endpoint was called. The submit url was never requested.
        server.verify();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(exchange.request().sent()).isFalse();
        assertThat(exchange.failureDetail()).isEqualTo("No access token: the token endpoint answered HTTP 401. The request was not sent.");
        assertThat(exchange.httpStatus()).isNull();
    }

    @Test
    void theVendorSignalsAreReadFromTheResponseOnLiveAsOnMock() {
        String notFound = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\",\"expression\":[\"Claim.patient\"]}]}";
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND).body(notFound).contentType(FHIR));
        VendorExchange byOutcome = client(Map.of()).submit(submitCall(), ResolvedText.plain(BODY), null);
        assertThat(byOutcome.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(byOutcome.httpStatus()).isEqualTo(404);
        assertThat(byOutcome.signals()).containsExactly(VendorSignal.MEMBER_NOT_FOUND);

        server.reset();
        String rejectReason = PasJson.bundle(PasJson.claimResponse("\"outcome\":\"error\"", "\"error\":[{\"code\":{\"coding\":[{\"system\":"
                + "\"https://codesystem.x12.org/005010/901\",\"code\":\"51\"}]}}]"));
        server.expect(requestTo(INQUIRE_URL)).andRespond(withSuccess(rejectReason, FHIR));
        VendorExchange byErrorCode = client(Map.of()).inquire(inquireCall(), ResolvedText.plain(BODY), null);
        assertThat(byErrorCode.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(byErrorCode.signals()).containsExactly(VendorSignal.PROVIDER_NOT_FOUND);
    }

    @Test
    void everyKindOfFailureComesBackAsAnExchangeAndNeverAsAnException() {
        HttpOnyxPasClient client = client(Map.of());
        String invalid = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\"}]}";
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body(invalid).contentType(FHIR));
        VendorExchange rejected = client.submit(submitCall(), ResolvedText.plain(BODY), null);
        assertThat(rejected.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(rejected.httpStatus()).isEqualTo(400);
        // The issues are kept for the screen: a rejection is not reduced to its status.
        assertThat(rejected.json().get("issue")).hasSize(1);
        assertThat(rejected.signals()).isEmpty();

        server.reset();
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(invalid).contentType(FHIR));
        assertThat(client.submit(submitCall(), ResolvedText.plain(BODY), null).failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);

        server.reset();
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>Bad Gateway</html>"));
        VendorExchange badGateway = client.submit(submitCall(), ResolvedText.plain(BODY), null);
        assertThat(badGateway.failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);
        assertThat(badGateway.httpStatus()).isEqualTo(502);
        assertThat(badGateway.responseBody()).isEqualTo("<html>Bad Gateway</html>");
        assertThat(badGateway.json()).isNull();

        server.reset();
        server.expect(requestTo(SUBMIT_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        VendorExchange timeout = client.submit(submitCall(), ResolvedText.plain(BODY), null);
        assertThat(timeout.failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
        assertThat(timeout.httpStatus()).isNull();
        // The submission did leave this machine, which is what a tester must know before sending it again.
        assertThat(timeout.request().sent()).isTrue();

        server.reset();
        server.expect(requestTo(INQUIRE_URL)).andRespond(withException(new ConnectException("Connection refused")));
        assertThat(client.inquire(inquireCall(), ResolvedText.plain(BODY), null).failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);

        server.reset();
        server.expect(requestTo(SUBMIT_URL)).andRespond(withSuccess("{\"resourceType\": \"Bundle\", \"entry\": [", FHIR));
        VendorExchange malformed = client.submit(submitCall(), ResolvedText.plain(BODY), null);
        assertThat(malformed.failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
        assertThat(malformed.responseBody()).isEqualTo("{\"resourceType\": \"Bundle\", \"entry\": [");
        assertThat(malformed.parseError()).isNotBlank();

        // A FHIR operation answers with a resource. A success with nothing in it is not an answer.
        server.reset();
        server.expect(requestTo(SUBMIT_URL)).andRespond(withSuccess());
        VendorExchange empty = client.submit(submitCall(), ResolvedText.plain(BODY), null);
        assertThat(empty.failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
        assertThat(empty.responseBody()).isNull();
        server.reset();
        server.expect(requestTo(INQUIRE_URL)).andRespond(withSuccess());
        assertThat(client.inquire(inquireCall(), ResolvedText.plain(BODY), null).failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
    }

    @Test
    void aBodyThatStillHoldsAPlaceholderIsRefusedBeforeAnythingIsSent() {
        ResolvedText unresolved = gateway.resolve("{\"resourceType\":\"Bundle\",\"id\":\"{{bundleIdentifier}}\"}", Map.of());
        assertThatThrownBy(() -> client(Map.of()).submit(submitCall(), unresolved, null)).isInstanceOf(UnresolvedVariablesException.class);
        server.verify();
    }

    @Test
    void aSubmissionPreparedBeforeTheEnvironmentChangedIsRefusedBeforeAnyTokenIsFetched() {
        withCredentials();
        HttpOnyxPasClient client = client(authOn());
        PreparedCall stale = submitCall();
        gateway.changeConfiguration();
        assertThatThrownBy(() -> client.submit(stale, ResolvedText.plain(BODY), null)).isInstanceOf(ConfigurationChangedException.class);
        // Neither the token endpoint nor the service was called: a write judged against one
        // environment must never leave for another.
        server.verify();
    }
}
