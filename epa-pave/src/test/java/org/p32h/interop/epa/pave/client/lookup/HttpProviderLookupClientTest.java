package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The live provider lookup at the HTTP layer. Nothing here touches a network. The sending is
 * shared with the member lookup and is tested in full there. This pins what is the provider's own:
 * its operation, its settings, its words and its mapping.
 */
class HttpProviderLookupClientTest {

    private static final String NPI = Npi.withCheckDigit("123456789");
    private static final String BASE = "https://directory.example.invalid";
    private static final String URL = BASE + "/providers/" + NPI;
    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String PROVIDER = "{\"npi\":\"" + NPI + "\",\"firstName\":\"Pat\",\"lastName\":\"Placeholder-Provider\","
            + "\"specialty\":\"Sleep Medicine\",\"inNetwork\":true,\"acceptingNewPatients\":false}";
    private static final Map<String, String> ONYX_AUTH_ON = Map.of("auth.enabled", "true", "auth.token-url", "{{tokenUrl}}",
            "auth.client-id", "{{clientId}}", "auth.client-secret", "{{clientSecret}}");

    private final FakeGateway gateway = new FakeGateway().environment("Directory UAT", EnvironmentKind.UAT)
            .variable("directory", BASE).variable("tokenUrl", TOKEN_URL).variable("clientId", "placeholder-client")
            .variable("clientSecret", "placeholder-secret").url(EpaOperation.PROVIDER_LOOKUP, "{{directory}}/providers/{id}");
    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private HttpProviderLookupClient client(Map<String, String> lookup, Map<String, String> onyx) {
        return new HttpProviderLookupClient(new ExchangeExecutor(),
                OnyxAccessTestFactory.tokens(OnyxProperties.of(onyx), gateway, restClient, Clock.systemUTC()), gateway,
                LookupProperties.of(lookup), restClient);
    }

    private HttpProviderLookupClient client() {
        return client(Map.of(), Map.of());
    }

    @Test
    void theLookupIsAGetToTheUrlTheGatewayResolvedForTheProviderOperation() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET)).andExpect(header("Accept", "application/json"))
                .andExpect(headerDoesNotExist("Content-Type")).andExpect(headerDoesNotExist("Authorization")).andExpect(content().string(""))
                .andRespond(withSuccess(PROVIDER, MediaType.APPLICATION_JSON));

        ProviderLookupResult result = client().lookup(NPI);

        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.npi()).isEqualTo(NPI);
        assertThat(result.provider().lastName()).isEqualTo("Placeholder-Provider");
        assertThat(result.message()).isEqualTo("Provider found, reported as in network.");
        assertThat(result.additionalFields()).extracting(AdditionalField::path).containsExactly("acceptingNewPatients");
        assertThat(result.wire().request().sent()).isTrue();
        assertThat(result.wire().request().url()).isEqualTo(URL);
        assertThat(result.wire().request().urlTemplate()).isEqualTo("{{directory}}/providers/{id}");
        assertThat(result.wire().memberId()).isNull();
        CallIntent intent = gateway.lastIntent();
        assertThat(intent.operation()).isEqualTo(EpaOperation.PROVIDER_LOOKUP);
        assertThat(intent.urlValues()).isEqualTo(Map.of("id", NPI));
    }

    @Test
    void aQueryStringTemplateAndConfiguredHeadersWorkAsForAMember() {
        gateway.url(EpaOperation.PROVIDER_LOOKUP, "{{directory}}/providers?npi={id}&active=true")
                .headers(EpaOperation.PROVIDER_LOOKUP, List.of(new ResolvedHeader("X-Api-Key", "[secret directoryKey]", "placeholder-key-value")));
        server.expect(requestTo(BASE + "/providers?npi=" + NPI + "&active=true")).andExpect(header("X-Api-Key", "placeholder-key-value"))
                .andRespond(withSuccess("{\"providers\":[" + PROVIDER + "]}", MediaType.APPLICATION_JSON));
        ProviderLookupResult result = client().lookup(NPI);
        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.wire().request().headers()).contains(new WireHeader("X-Api-Key", "[redacted]"));
        assertThat(result.wire().request().headers().toString()).doesNotContain("placeholder-key-value");
    }

    @Test
    void eachWayACallCanEndGivesTheRightStatusInTheDirectorysWords() {
        HttpProviderLookupClient client = client();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        ProviderLookupResult notFound = client.lookup(NPI);
        assertThat(notFound.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(notFound.message()).isEqualTo("The provider directory does not know this NPI (it answered HTTP 404).");
        assertThat(notFound.error()).isNull();

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.lookup(NPI).status()).isEqualTo(LookupStatus.NOT_FOUND);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        ProviderLookupResult badRequest = client.lookup(NPI);
        assertThat(badRequest.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(badRequest.error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(badRequest.error().action()).contains("lookup.provider.url");

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        ProviderLookupResult serverError = client.lookup(NPI);
        assertThat(serverError.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(serverError.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(serverError.message()).isEqualTo("The provider directory failed with HTTP 503.");

        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThat(client.lookup(NPI).error().kind()).isEqualTo(ErrorKind.TIMEOUT);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("Connection refused")));
        ProviderLookupResult unreachable = client.lookup(NPI);
        assertThat(unreachable.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(unreachable.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"npi\":", MediaType.APPLICATION_JSON));
        assertThat(client.lookup(NPI).error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess());
        ProviderLookupResult emptyBody = client.lookup(NPI);
        assertThat(emptyBody.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(emptyBody.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
    }

    @Test
    void theProviderLookupHasItsOwnSwitchItsOwnUrlAndItsOwnUseOfTheOnyxToken() {
        // The member switch does not turn the provider lookup off.
        server.expect(requestTo(URL)).andExpect(headerDoesNotExist("Authorization")).andRespond(withSuccess(PROVIDER, MediaType.APPLICATION_JSON));
        assertThat(client(Map.of("member.enabled", "false", "member.use-onyx-auth", "true"), ONYX_AUTH_ON).lookup(NPI).status())
                .isEqualTo(LookupStatus.FOUND);
        server.verify();

        server.reset();
        ProviderLookupResult off = client(Map.of("provider.enabled", "false"), Map.of()).lookup(NPI);
        server.verify();
        assertThat(off.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(off.message()).isEqualTo("Provider lookup is switched off (lookup.provider.enabled is false): type the provider details by hand.");
        assertThat(off.error()).isNull();
        assertThat(off.npi()).isEqualTo(NPI);

        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"access_token\":\"placeholder-access-token\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer placeholder-access-token"))
                .andRespond(withSuccess(PROVIDER, MediaType.APPLICATION_JSON));
        ProviderLookupResult withToken = client(Map.of("provider.use-onyx-auth", "true"), ONYX_AUTH_ON).lookup(NPI);
        server.verify();
        assertThat(withToken.wire().request().headers()).contains(new WireHeader("Authorization", "Bearer [redacted]"));

        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        ProviderLookupResult noToken = client(Map.of("provider.use-onyx-auth", "true"), ONYX_AUTH_ON).lookup(NPI);
        server.verify();
        assertThat(noToken.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(noToken.message()).isEqualTo("Provider lookup was not sent, because no access token could be obtained for it.");
        assertThat(noToken.wire().request().sent()).isFalse();
    }

    @Test
    void aLookupThatIsNotConfiguredOrNotCompletelyIsDisabledAndSaysWhatIsMissing() {
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.PROVIDER_LOOKUP, "There is no url for Provider lookup."));
        ProviderLookupResult notConfigured = client().lookup(NPI);
        assertThat(notConfigured.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(notConfigured.message()).isEqualTo("Provider lookup is not configured, so there is no url to ask: set lookup.provider.url, "
                + "and until then type the provider details by hand.");
        assertThat(notConfigured.error()).isNull();

        gateway.failing(new UnresolvedVariablesException(EpaOperation.PROVIDER_LOOKUP, List.of("directory"), null, "{{directory}}/providers/{id}"));
        ProviderLookupResult unresolved = client().lookup(NPI);
        assertThat(unresolved.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(unresolved.message()).isEqualTo("Provider lookup is configured but not completely, because the placeholder {{directory}} "
                + "in its url has no value, and no environment is active: type the provider details by hand.");

        gateway.failing(null).headers(EpaOperation.PROVIDER_LOOKUP, List.of(ResolvedHeader.unresolved("Ocp-Apim-Subscription-Key", List.of("directoryKey"))));
        ProviderLookupResult header = client().lookup(NPI);
        assertThat(header.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(header.message()).isEqualTo("Provider lookup is configured but not completely, because the placeholder {{directoryKey}} "
                + "in the header Ocp-Apim-Subscription-Key has no value, and the active environment is \"Directory UAT\": type the provider "
                + "details by hand.");
        assertThat(header.wire()).isNull();
        // Nothing was sent for any of the three.
        server.verify();
    }
}
