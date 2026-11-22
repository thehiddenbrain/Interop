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

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The live member lookup at the HTTP layer. Nothing here touches a network: the client is bound to
 * a mock server, and the gateway is a stand-in that hands back calls made with the test factory.
 */
class HttpMemberLookupClientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final String TEMPLATE = "{{memberApi}}/api/members/{id}";
    private static final String BASE = "https://members.example.invalid";
    private static final String URL = BASE + "/api/members/S123456789";
    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String MEMBER = "{\"memberId\":\"S123456789\",\"firstName\":\"Avery\",\"lastName\":\"Placeholder\","
            + "\"dateOfBirth\":\"1970-01-31\",\"lineOfBusiness\":\"Medicare\",\"coverageStatus\":\"Active\",\"extra\":\"kept\"}";
    private static final MemberIdForms FORMS = MemberIds.forms("S123456789");
    private static final Map<String, String> ONYX_AUTH_ON = Map.of("auth.enabled", "true", "auth.token-url", "{{tokenUrl}}",
            "auth.client-id", "{{clientId}}", "auth.client-secret", "{{clientSecret}}");

    private final FakeGateway gateway = new FakeGateway().environment("Member UAT", EnvironmentKind.UAT)
            .variable("memberApi", BASE).variable("tokenUrl", TOKEN_URL).variable("clientId", "placeholder-client")
            .variable("clientSecret", "placeholder-secret").url(EpaOperation.MEMBER_LOOKUP, TEMPLATE);
    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private HttpMemberLookupClient client(Map<String, String> lookup, Map<String, String> onyx) {
        return client(lookup, onyx, gateway, new ExchangeExecutor());
    }

    private HttpMemberLookupClient client(Map<String, String> lookup, Map<String, String> onyx, OperationGateway through,
                                          ExchangeExecutor executor) {
        return new HttpMemberLookupClient(executor, OnyxAccessTestFactory.tokens(OnyxProperties.of(onyx), through, restClient, CLOCK),
                through, LookupProperties.of(lookup), restClient, CLOCK);
    }

    private HttpMemberLookupClient client() {
        return client(Map.of(), Map.of());
    }

    // ---------------------------------------------------------------------------------------
    // The outbound request

    @Test
    void theLookupIsAGetToTheUrlTheGatewayResolvedWithTheNoSpaceIdInThePath() {
        server.expect(requestTo(BASE + "/api/members/S1234567801")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", "application/json")).andExpect(headerDoesNotExist("Content-Type"))
                .andExpect(headerDoesNotExist("Authorization")).andExpect(content().string(""))
                .andRespond(withSuccess(MEMBER.replace("S123456789", "S12345678   01"), MediaType.APPLICATION_JSON));

        MemberIdForms spaced = MemberIds.forms("S12345678   01");
        MemberLookupResult result = client().lookup(spaced);

        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.member().firstName()).isEqualTo("Avery");
        assertThat(result.member().memberId()).isEqualTo("S1234567801");
        assertThat(result.memberId()).isEqualTo(spaced);
        // The wire view shows what was sent and where, beside the template, with both forms of the ID.
        assertThat(result.wire().request().sent()).isTrue();
        assertThat(result.wire().request().method()).isEqualTo("GET");
        assertThat(result.wire().request().url()).isEqualTo(BASE + "/api/members/S1234567801");
        assertThat(result.wire().request().urlTemplate()).isEqualTo(TEMPLATE);
        assertThat(result.wire().request().body()).isNull();
        assertThat(result.wire().response().status()).isEqualTo(200);
        assertThat(result.wire().memberId()).isEqualTo(spaced);
        // The target was asked of the gateway and of nothing else, with the ID as a url value for it to encode.
        CallIntent intent = gateway.lastIntent();
        assertThat(gateway.intents()).hasSize(1);
        assertThat(intent.operation()).isEqualTo(EpaOperation.MEMBER_LOOKUP);
        assertThat(intent.urlValues()).isEqualTo(Map.of("id", "S1234567801"));
        assertThat(intent.runValues()).isEmpty();
        assertThat(intent.request()).isNull();
        assertThat(intent.unlock()).isNull();
        // What the mapping did not read is on screen.
        assertThat(result.additionalFields()).containsExactly(new AdditionalField("extra", Presence.VALUE, "\"kept\""));
    }

    @Test
    void anIdInThePathIsPercentEncodedSoItCannotChangeTheShapeOfTheUrl() {
        // A slash, a percent sign and a hash in an ID would each mean something else in a url.
        server.expect(requestTo(BASE + "/api/members/P32H%2FTEST%251%23x")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        MemberLookupResult result = client().lookup(MemberIds.forms("P32H/TEST %1#x"));
        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(result.wire().request().url()).isEqualTo(BASE + "/api/members/P32H%2FTEST%251%23x");
        assertThat(result.memberId().sent()).isEqualTo("P32H/TEST%1#x");
    }

    @Test
    void aQueryStringTemplateWorksTooAndAnIdCannotAddAParameterToIt() {
        gateway.url(EpaOperation.MEMBER_LOOKUP, "{{memberApi}}/api/lookup?memberId={id}&include=coverage");
        server.expect(requestTo(BASE + "/api/lookup?memberId=P32H-TEST%26all%3Dtrue&include=coverage")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(MEMBER, MediaType.APPLICATION_JSON));
        MemberLookupResult result = client().lookup(MemberIds.forms("P32H-TEST&all=true"));
        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.wire().request().urlTemplate()).isEqualTo("{{memberApi}}/api/lookup?memberId={id}&include=coverage");
        // The record came back under another ID than the odd one that was asked for, and the result says so.
        assertThat(result.attention()).hasSize(1).first().asString().contains("different member ID");
    }

    @Test
    void theHeadersConfiguredForTheLookupAreSentAndACredentialAmongThemIsNeverShown() {
        gateway.headers(EpaOperation.MEMBER_LOOKUP, List.of(new ResolvedHeader("X-Api-Key", "[secret memberApiKey]", "placeholder-key-value"),
                new ResolvedHeader("X-Client", "pave", "pave"), new ResolvedHeader("Accept", "application/fhir+json", "application/fhir+json")));
        server.expect(requestTo(URL)).andExpect(header("X-Api-Key", "placeholder-key-value")).andExpect(header("X-Client", "pave"))
                // A configured header of the same name replaces the default.
                .andExpect(header("Accept", "application/fhir+json")).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(MEMBER, MediaType.APPLICATION_JSON));

        MemberLookupResult result = client().lookup(FORMS);

        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.wire().request().headers()).containsExactlyInAnyOrder(new WireHeader("Accept", "application/fhir+json"),
                new WireHeader("X-Api-Key", "[redacted]"), new WireHeader("X-Client", "pave"));
        assertThat(result.wire().request().headers().toString()).doesNotContain("placeholder-key-value");
    }

    // ---------------------------------------------------------------------------------------
    // The Onyx access token

    @Test
    void theOnyxTokenIsSentOnlyWhenTheLookupIsConfiguredToUseIt() {
        // Onyx authentication is on, and this lookup does not use it: no token is asked for and none is sent.
        server.expect(requestTo(URL)).andExpect(headerDoesNotExist("Authorization")).andRespond(withSuccess(MEMBER, MediaType.APPLICATION_JSON));
        assertThat(client(Map.of(), ONYX_AUTH_ON).lookup(FORMS).status()).isEqualTo(LookupStatus.FOUND);
        server.verify();

        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"access_token\":\"placeholder-access-token\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer placeholder-access-token"))
                .andRespond(withSuccess(MEMBER, MediaType.APPLICATION_JSON));
        MemberLookupResult result = client(Map.of("member.use-onyx-auth", "true"), ONYX_AUTH_ON).lookup(FORMS);
        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.wire().request().headers()).contains(new WireHeader("Authorization", "Bearer [redacted]"));
        assertThat(result.wire().request().headers().toString()).doesNotContain("placeholder-access-token");

        // The switch is per lookup: the provider's does not turn the member's on.
        server.reset();
        server.expect(requestTo(URL)).andExpect(headerDoesNotExist("Authorization")).andRespond(withSuccess(MEMBER, MediaType.APPLICATION_JSON));
        client(Map.of("provider.use-onyx-auth", "true"), ONYX_AUTH_ON).lookup(FORMS);
        server.verify();
    }

    @Test
    void whenNoTokenCanBeObtainedNothingIsSentAndTheLookupIsAFaultThatSaysWhy() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        MemberLookupResult result = client(Map.of("member.use-onyx-auth", "true"), ONYX_AUTH_ON).lookup(FORMS);

        // Only the token endpoint was called. The member API was never asked.
        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(result.message()).isEqualTo("Member lookup was not sent, because no access token could be obtained for it.");
        assertThat(result.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);
        assertThat(result.error().title()).isEqualTo("Member lookup was not sent, because no access token could be obtained for it. "
                + "No access token: the token endpoint answered HTTP 401. The request was not sent.");
        assertThat(result.error().action()).contains("onyx.auth").contains("lookup.member.use-onyx-auth").contains("type the member details by hand");
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.wire().request().url()).isEqualTo(URL);
        assertThat(result.wire().response()).isNull();
        assertThat(result.wire().memberId()).isEqualTo(FORMS);
        assertThat(result.member()).isNull();
    }

    @Test
    void aLookupAskedToUseTheOnyxTokenWhileOnyxAuthenticationIsOffGoesOutWithoutOneAndA401SaysSo() {
        server.expect(requestTo(URL)).andExpect(headerDoesNotExist("Authorization")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        MemberLookupResult result = client(Map.of("member.use-onyx-auth", "true"), Map.of()).lookup(FORMS);
        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(result.error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(result.error().httpStatus()).isEqualTo(401);
        assertThat(result.error().action()).contains("went out without an Authorization header").contains("onyx.auth");
    }

    @Test
    void aLookupConfiguredWithItsOwnAuthorizationIsSentWithThatAndNoTokenIsFetched() {
        gateway.headers(EpaOperation.MEMBER_LOOKUP, List.of(new ResolvedHeader("Authorization", "Basic [redacted]", "Basic cGxhY2Vob2xkZXI=")));
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Basic cGxhY2Vob2xkZXI="))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        MemberLookupResult result = client(Map.of("member.use-onyx-auth", "true"), ONYX_AUTH_ON).lookup(FORMS);
        // The token endpoint was not called.
        server.verify();
        assertThat(result.wire().request().headers()).contains(new WireHeader("Authorization", "Basic [redacted]"));
        assertThat(result.error().action()).contains("went out with an Authorization header");
    }

    // ---------------------------------------------------------------------------------------
    // How a call that was sent can end

    @Test
    void anHttp404IsNotFoundAndASuccessWithNoRecordIsNotFoundToo() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"error\":\"not_found\"}").contentType(MediaType.APPLICATION_JSON));
        MemberLookupResult byStatus = client().lookup(FORMS);
        assertThat(byStatus.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(byStatus.error()).isNull();
        assertThat(byStatus.wire().response().status()).isEqualTo(404);
        assertThat(byStatus.wire().request().sent()).isTrue();

        for (String empty : List.of("{}", "[]", "{\"data\":null}", "{\"data\":[]}", "{\"member\":{}}")) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withSuccess(empty, MediaType.APPLICATION_JSON));
            MemberLookupResult byEnvelope = client().lookup(FORMS);
            assertThat(byEnvelope.status()).as(empty).isEqualTo(LookupStatus.NOT_FOUND);
            assertThat(byEnvelope.error()).as(empty).isNull();
        }
    }

    @Test
    void everyOtherEndingIsAFaultThatSaysWhichAndShowsTheRequestAndTheResponse() {
        HttpMemberLookupClient client = client();

        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"bad id\"}").contentType(MediaType.APPLICATION_JSON));
        MemberLookupResult badRequest = client.lookup(FORMS);
        assertThat(badRequest.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(badRequest.error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(badRequest.error().httpStatus()).isEqualTo(400);
        assertThat(badRequest.wire().response().body()).isEqualTo("{\"error\":\"bad id\"}");
        assertThat(badRequest.additionalFields()).extracting(AdditionalField::path).containsExactly("error");

        for (HttpStatus status : List.of(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE)) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withStatus(status).body("<html>upstream</html>"));
            MemberLookupResult serverError = client.lookup(FORMS);
            assertThat(serverError.status()).as(status.toString()).isEqualTo(LookupStatus.FAULT);
            assertThat(serverError.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
            assertThat(serverError.error().httpStatus()).isEqualTo(status.value());
            assertThat(serverError.additionalFields()).containsExactly(AdditionalField.unparsedBody("<html>upstream</html>"));
        }

        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out for S123456789")));
        MemberLookupResult timeout = client.lookup(FORMS);
        assertThat(timeout.status()).isEqualTo(LookupStatus.NOT_ANSWERING);
        assertThat(timeout.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(timeout.message()).isEqualTo("The member API did not answer inside the read timeout.");
        assertThat(timeout.wire().request().sent()).isTrue();
        assertThat(timeout.wire().response().status()).isNull();

        for (IOException unreachable : List.of(new ConnectException("Connection refused"), new UnknownHostException("members.example.invalid"))) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withException(unreachable));
            MemberLookupResult result = client.lookup(FORMS);
            assertThat(result.status()).isEqualTo(LookupStatus.NOT_ANSWERING);
            assertThat(result.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);
            assertThat(result.message()).isEqualTo("The member API could not be reached.");
            // The reason is one of this tool's own sentences, never the exception's message.
            assertThat(result.error().title()).doesNotContain("members.example.invalid").doesNotContain("S123456789");
        }

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"memberId\": \"S1234", MediaType.APPLICATION_JSON));
        MemberLookupResult malformed = client.lookup(FORMS);
        assertThat(malformed.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(malformed.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(malformed.message()).isEqualTo("The member API answered HTTP 200 with a body that is not JSON.");
        assertThat(malformed.additionalFields()).containsExactly(AdditionalField.unparsedBody("{\"memberId\": \"S1234"));

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess());
        MemberLookupResult emptyBody = client.lookup(FORMS);
        assertThat(emptyBody.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(emptyBody.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(emptyBody.message()).isEqualTo("The member API answered HTTP 200 with an empty body, so there is nothing to read a member from.");

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess("[" + MEMBER + "," + MEMBER + "]", MediaType.APPLICATION_JSON));
        MemberLookupResult several = client.lookup(FORMS);
        assertThat(several.status()).isEqualTo(LookupStatus.SEVERAL);
        assertThat(several.message()).startsWith("The member API returned 2 records for one member ID");
    }

    // ---------------------------------------------------------------------------------------
    // Disabled: nothing is sent, and it is not an error

    @Test
    void aLookupThatIsSwitchedOffIsDisabledWithoutAskingTheGatewayAnything() {
        MemberLookupResult result = client(Map.of("member.enabled", "false"), Map.of()).lookup(FORMS);
        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(result.message()).isEqualTo("Member lookup is switched off (lookup.member.enabled is false): type the member details by hand.");
        assertThat(result.error()).isNull();
        assertThat(result.wire()).isNull();
        assertThat(result.member()).isNull();
        assertThat(result.memberId()).isEqualTo(FORMS);
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void aLookupWithNoUrlIsDisabledAndNotAnError() {
        // As the live gateway refuses it.
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.MEMBER_LOOKUP, "There is no url for Member lookup."));
        MemberLookupResult refused = client().lookup(FORMS);
        // And as a gateway that hands back a call with no target does.
        MemberLookupResult empty = client(Map.of(), Map.of(), FakeGateway.unconfigured(), new ExchangeExecutor()).lookup(FORMS);
        server.verify();
        for (MemberLookupResult result : List.of(refused, empty)) {
            assertThat(result.status()).isEqualTo(LookupStatus.DISABLED);
            assertThat(result.message()).isEqualTo("Member lookup is not configured, so there is no url to ask: set lookup.member.url, "
                    + "and until then type the member details by hand.");
            assertThat(result.error()).isNull();
            assertThat(result.wire()).isNull();
            assertThat(result.memberId()).isEqualTo(FORMS);
        }
    }

    @Test
    void aUrlThatStillHoldsAPlaceholderIsDisabledAndNamesTheMissingVariable() {
        gateway.failing(new UnresolvedVariablesException(EpaOperation.MEMBER_LOOKUP, List.of("memberApi"), "Member UAT", TEMPLATE,
                Map.of("memberApi", "it is blank in the environment.")));
        MemberLookupResult refused = client().lookup(FORMS);
        assertThat(refused.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(refused.message()).isEqualTo("Member lookup is configured but not completely, because the placeholder {{memberApi}} "
                + "(it is blank in the environment) in its url has no value, and the active environment is \"Member UAT\": type the "
                + "member details by hand.");
        assertThat(refused.error()).isNull();

        // A gateway that hands the unresolved target back without refusing it gets the same answer.
        FakeGateway undefined = new FakeGateway().environment("Member UAT", EnvironmentKind.UAT).url(EpaOperation.MEMBER_LOOKUP, TEMPLATE);
        MemberLookupResult returned = client(Map.of(), Map.of(), undefined, new ExchangeExecutor()).lookup(FORMS);
        assertThat(returned.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(returned.message()).isEqualTo("Member lookup is configured but not completely, because the placeholder {{memberApi}} "
                + "in its url has no value, and the active environment is \"Member UAT\": type the member details by hand.");

        // With no environment active, that is said in place of a name.
        FakeGateway none = FakeGateway.unconfigured().url(EpaOperation.MEMBER_LOOKUP, "{{memberApi}}/{{memberPath}}/{id}");
        assertThat(client(Map.of(), Map.of(), none, new ExchangeExecutor()).lookup(FORMS).message()).isEqualTo("Member lookup is configured "
                + "but not completely, because the placeholders {{memberApi}}, {{memberPath}} in its url have no value, and no environment "
                + "is active: type the member details by hand.");
        // Nothing was sent in any of the three.
        server.verify();
    }

    @Test
    void aHeaderThatStillHoldsAPlaceholderIsDisabledAndNamesTheVariableWithNothingSent() {
        gateway.headers(EpaOperation.MEMBER_LOOKUP, List.of(ResolvedHeader.unresolved("X-Api-Key", List.of("memberApiKey"))));

        MemberLookupResult result = client().lookup(FORMS);

        // No request left: without its key it would fail at the far side with nothing to show why.
        server.verify();
        assertThat(result.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(result.message()).isEqualTo("Member lookup is configured but not completely, because the placeholder {{memberApiKey}} "
                + "in the header X-Api-Key has no value, and the active environment is \"Member UAT\": type the member details by hand.");
        assertThat(result.error()).isNull();
        assertThat(result.wire()).isNull();
        // The same when the Onyx token would have gone with it: the token is not fetched to stand in for the missing key.
        MemberLookupResult withToken = client(Map.of("member.use-onyx-auth", "true"), ONYX_AUTH_ON).lookup(FORMS);
        server.verify();
        assertThat(withToken.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(withToken.message()).contains("{{memberApiKey}}").contains("the header X-Api-Key");
    }

    @Test
    void aTargetThatCannotBeCalledOrARequestThatIsNotValidHttpIsDisabledWithTheReason() {
        FakeGateway noHost = new FakeGateway().url(EpaOperation.MEMBER_LOOKUP, "/api/members/{id}");
        MemberLookupResult unusable = client(Map.of(), Map.of(), noHost, new ExchangeExecutor()).lookup(FORMS);
        assertThat(unusable.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(unusable.message()).isEqualTo("Member lookup is configured, but lookup.member.url does not resolve to a url that can "
                + "be called: correct it, and until then type the member details by hand.");

        gateway.headers(EpaOperation.MEMBER_LOOKUP, List.of(new ResolvedHeader("X Bad Name", "v", "v")));
        MemberLookupResult badHeader = client().lookup(FORMS);
        assertThat(badHeader.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(badHeader.message()).contains("not valid HTTP").contains("lookup.member.headers");

        // The gateway refuses an ID it has no token for. Its message may quote the ID, so it is not passed on.
        FakeGateway refusing = new FakeGateway().failing(new BadInputException("The url has no token for the value S123456789.", "Add {id}."));
        MemberLookupResult noToken = client(Map.of(), Map.of(), refusing, new ExchangeExecutor()).lookup(FORMS);
        assertThat(noToken.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(noToken.message()).isEqualTo("Member lookup is configured, but its url has no {id} token for the member ID to go in: "
                + "correct lookup.member.url, and until then type the member details by hand.");
        assertThat(noToken.message()).doesNotContain("S123456789");

        FakeGateway guarded = new FakeGateway().failing(new ProdWriteBlockedException(EpaOperation.MEMBER_LOOKUP,
                new EnvironmentRef("Member Production", EnvironmentKind.PROD), "Writes are blocked.", "Unlock writes."));
        MemberLookupResult blocked = client(Map.of(), Map.of(), guarded, new ExchangeExecutor()).lookup(FORMS);
        // Refused, not switched off: the refusal is repeated, and what to do about it.
        assertThat(blocked.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(blocked.message()).isEqualTo("Member lookup was refused by the production guardrail and not sent: Writes are blocked.");
        assertThat(blocked.error().action()).isEqualTo("Unlock writes. Until then, type the member details by hand.");
        server.verify();
    }

    // ---------------------------------------------------------------------------------------
    // A configuration that changes under a lookup

    /** A gateway whose configuration changes straight after it prepares a call, as when a tester switches environment mid-lookup. */
    private static final class ChangingGateway implements OperationGateway {
        private final FakeGateway fake;

        private ChangingGateway(FakeGateway fake) {
            this.fake = fake;
        }

        @Override
        public PreparedCall prepare(CallIntent intent) {
            PreparedCall call = fake.prepare(intent);
            fake.changeConfiguration();
            return call;
        }

        @Override
        public ResolvedText resolve(String template, Map<String, String> runValues) {
            return fake.resolve(template, runValues);
        }

        @Override
        public EnvironmentRef activeEnvironment() {
            return fake.activeEnvironment();
        }

        @Override
        public WriteAccess writeAccess(ProdUnlock unlock) {
            return fake.writeAccess(unlock);
        }

        @Override
        public long epoch() {
            return fake.epoch();
        }
    }

    @Test
    void aLookupPreparedBeforeTheConfigurationChangedIsNotSentAndNoTokenIsFetchedForIt() {
        ChangingGateway changing = new ChangingGateway(gateway);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("gateway", changing);
        ExchangeExecutor guarded = new ExchangeExecutor(beans.getBeanProvider(OperationGateway.class));

        for (Map<String, String> lookup : List.of(Map.<String, String>of(), Map.of("member.use-onyx-auth", "true"))) {
            MemberLookupResult result = client(lookup, ONYX_AUTH_ON, changing, guarded).lookup(FORMS);
            // Neither the token endpoint nor the member API was called.
            server.verify();
            assertThat(result.status()).isEqualTo(LookupStatus.FAULT);
            assertThat(result.message()).isEqualTo("Member lookup was not sent, because the configuration changed while it was being prepared.");
            assertThat(result.error().kind()).isEqualTo(ErrorKind.CONFIGURATION);
            assertThat(result.wire().request().sent()).isFalse();
            assertThat(result.wire().request().note()).isEqualTo("Not sent. The configuration changed after this request was prepared.");
            assertThat(result.wire().response()).isNull();
        }

        // The same answer when the gateway itself finds the configuration has moved on, before there is a request to show.
        FakeGateway refusing = new FakeGateway().failing(ConfigurationChangedException.sinceTheScreenWasDrawn());
        MemberLookupResult early = client(Map.of(), Map.of(), refusing, new ExchangeExecutor()).lookup(FORMS);
        server.verify();
        assertThat(early.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(early.message()).isEqualTo("Member lookup was not sent, because the configuration changed while it was being prepared.");
        assertThat(early.error().kind()).isEqualTo(ErrorKind.CONFIGURATION);
        assertThat(early.wire()).isNull();
    }
}
