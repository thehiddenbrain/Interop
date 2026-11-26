package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * No member data in the log. Each test runs lookups that end every way a lookup can end, captures
 * everything any logger wrote meanwhile at every level, and asserts two things: the outcome was
 * logged, so the test cannot pass on a silent client, and neither the identifier nor any field of
 * the response is anywhere in what was written.
 */
class LookupLoggingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final String MEMBER_LOGGER = "org.p32h.interop.epa.pave.client.lookup.HttpMemberLookupClient";
    private static final String PROVIDER_LOGGER = "org.p32h.interop.epa.pave.client.lookup.HttpProviderLookupClient";

    private static final MemberIdForms FORMS = MemberIds.forms("S12345678   01");
    private static final String MEMBER_URL = "https://members.example.invalid/api/members/S1234567801";
    private static final String MEMBER = """
            {
              "memberId": "S12345678   01",
              "firstName": "Zebediah-Placeholder",
              "lastName": "Placeholder-Quillfeather",
              "dateOfBirth": "07/19/1961",
              "gender": "nonbinary-placeholder",
              "lineOfBusiness": "Medicare-Placeholder-Line",
              "planName": "Synthetic Plan Zeta",
              "coverageStatus": "Termed-Placeholder",
              "coverageStart": "2021-02-03",
              "coverageEnd": "2024-05-06",
              "subscriberId": "S9876543210",
              "address": {"line1": "742 Placeholder Terrace", "line2": "Flat 9Z", "city": "Sampletown-Upon-Test", "state": "ZZ", "postalCode": "00000-1234"},
              "unknownField": "not-interpreted-placeholder"
            }""";
    /** Everything that must never be written: the ID in both forms, the url, and every value of the response, read and unread. */
    private static final List<String> MEMBER_DATA = List.of("S12345678   01", "S1234567801", "S12345678", "members.example.invalid",
            "/api/members/", "Zebediah", "Quillfeather", "07/19/1961", "1961-07-19", "1961", "nonbinary", "Medicare-Placeholder-Line",
            "Synthetic Plan Zeta", "Termed-Placeholder", "2021-02-03", "2024-05-06", "S9876543210", "742 Placeholder Terrace", "Flat 9Z",
            "Sampletown-Upon-Test", "00000-1234", "not-interpreted-placeholder", "unknownField", "placeholder-key-value");

    private static final String NPI = Npi.withCheckDigit("123456789");
    private static final String PROVIDER_URL = "https://directory.example.invalid/providers?npi=" + NPI;
    private static final String PROVIDER = "{\"npi\":\"" + NPI + "\",\"firstName\":\"Peregrine-Placeholder\",\"lastName\":\"Placeholder-Thistlewood\","
            + "\"specialty\":\"Sleep-Medicine-Placeholder\",\"networkStatus\":\"Out of network\",\"phone\":\"617-555-0142\"}";
    private static final List<String> PROVIDER_DATA = List.of(NPI, "directory.example.invalid", "Peregrine", "Thistlewood",
            "Sleep-Medicine-Placeholder", "Out of network", "617-555-0142");

    private final FakeGateway gateway = new FakeGateway().environment("Lookup UAT", EnvironmentKind.UAT)
            .url(EpaOperation.MEMBER_LOOKUP, "https://members.example.invalid/api/members/{id}")
            .url(EpaOperation.PROVIDER_LOOKUP, "https://directory.example.invalid/providers?npi={id}")
            .headers(EpaOperation.MEMBER_LOOKUP, List.of(new ResolvedHeader("X-Api-Key", "[secret memberApiKey]", "placeholder-key-value")));
    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private HttpMemberLookupClient members(Map<String, String> lookup) {
        return new HttpMemberLookupClient(new ExchangeExecutor(), OnyxAccessTestFactory.tokens(OnyxProperties.defaults(), gateway, restClient, CLOCK),
                gateway, LookupProperties.of(lookup), restClient, CLOCK);
    }

    private HttpProviderLookupClient providers(Map<String, String> lookup) {
        return new HttpProviderLookupClient(new ExchangeExecutor(), OnyxAccessTestFactory.tokens(OnyxProperties.defaults(), gateway, restClient, CLOCK),
                gateway, LookupProperties.of(lookup), restClient);
    }

    @Test
    void aMemberLookupLogsItsOutcomeAndHttpStatusAndNothingAboutTheMember() {
        List<MemberLookupResult> results = new ArrayList<>();
        List<String> everything;
        List<String> own;
        try (CapturedLog log = new CapturedLog()) {
            server.expect(requestTo(MEMBER_URL)).andRespond(withSuccess(MEMBER, MediaType.APPLICATION_JSON));
            results.add(members(Map.of()).lookup(FORMS));
            server.reset();
            server.expect(requestTo(MEMBER_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                    .body("{\"message\":\"No member S1234567801, Placeholder-Quillfeather\"}").contentType(MediaType.APPLICATION_JSON));
            results.add(members(Map.of()).lookup(FORMS));
            server.reset();
            server.expect(requestTo(MEMBER_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("<html>Failed while loading member S1234567801 Zebediah-Placeholder</html>"));
            results.add(members(Map.of()).lookup(FORMS));
            server.reset();
            server.expect(requestTo(MEMBER_URL)).andRespond(withSuccess("{\"memberId\": \"S1234567801\", \"lastName\": \"Quillfeather", MediaType.APPLICATION_JSON));
            results.add(members(Map.of()).lookup(FORMS));
            server.reset();
            // An exception message from the HTTP layer quotes the url, which holds the ID.
            server.expect(requestTo(MEMBER_URL)).andRespond(withException(new SocketTimeoutException("Read timed out: GET " + MEMBER_URL)));
            results.add(members(Map.of()).lookup(FORMS));
            server.reset();
            results.add(members(Map.of("member.enabled", "false")).lookup(FORMS));
            everything = log.lines();
            own = log.ownLines();
        }

        // The lookups ended the ways this test means them to, with the member data in the results, where it belongs.
        assertThat(results).extracting(MemberLookupResult::status).containsExactly(LookupStatus.FOUND, LookupStatus.NOT_FOUND,
                LookupStatus.FAULT, LookupStatus.FAULT, LookupStatus.NOT_ANSWERING, LookupStatus.DISABLED);
        assertThat(results.get(0).member().lastName()).isEqualTo("Placeholder-Quillfeather");
        assertThat(results.get(0).member().dateOfBirth()).isEqualTo("1961-07-19");
        assertThat(results.get(0).attention()).isNotEmpty();
        assertThat(results.get(0).wire().request().url()).isEqualTo(MEMBER_URL);

        // What was logged: the outcome and the HTTP status of each, and nothing else from this tool.
        assertThat(own).containsExactly(
                "INFO " + MEMBER_LOGGER + ": Member lookup ended FOUND with HTTP status 200",
                "INFO " + MEMBER_LOGGER + ": Member lookup ended NOT_FOUND with HTTP status 404",
                "INFO " + MEMBER_LOGGER + ": Member lookup ended FAULT with HTTP status 500",
                "INFO " + MEMBER_LOGGER + ": Member lookup ended FAULT with HTTP status 200",
                "INFO " + MEMBER_LOGGER + ": Member lookup ended NOT_ANSWERING with HTTP status none",
                "INFO " + MEMBER_LOGGER + ": Member lookup ended DISABLED with HTTP status none");
        // And in everything any logger wrote, at any level, nothing about the member.
        String written = String.join("\n", everything);
        for (String data : MEMBER_DATA) {
            assertThat(written).as("the log must not hold \"" + data + "\"").doesNotContain(data);
        }
    }

    @Test
    void aProviderLookupLogsItsOutcomeAndHttpStatusOnly() {
        List<ProviderLookupResult> results = new ArrayList<>();
        List<String> everything;
        List<String> own;
        try (CapturedLog log = new CapturedLog()) {
            server.expect(requestTo(PROVIDER_URL)).andRespond(withSuccess(PROVIDER, MediaType.APPLICATION_JSON));
            results.add(providers(Map.of()).lookup(NPI));
            server.reset();
            server.expect(requestTo(PROVIDER_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
            results.add(providers(Map.of()).lookup(NPI));
            server.reset();
            server.expect(requestTo(PROVIDER_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("upstream failed for " + NPI));
            results.add(providers(Map.of()).lookup(NPI));
            server.reset();
            results.add(providers(Map.of("provider.enabled", "false")).lookup(NPI));
            everything = log.lines();
            own = log.ownLines();
        }

        assertThat(results).extracting(ProviderLookupResult::status).containsExactly(LookupStatus.FOUND, LookupStatus.NOT_FOUND,
                LookupStatus.FAULT, LookupStatus.DISABLED);
        assertThat(results.get(0).provider().lastName()).isEqualTo("Placeholder-Thistlewood");
        assertThat(own).containsExactly(
                "INFO " + PROVIDER_LOGGER + ": Provider lookup ended FOUND with HTTP status 200",
                "INFO " + PROVIDER_LOGGER + ": Provider lookup ended NOT_FOUND with HTTP status 404",
                "INFO " + PROVIDER_LOGGER + ": Provider lookup ended FAULT with HTTP status 502",
                "INFO " + PROVIDER_LOGGER + ": Provider lookup ended DISABLED with HTTP status none");
        String written = String.join("\n", everything);
        for (String data : PROVIDER_DATA) {
            assertThat(written).as("the log must not hold \"" + data + "\"").doesNotContain(data);
        }
    }

    @Test
    void theMockClientsAndTheServicesWriteNothingAtAll() {
        List<String> own;
        try (CapturedLog log = new CapturedLog()) {
            LookupFixtures fixtures = new LookupFixtures();
            MockMemberLookupClient members = new MockMemberLookupClient(fixtures, LookupProperties.defaults(), CLOCK);
            MockProviderLookupClient providers = new MockProviderLookupClient(fixtures, LookupProperties.defaults());
            assertThat(members.lookup(MemberIds.forms("P32H-PA-REQ")).status()).isEqualTo(LookupStatus.FOUND);
            assertThat(members.lookup(MemberIds.forms("P32H-NOMEM")).status()).isEqualTo(LookupStatus.NOT_FOUND);
            assertThat(members.lookup(MemberIds.forms("P32H-LOOKUP-FAULT")).status()).isEqualTo(LookupStatus.FAULT);
            assertThat(providers.lookup(NPI).status()).isEqualTo(LookupStatus.FOUND);
            own = log.ownLines();
        }
        assertThat(own).isEmpty();
    }
}
