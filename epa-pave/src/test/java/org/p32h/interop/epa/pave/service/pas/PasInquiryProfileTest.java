package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.StartupChecks;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import tools.jackson.databind.JsonNode;

/**
 * The inquiry's Claim declares the PAS profile-claim-inquiry (the vendor's sandbox refused an
 * inquiry without it), and both bundles stamp whole seconds in UTC (the sandbox refused seven
 * fractional digits and a local offset), whatever the zone and the precision of the clock.
 */
class PasInquiryProfileTest {

    /** A clock with nanoseconds and a zone west of UTC: what the stamps must not carry. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T14:06:08.987654321Z"), ZoneId.of("America/New_York"));

    private static PasRequestBuilder builder() {
        return new PasRequestBuilder(new FakeCodeList(), StartupChecks.ATTACHMENT_FHIR, CLOCK, UUID::randomUUID);
    }

    private static JsonNode inquiry() {
        PasInquireRequest request = PasInquireRequest.builder().member(MemberDetail.builder().memberId("S123456789").build())
                .provider(ProviderDetail.builder().npi("1234567893").build()).build();
        return JsonSafe.parse(builder().inquire(request, "claim-test-0001", List.of(CodeInput.of("95810")), "given on the form",
                "urn:uuid:00000000-0000-4000-8000-0000000000b1", name -> new ResolvedText(name, null, null, List.of(), List.of(name)))
                .json()).node();
    }

    private static JsonNode submission() {
        PasSubmitRequest request = PasSubmitRequest.builder().member(MemberDetail.builder().memberId("S123456789").build())
                .provider(ProviderDetail.builder().npi("1234567893").build()).procedureCodes(List.of(CodeInput.of("95810"))).build();
        return JsonSafe.parse(builder().submit(request, "claim-test-0001", true, "urn:uuid:00000000-0000-4000-8000-0000000000b2",
                name -> new ResolvedText(name, null, null, List.of(), List.of(name))).json()).node();
    }

    @Test
    void theInquiryClaimDeclaresTheInquiryProfile() {
        JsonNode claim = inquiry().at("/entry/0/resource");
        assertThat(claim.get("resourceType").asString()).isEqualTo("Claim");
        assertThat(claim.at("/meta/profile")).hasSize(1);
        assertThat(claim.at("/meta/profile/0").asString())
                .isEqualTo("http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-claim-inquiry");
    }

    @Test
    void theSubmittedClaimDeclaresNoInquiryProfile() {
        assertThat(submission().at("/entry/0/resource").has("meta")).isFalse();
    }

    @Test
    void bothBundlesStampWholeSecondsInUtc() {
        for (JsonNode bundle : List.of(inquiry(), submission())) {
            assertThat(bundle.get("timestamp").asString()).isEqualTo("2026-11-22T14:06:08Z");
            assertThat(bundle.at("/entry/0/resource/created").asString()).isEqualTo("2026-11-22T14:06:08Z");
        }
    }
}
