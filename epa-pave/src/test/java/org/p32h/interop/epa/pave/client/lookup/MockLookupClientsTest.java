package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;

/** The two mock clients: what they say about a call that never left this machine. */
class MockLookupClientsTest {

    private static final LookupFixtures FIXTURES = new LookupFixtures();

    private static Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneId.of("America/New_York"));
    }

    private static MockMemberLookupClient members(Map<String, String> properties, String instant) {
        return new MockMemberLookupClient(FIXTURES, LookupProperties.of(properties), clockAt(instant));
    }

    private static MockMemberLookupClient members() {
        return members(Map.of(), "2026-03-10T15:00:00Z");
    }

    private static MockProviderLookupClient providers(Map<String, String> properties) {
        return new MockProviderLookupClient(FIXTURES, LookupProperties.of(properties));
    }

    @Test
    void aMemberIsAnsweredFromItsFixtureThroughTheMappingAndTheWireViewSaysNothingWasSent() {
        MemberLookupResult result = members().lookup(MemberIds.forms("P32H-PA-REQ"));
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.member().firstName()).isEqualTo("Avery");
        assertThat(result.member().lineOfBusiness()).isEqualTo("Medicare");
        assertThat(result.message()).isEqualTo("Member found, with coverage reported as active.");
        WireRequest request = result.wire().request();
        assertThat(request.sent()).isFalse();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.url()).isNull();
        assertThat(request.urlTemplate()).isEmpty();
        assertThat(request.headers()).containsExactly(new WireHeader("Accept", "application/json"));
        assertThat(request.note()).isEqualTo("Mock profile. Answered from the fixture file p32h-pa-req.json. Nothing left this machine.");
        assertThat(result.wire().response().status()).isEqualTo(200);
        assertThat(result.wire().response().body()).contains("Placeholder-PaRequired");
        assertThat(result.wire().memberId()).isEqualTo(MemberIds.forms("P32H-PA-REQ"));
    }

    @Test
    void theConfiguredUrlTemplateIsShownAsItIsWrittenAndNeverResolved() {
        MemberLookupResult result = members(Map.of("member.url", " {{memberApi}}/members/{id} "), "2026-03-10T15:00:00Z")
                .lookup(MemberIds.forms("S123456789"));
        assertThat(result.wire().request().urlTemplate()).isEqualTo("{{memberApi}}/members/{id}");
        assertThat(result.wire().request().url()).isNull();
        ProviderLookupResult provider = providers(Map.of("provider.url", "https://directory.example.invalid/providers?npi={id}"))
                .lookup("1234567893");
        assertThat(provider.wire().request().urlTemplate()).isEqualTo("https://directory.example.invalid/providers?npi={id}");
    }

    @Test
    void anIdWithNoFixtureIsAnsweredAsAnApiThatDoesNotKnowIt() {
        MemberLookupResult result = members().lookup(MemberIds.forms("P32H-NOMEM"));
        assertThat(result.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(result.message()).isEqualTo("The member API does not know this member ID (it answered HTTP 404).");
        assertThat(result.error()).isNull();
        assertThat(result.wire().response().status()).isEqualTo(404);
        assertThat(result.wire().request().note()).isEqualTo("Mock profile. No fixture file holds this member ID, so the mock answered "
                + "as a service that does not know it. Nothing left this machine.");
        assertThat(result.additionalFields()).extracting(AdditionalField::path).containsExactly("error", "message");
        // An ID nobody planned for is treated the same way.
        assertThat(members().lookup(MemberIds.forms("S000000000")).status()).isEqualTo(LookupStatus.NOT_FOUND);

        ProviderLookupResult provider = providers(Map.of()).lookup("9999999995");
        assertThat(provider.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(provider.wire().request().note()).contains("No fixture file holds this NPI");
        assertThat(provider.wire().response().body()).contains("No provider matches the identifier supplied.");
    }

    @Test
    void aSentinelTypedInAnotherLetterCaseIsFoundAndTheDifferenceIsSaid() {
        MemberLookupResult result = members().lookup(MemberIds.forms("p32h-pa-req"));
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        // The ID is not rewritten. What goes on to the next request is what was typed.
        assertThat(result.member().memberId()).isEqualTo("p32h-pa-req");
        assertThat(result.attention()).containsExactly("The member API writes this member ID in different letter case from the ID that "
                + "was typed. The ID is sent as typed.");
    }

    @Test
    void coverageDatesAreComparedWithTheClockTheToolRunsOn() {
        // On a day before its coverage ended, the inactive member has one reason to look: the status.
        MemberLookupResult before = members(Map.of(), "2024-06-01T15:00:00Z").lookup(MemberIds.forms("P32H-INACTIVE"));
        assertThat(before.attention()).containsExactly("Coverage is not active: the member API returned the coverage status \"Termed\".");
        // Afterwards it has two.
        MemberLookupResult after = members(Map.of(), "2026-03-10T15:00:00Z").lookup(MemberIds.forms("P32H-INACTIVE"));
        assertThat(after.attention()).hasSize(2);
        // The day is the day in the zone the tool runs in, not in UTC: at 03:00 UTC on the first of January it is still the 31st in New York.
        MemberLookupResult lastDay = members(Map.of(), "2025-01-01T03:00:00Z").lookup(MemberIds.forms("P32H-INACTIVE"));
        assertThat(lastDay.attention()).hasSize(1);
    }

    @Test
    void aLookupThatIsSwitchedOffIsDisabledOnMockTooAndIsNotAnError() {
        MemberLookupResult member = members(Map.of("member.enabled", "false"), "2026-03-10T15:00:00Z").lookup(MemberIds.forms("P32H-PA-REQ"));
        assertThat(member.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(member.message()).isEqualTo("Member lookup is switched off (lookup.member.enabled is false): type the member details by hand.");
        assertThat(member.error()).isNull();
        assertThat(member.member()).isNull();
        assertThat(member.wire()).isNull();
        assertThat(member.memberId()).isEqualTo(MemberIds.forms("P32H-PA-REQ"));
        // Each lookup has its own switch.
        assertThat(providers(Map.of("member.enabled", "false")).lookup("1234567893").status()).isEqualTo(LookupStatus.FOUND);

        ProviderLookupResult provider = providers(Map.of("provider.enabled", "false")).lookup("1234567893");
        assertThat(provider.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(provider.message()).isEqualTo("Provider lookup is switched off (lookup.provider.enabled is false): type the provider details by hand.");
        assertThat(provider.error()).isNull();
        assertThat(provider.npi()).isEqualTo("1234567893");
        assertThat(provider.wire()).isNull();
    }

    @Test
    void aProviderIsAnsweredFromItsFixtureAndAFaultKeepsItsStatusAndBody() {
        ProviderLookupResult found = providers(Map.of()).lookup("1234567893");
        assertThat(found.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(found.provider().displayName()).isEqualTo("Pat Placeholder-Provider");
        assertThat(found.provider().taxonomyCode()).isEqualTo("2084S0012X");
        assertThat(found.wire().request().note()).isEqualTo("Mock profile. Answered from the fixture file 1234567893.json. Nothing left this machine.");
        assertThat(found.wire().response().headers()).containsExactly(new WireHeader("Content-Type", "application/json"));

        ProviderLookupResult fault = providers(Map.of()).lookup("9999999987");
        assertThat(fault.status()).isEqualTo(LookupStatus.FAULT);
        assertThat(fault.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(fault.wire().response().status()).isEqualTo(500);
        assertThat(fault.wire().response().parsed()).isFalse();
        // The body was written in the file as text, so the mock does not claim it is JSON.
        assertThat(fault.wire().response().headers()).isEmpty();
    }
}
