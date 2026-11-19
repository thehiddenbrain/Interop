package org.p32h.interop.epa.pave.client.dtr;

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
import java.util.function.BiFunction;
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
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The live DTR client at the HTTP layer. Nothing here touches a network. */
class HttpOnyxDtrClientTest {

    private static final String PACKAGE_URL = "https://onyx.example.invalid/fhir/Questionnaire/$questionnaire-package";
    private static final String NEXT_URL = "https://onyx.example.invalid/fhir/Questionnaire/$next-question";
    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String FHIR_JSON = "application/fhir+json";
    private static final MediaType FHIR = MediaType.valueOf(FHIR_JSON);
    private static final String PACKAGE_BODY = "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"coverage\"}]}";
    private static final String RESPONSE_BODY = "{\"resourceType\":\"QuestionnaireResponse\",\"status\":\"in-progress\"}";
    private static final String PACKAGE_ANSWER = "{\"resourceType\":\"Parameters\",\"parameter\":[]}";

    private final FakeGateway gateway = new FakeGateway().environment("Onyx Sandbox", EnvironmentKind.SANDBOX)
            .variable("baseUrl", "https://onyx.example.invalid/fhir")
            .url(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "{{baseUrl}}/Questionnaire/$questionnaire-package")
            .url(EpaOperation.DTR_NEXT_QUESTION, "{{baseUrl}}/Questionnaire/$next-question");
    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private HttpOnyxDtrClient client(Map<String, String> auth) {
        Map<String, String> properties = new HashMap<>();
        auth.forEach((key, value) -> properties.put("auth." + key, value));
        return new HttpOnyxDtrClient(OnyxAccessTestFactory.create(new ExchangeExecutor(), OnyxProperties.of(properties), gateway, restClient,
                Clock.systemUTC()));
    }

    private static Map<String, String> authOn() {
        return Map.of("enabled", "true", "token-url", "{{tokenUrl}}", "client-id", "{{clientId}}", "client-secret", "{{clientSecret}}");
    }

    private void withCredentials() {
        gateway.variable("tokenUrl", TOKEN_URL).variable("clientId", "placeholder-client").variable("clientSecret", "placeholder-secret");
    }

    private PreparedCall packageCall() {
        return gateway.prepare(CallIntent.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE));
    }

    private PreparedCall nextCall() {
        return gateway.prepare(CallIntent.of(EpaOperation.DTR_NEXT_QUESTION));
    }

    @Test
    void thePackageRequestIsPostedAsFhirJsonToTheResolvedOperationUrl() {
        server.expect(requestTo(PACKAGE_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", FHIR_JSON)).andExpect(header("Content-Type", FHIR_JSON))
                .andExpect(headerDoesNotExist("Authorization")).andExpect(content().string(PACKAGE_BODY))
                .andRespond(withSuccess(PACKAGE_ANSWER, FHIR));

        VendorExchange exchange = client(Map.of()).questionnairePackage(packageCall(), ResolvedText.plain(PACKAGE_BODY),
                MockSelection.of(null, "S123456789", "1234567893"));

        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.httpStatus()).isEqualTo(200);
        assertThat(exchange.request().sent()).isTrue();
        assertThat(exchange.request().method()).isEqualTo("POST");
        assertThat(exchange.request().url()).isEqualTo(PACKAGE_URL);
        assertThat(exchange.request().urlTemplate()).isEqualTo("{{baseUrl}}/Questionnaire/$questionnaire-package");
        assertThat(exchange.request().body()).isEqualTo(PACKAGE_BODY);
        assertThat(exchange.request().headers()).contains(new WireHeader("Accept", FHIR_JSON), new WireHeader("Content-Type", FHIR_JSON));
        // The answer is parsed although its media type is FHIR JSON and not plain JSON.
        assertThat(exchange.json().get("resourceType").asString()).isEqualTo("Parameters");
        // A real answer is never attributed to a mock scenario.
        assertThat(exchange.scenarioId()).isNull();
        assertThat(exchange.signals()).isEmpty();
    }

    @Test
    void theNextQuestionRequestIsPostedAsFhirJsonToItsOwnOperationUrl() {
        server.expect(requestTo(NEXT_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", FHIR_JSON)).andExpect(header("Content-Type", FHIR_JSON))
                .andExpect(headerDoesNotExist("Authorization")).andExpect(content().string(RESPONSE_BODY))
                .andRespond(withSuccess(RESPONSE_BODY, FHIR));

        VendorExchange exchange = client(Map.of()).nextQuestion(nextCall(), ResolvedText.plain(RESPONSE_BODY), null);

        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.request().sent()).isTrue();
        assertThat(exchange.request().url()).isEqualTo(NEXT_URL);
        assertThat(exchange.request().urlTemplate()).isEqualTo("{{baseUrl}}/Questionnaire/$next-question");
        assertThat(exchange.json().get("status").asString()).isEqualTo("in-progress");
        assertThat(exchange.scenarioId()).isNull();
    }

    @Test
    void aScenarioChoiceIsIgnoredOnLive() {
        // Whatever the selection says, the request is the same and the answer is the service's.
        server.expect(requestTo(PACKAGE_URL)).andExpect(content().string(PACKAGE_BODY)).andRespond(withSuccess(PACKAGE_ANSWER, FHIR));
        server.expect(requestTo(NEXT_URL)).andExpect(content().string(RESPONSE_BODY)).andRespond(withSuccess(RESPONSE_BODY, FHIR));
        HttpOnyxDtrClient client = client(Map.of());
        VendorExchange fetched = client.questionnairePackage(packageCall(), ResolvedText.plain(PACKAGE_BODY),
                MockSelection.of("dtr-07-no-questionnaire", "P32H-DTR-EMPTY", null));
        VendorExchange stepped = client.nextQuestion(nextCall(), ResolvedText.plain(RESPONSE_BODY),
                MockSelection.of("dtr-02-adaptive-branching", "P32H-DTR-ADAPT", null));
        server.verify();
        assertThat(fetched.scenarioId()).isNull();
        assertThat(fetched.responseBody()).isEqualTo(PACKAGE_ANSWER);
        assertThat(stepped.scenarioId()).isNull();
        assertThat(stepped.responseBody()).isEqualTo(RESPONSE_BODY);
    }

    @Test
    void theAccessTokenIsFetchedAndSentAsABearerCredentialThatIsNeverShown() {
        withCredentials();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"access_token\":\"placeholder-access-token\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(PACKAGE_URL)).andExpect(header("Authorization", "Bearer placeholder-access-token"))
                .andRespond(withSuccess(PACKAGE_ANSWER, FHIR));
        // The token is cached, so the second operation sends it without asking for it again.
        server.expect(requestTo(NEXT_URL)).andExpect(header("Authorization", "Bearer placeholder-access-token"))
                .andRespond(withSuccess(RESPONSE_BODY, FHIR));

        HttpOnyxDtrClient client = client(authOn());
        VendorExchange fetched = client.questionnairePackage(packageCall(), ResolvedText.plain(PACKAGE_BODY), null);
        VendorExchange stepped = client.nextQuestion(nextCall(), ResolvedText.plain(RESPONSE_BODY), null);

        server.verify();
        for (VendorExchange exchange : new VendorExchange[] {fetched, stepped}) {
            assertThat(exchange.request().headers()).contains(new WireHeader("Authorization", "Bearer [redacted]"));
            assertThat(exchange.request().headers().toString()).doesNotContain("placeholder-access-token");
        }
    }

    @Test
    void whenNoTokenCanBeObtainedNothingIsSentAndTheExchangeSaysWhy() {
        withCredentials();
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        VendorExchange exchange = client(authOn()).nextQuestion(nextCall(), ResolvedText.plain(RESPONSE_BODY), null);

        // Only the token endpoint was called. The DTR url was never requested.
        server.verify();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(exchange.request().sent()).isFalse();
        assertThat(exchange.failureDetail()).isEqualTo("No access token: the token endpoint answered HTTP 401. The request was not sent.");
        assertThat(exchange.httpStatus()).isNull();
    }

    @Test
    void theVendorSignalsAreReadFromTheResponseOnLiveAsOnMock() {
        String memberNotFound = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\","
                + "\"expression\":[\"Coverage.beneficiary\"]}]}";
        server.expect(requestTo(PACKAGE_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND).body(memberNotFound).contentType(FHIR));
        VendorExchange fetched = client(Map.of()).questionnairePackage(packageCall(), ResolvedText.plain(PACKAGE_BODY), null);
        assertThat(fetched.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(fetched.httpStatus()).isEqualTo(404);
        assertThat(fetched.signals()).containsExactly(VendorSignal.MEMBER_NOT_FOUND);

        server.reset();
        String providerNotFound = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\","
                + "\"expression\":[\"PractitionerRole.practitioner\"]}]}";
        server.expect(requestTo(NEXT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND).body(providerNotFound).contentType(FHIR));
        assertThat(client(Map.of()).nextQuestion(nextCall(), ResolvedText.plain(RESPONSE_BODY), null).signals())
                .containsExactly(VendorSignal.PROVIDER_NOT_FOUND);

        // A 404 that names neither is a rejected request and nothing more is read into it.
        server.reset();
        server.expect(requestTo(PACKAGE_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"resourceType\":\"OperationOutcome\","
                + "\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\"}]}").contentType(FHIR));
        assertThat(client(Map.of()).questionnairePackage(packageCall(), ResolvedText.plain(PACKAGE_BODY), null).signals()).isEmpty();
    }

    @Test
    void everyKindOfFailureOfAPackageFetchComesBackAsAnExchangeAndNeverAsAnException() {
        HttpOnyxDtrClient client = client(Map.of());
        assertEveryFailureIsClassified(PACKAGE_URL, (call, body) -> client.questionnairePackage(packageCall(), body, null), PACKAGE_BODY,
                "{\"resourceType\": \"Parameters\", \"parameter\": [");
    }

    @Test
    void everyKindOfFailureOfANextQuestionCallComesBackAsAnExchangeAndNeverAsAnException() {
        HttpOnyxDtrClient client = client(Map.of());
        assertEveryFailureIsClassified(NEXT_URL, (call, body) -> client.nextQuestion(nextCall(), body, null), RESPONSE_BODY,
                "{\"resourceType\": \"QuestionnaireResponse\", \"item\": [");
    }

    private void assertEveryFailureIsClassified(String url, BiFunction<PreparedCall, ResolvedText, VendorExchange> send, String requestBody,
                                                String truncated) {
        ResolvedText body = ResolvedText.plain(requestBody);
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"resourceType\":\"OperationOutcome\",\"issue\":[]}")
                .contentType(FHIR));
        VendorExchange badRequest = send.apply(null, body);
        assertThat(badRequest.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(badRequest.httpStatus()).isEqualTo(400);
        assertThat(badRequest.json().get("resourceType").asString()).isEqualTo("OperationOutcome");
        server.reset();
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>Bad Gateway</html>"));
        VendorExchange badGateway = send.apply(null, body);
        assertThat(badGateway.failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);
        assertThat(badGateway.httpStatus()).isEqualTo(502);
        assertThat(badGateway.responseBody()).isEqualTo("<html>Bad Gateway</html>");
        server.reset();
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(send.apply(null, body).failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);
        server.reset();
        server.expect(requestTo(url)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        VendorExchange timeout = send.apply(null, body);
        assertThat(timeout.failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
        assertThat(timeout.httpStatus()).isNull();
        // The request that got no answer is still described, so the tester sees what was sent.
        assertThat(timeout.request().body()).isEqualTo(requestBody);
        assertThat(timeout.request().sent()).isTrue();
        server.reset();
        server.expect(requestTo(url)).andRespond(withException(new ConnectException("Connection refused")));
        assertThat(send.apply(null, body).failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        server.reset();
        server.expect(requestTo(url)).andRespond(withSuccess(truncated, FHIR));
        VendorExchange malformed = send.apply(null, body);
        assertThat(malformed.failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
        assertThat(malformed.httpStatus()).isEqualTo(200);
        assertThat(malformed.responseBody()).isEqualTo(truncated);
        assertThat(malformed.json()).isNull();
        server.reset();
        // A success with nothing in it is not an answer for either operation.
        server.expect(requestTo(url)).andRespond(withSuccess());
        VendorExchange empty = send.apply(null, body);
        assertThat(empty.failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
        assertThat(empty.responseBody()).isNull();
        server.reset();
    }

    @Test
    void aCallPreparedBeforeTheEnvironmentChangedIsRefusedBeforeAnyTokenIsFetched() {
        withCredentials();
        HttpOnyxDtrClient client = client(authOn());
        PreparedCall stalePackage = packageCall();
        PreparedCall staleStep = nextCall();
        gateway.changeConfiguration();
        assertThatThrownBy(() -> client.questionnairePackage(stalePackage, ResolvedText.plain(PACKAGE_BODY), null))
                .isInstanceOf(ConfigurationChangedException.class);
        assertThatThrownBy(() -> client.nextQuestion(staleStep, ResolvedText.plain(RESPONSE_BODY), null))
                .isInstanceOf(ConfigurationChangedException.class);
        // Neither the token endpoint nor the service was called: one environment's token must
        // never be fetched for a request prepared against another.
        server.verify();
    }
}
