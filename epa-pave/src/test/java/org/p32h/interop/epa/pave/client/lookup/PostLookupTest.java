package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.lookup.LookupKind;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings;
import org.p32h.interop.epa.pave.service.lookup.LookupSettings.SignIn;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * A lookup saved on screen as a POST (R-11), against an API on the loopback interface: the member
 * ID goes in the body, filled and JSON-escaped, never in the url; the date of service in the
 * saved format; each ending is one of the states of plan 7.5; the saved list and field paths are
 * where the answer is read; and the member ID reaches no log line.
 */
class PostLookupTest {

    private static final Clock CLOCK = ConfigFixture.CLOCK;
    private static final String BODY = "{\"memberIds\": [\"{memberId}\"], \"dateRange\": {\"from\": \"{dateOfService}\", \"to\": \"{dateOfService}\"}}";
    private static final String FOUND = "{\"members\": [{\"memberId\": \"S123456789\", \"firstName\": \"Placeholder-Given\", "
            + "\"lastName\": \"Placeholder-Surname\", \"dob\": \"1970-01-01\", \"coverageStatus\": \"active\"}]}";
    private static final LocalDate DATE = LocalDate.parse("2026-03-10");

    @TempDir
    Path directory;

    private ConfigFixture fixture() {
        ConfigFixture fixture = ConfigFixture.live(directory);
        fixture.activeEnvironmentOf("Lookup Sandbox", EnvironmentKind.SANDBOX, "payerName", "Placeholder Payer");
        return fixture;
    }

    static LookupSettings post(String address, String body, String dateFormat, String idForm, String listPath, Map<String, String> paths,
                               Integer readMs) {
        return new LookupSettings(true, "POST", address, body, dateFormat, idForm, null, List.of(), listPath, paths, null, readMs);
    }

    private HttpMemberLookupClient members(ConfigFixture fixture, LookupSettings settings) {
        return new HttpMemberLookupClient(new ExchangeExecutor(),
                OnyxAccessTestFactory.tokens(OnyxProperties.defaults(), fixture.gateway, RestClient.builder().build(), CLOCK), fixture.gateway,
                LookupProperties.defaults(), RestClient.builder(), CLOCK, kind -> kind == LookupKind.MEMBER ? settings : null);
    }

    private HttpProviderLookupClient providers(ConfigFixture fixture, LookupSettings settings) {
        return new HttpProviderLookupClient(new ExchangeExecutor(),
                OnyxAccessTestFactory.tokens(OnyxProperties.defaults(), fixture.gateway, RestClient.builder().build(), CLOCK), fixture.gateway,
                LookupProperties.defaults(), RestClient.builder(), kind -> kind == LookupKind.PROVIDER ? settings : null);
    }

    @Test
    void thePostCarriesTheMemberIdInItsBodyFilledAndEscapedWithTheDateInItsFormatAndNothingInTheUrl() throws Exception {
        ConfigFixture fixture = fixture();
        try (LoopbackApi api = new LoopbackApi(); CapturedLog log = new CapturedLog()) {
            api.answer("/member/search", 200, FOUND);
            MemberLookupResult result = members(fixture, post(api.base() + "/member/search", BODY, "MM/dd/yyyy", "PACKED", "members",
                    Map.of("dateOfBirth", "dob"), null)).lookup(MemberIds.forms("S12345 6789"), DATE);

            assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
            assertThat(result.member().firstName()).isEqualTo("Placeholder-Given");
            assertThat(result.member().dateOfBirth()).isEqualTo("1970-01-01");
            LoopbackApi.Seen seen = api.last();
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.path()).isEqualTo("/member/search");
            assertThat(seen.headers().get("content-type")).startsWith("application/json");
            JsonNode body = JsonSafe.parse(seen.body()).node();
            assertThat(body.get("memberIds").get(0).asString()).isEqualTo("S123456789");
            assertThat(body.get("dateRange").get("from").asString()).isEqualTo("03/10/2026");
            assertThat(body.get("dateRange").get("to").asString()).isEqualTo("03/10/2026");
            // The wire view shows what was sent, as every call does.
            assertThat(result.wire().request().method()).isEqualTo("POST");
            assertThat(String.join("\n", log.lines())).doesNotContain("S123456789").doesNotContain("S12345 6789");
        }
    }

    @Test
    void aMemberIdHoldingAQuoteOrAPlaceholderIsSentAsTextAndNeverChangesTheBodyOrIsResolved() throws Exception {
        ConfigFixture fixture = fixture();
        try (LoopbackApi api = new LoopbackApi()) {
            api.answer("/member/search", 200, "{\"members\": []}");
            String bare = "{\"id\": {memberId}}";
            members(fixture, post(api.base() + "/member/search", bare, null, "AS_TYPED", "members", Map.of(), null))
                    .lookup(MemberIds.forms("S1\"23{{payerName}}"), null);
            JsonNode body = JsonSafe.parse(api.last().body()).node();
            assertThat(body.get("id").asString()).isEqualTo("S1\"23{{payerName}}");
        }
    }

    @Test
    void theIdFormDecidesHowTheLookupWritesTheMemberIdWhileTheCardKeepsItsOwnForms() throws Exception {
        ConfigFixture fixture = fixture();
        try (LoopbackApi api = new LoopbackApi()) {
            api.answer("/member/search", 200, FOUND);
            String body = "{\"id\": \"{memberId}\"}";
            for (String[] each : new String[][] {{"AS_TYPED", "S1234567 890"}, {"PACKED", "S1234567890"}, {"SPACED", "S12345678   90"},
                    {"SPLIT", "S12345678"}}) {
                members(fixture, post(api.base() + "/member/search", body, null, each[0], null, Map.of(), null))
                        .lookup(MemberIds.forms("S1234567 890"), null);
                assertThat(JsonSafe.parse(api.last().body()).node().get("id").asString()).as(each[0]).isEqualTo(each[1]);
            }
        }
    }

    @Test
    void severalNotFoundFailedAndNotAnsweringAreTheirOwnStates() throws Exception {
        ConfigFixture fixture = fixture();
        try (LoopbackApi api = new LoopbackApi()) {
            String body = "{\"id\": \"{memberId}\"}";
            api.answer("/several", 200, "{\"members\": [{\"firstName\": \"A-Placeholder\"}, {\"firstName\": \"B-Placeholder\"}]}");
            api.answer("/none", 200, "{\"members\": []}");
            api.answer("/failed", 500, "{}");
            api.answer("/elsewhere", 200, "{\"result\": \"ok\"}");
            api.slow("/slow", 3000);

            MemberLookupResult several = members(fixture, post(api.base() + "/several", body, null, null, "members", Map.of(), null))
                    .lookup(MemberIds.forms("S123456789"), null);
            assertThat(several.status()).isEqualTo(LookupStatus.SEVERAL);
            assertThat(several.statusLabel()).isEqualTo("Several matched");
            assertThat(several.member()).isNull();
            assertThat(several.message()).startsWith("The member API returned 2 records for one member ID");

            MemberLookupResult none = members(fixture, post(api.base() + "/none", body, null, null, "members", Map.of(), null))
                    .lookup(MemberIds.forms("S123456789"), null);
            assertThat(none.status()).isEqualTo(LookupStatus.NOT_FOUND);

            MemberLookupResult failed = members(fixture, post(api.base() + "/failed", body, null, null, null, Map.of(), null))
                    .lookup(MemberIds.forms("S123456789"), null);
            assertThat(failed.status()).isEqualTo(LookupStatus.FAULT);
            assertThat(failed.statusLabel()).isEqualTo("The lookup failed");

            MemberLookupResult elsewhere = members(fixture, post(api.base() + "/elsewhere", body, null, null, "data.members", Map.of(), null))
                    .lookup(MemberIds.forms("S123456789"), null);
            assertThat(elsewhere.status()).isEqualTo(LookupStatus.FAULT);
            assertThat(elsewhere.message()).isEqualTo("The member API answered, and its answer holds nothing at the List of matches saved "
                    + "for the member lookup in Configuration > Members.");

            MemberLookupResult slow = members(fixture, post(api.base() + "/slow", body, null, null, null, Map.of(), 300))
                    .lookup(MemberIds.forms("S123456789"), null);
            assertThat(slow.status()).isEqualTo(LookupStatus.NOT_ANSWERING);
            assertThat(slow.statusLabel()).isEqualTo("Not answering");
        }
        // A port nobody listens on: not answering either, and the sentence names the saved Address.
        MemberLookupResult unreachable = members(fixture, post("http://127.0.0.1:9/x", "{\"id\": \"{memberId}\"}", null, null, null, Map.of(),
                null)).lookup(MemberIds.forms("S123456789"), null);
        assertThat(unreachable.status()).isEqualTo(LookupStatus.NOT_ANSWERING);
        assertThat(unreachable.error().action()).contains("the Address of the member lookup in Configuration > Members");
    }

    @Test
    void aBodyThatNeedsTheDateOfServiceSendsNothingWithoutOneAndGuessesNothing() throws Exception {
        ConfigFixture fixture = fixture();
        try (LoopbackApi api = new LoopbackApi()) {
            MemberLookupResult result = members(fixture, post(api.base() + "/member/search", BODY, "yyyy-MM-dd", null, null, Map.of(), null))
                    .lookup(MemberIds.forms("S123456789"), null);
            assertThat(result.status()).isEqualTo(LookupStatus.DISABLED);
            assertThat(result.message()).contains("sends the date of service, and none was given, so nothing was sent");
            assertThat(api.seen()).isEmpty();
        }
    }

    @Test
    void aSavedGetPutsTheIdentifierInTheAddressAndTheFieldPathsReadTheProvider() throws Exception {
        ConfigFixture fixture = fixture();
        String npi = Npi.withCheckDigit("123456789");
        try (LoopbackApi api = new LoopbackApi()) {
            api.answer("/providers/" + npi, 200, "{\"data\": {\"person\": {\"given\": \"Placeholder-Given\"}, \"contact\": {\"faxNo\": "
                    + "\"555-0100\"}, \"specialty\": \"Cardiology\"}}");
            LookupSettings get = new LookupSettings(true, "GET", api.base() + "/providers/{id}", null, null, null, null, List.of(), "data",
                    Map.of("firstName", "person.given", "fax", "contact.faxNo"), null, null);
            ProviderLookupResult result = providers(fixture, get).lookup(npi, null);
            assertThat(api.last().method()).isEqualTo("GET");
            assertThat(api.last().body()).isEmpty();
            assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
            assertThat(result.provider().firstName()).isEqualTo("Placeholder-Given");
            assertThat(result.provider().fax()).isEqualTo("555-0100");
            assertThat(result.provider().specialty()).isEqualTo("Cardiology");
        }
    }

    @Test
    void aSavedLookupThatIsSwitchedOffSendsNothingAndSaysWhere() throws Exception {
        ConfigFixture fixture = fixture();
        try (LoopbackApi api = new LoopbackApi()) {
            LookupSettings off = new LookupSettings(false, "POST", api.base() + "/x", "{\"id\": \"{memberId}\"}", null, null,
                    new SignIn(SignIn.NONE, null, null, null, null, null, null, null, null), List.of(), null, Map.of(), null, null);
            MemberLookupResult result = members(fixture, off).lookup(MemberIds.forms("S123456789"), null);
            assertThat(result.status()).isEqualTo(LookupStatus.DISABLED);
            assertThat(result.message()).isEqualTo("Member lookup is switched off (Lookup on is not ticked for the member lookup in "
                    + "Configuration > Members): type the member details by hand.");
            assertThat(api.seen()).isEmpty();
        }
    }
}
