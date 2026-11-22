package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.service.crd.CrdRequestBuilder;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrRequestBuilder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The vendor's own practitioner id (R-40): with it given the NPI is optional, its characters are
 * checked, and CRD and DTR name the Practitioner by it with a note.
 */
class VendorPractitionerIdTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC);
    private static final MemberDetail MEMBER = MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname")
            .gender("female").build();

    private static List<FieldError> errors(ProviderDetail provider) {
        return new RunInputs(CLOCK).providerByNpiOrVendorId(provider).errors();
    }

    private static ProviderDetail vendor(String id) {
        return ProviderDetail.builder().firstName("Pat").lastName("Provider").vendorPractitionerId(id).build();
    }

    @Test
    void withAVendorIdTheNpiIsOptionalAndATypedNpiIsStillChecked() {
        assertThat(errors(vendor("P123"))).isEmpty();
        assertThat(errors(vendor("P123").toBuilder().npi("1234567893").build())).isEmpty();
        assertThat(errors(vendor("P123").toBuilder().npi("1234567890").build())).extracting(FieldError::field).containsExactly("provider.npi");
        assertThat(errors(ProviderDetail.builder().firstName("Pat").build())).containsExactly(new FieldError("provider.npi",
                "Enter the ordering provider's NPI, or the vendor practitioner id under Edit provider fields."));
        assertThat(errors(null)).extracting(FieldError::field).containsExactly("provider.npi");
    }

    @Test
    void theCharactersOfAVendorIdAreChecked() {
        String why = "A vendor practitioner id holds letters, digits, - and . only, at most 64 characters. It is sent as a FHIR resource id, "
                + "which allows nothing else.";
        assertThat(errors(vendor("P-12.3"))).isEmpty();
        assertThat(errors(vendor("x".repeat(64)))).isEmpty();
        for (String bad : List.of("P 123", "P/123", "P_123", "x".repeat(65), "Pé123")) {
            assertThat(errors(vendor(bad))).as(bad).containsExactly(new FieldError("provider.vendorPractitionerId", why));
        }
        // The rule for steps that still need an NPI checks the characters too.
        assertThat(new RunInputs(CLOCK).provider(vendor("P 1").toBuilder().npi("1234567893").build()).errors())
                .containsExactly(new FieldError("provider.vendorPractitionerId", why));
    }

    @Test
    void crdNamesThePractitionerByTheVendorIdWithANote() {
        CrdRunRequest request = CrdRunRequest.builder().member(MEMBER).provider(vendor("P123")).procedureCodes(List.of(CodeInput.of("E0601")))
                .build();
        CrdRequestBuilder.Built built = new CrdRequestBuilder(null, CLOCK).build(request, "hook-1", ResolvedText::plain);
        JsonNode json = JsonSafe.parse(built.json()).node();
        assertThat(json.at("/context/userId").asString()).isEqualTo("Practitioner/P123");
        assertThat(json.at("/context/draftOrders/entry/0/resource/requester/reference").asString()).isEqualTo("Practitioner/P123");
        assertThat(json.at("/prefetch/practitionerBundle/entry/0/resource/id").asString()).isEqualTo("P123");
        assertThat(json.at("/prefetch/practitionerBundle/entry/0/resource").has("identifier")).isFalse();
        assertThat(built.notes()).contains("The Practitioner was sent with the id P123, the vendor practitioner id given, so userId and the "
                + "requester of every order read Practitioner/P123.");
        assertThat(built.incomplete()).extracting(CaseResources.Required::element).contains("Practitioner.identifier");
    }

    @Test
    void withoutAVendorIdEverythingIsAsBeforeButTheRequesterIsThePractitioner() {
        ProviderDetail provider = ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();
        CrdRunRequest request = CrdRunRequest.builder().member(MEMBER).provider(provider).procedureCodes(List.of(CodeInput.of("E0601"))).build();
        CrdRequestBuilder.Built built = new CrdRequestBuilder(null, CLOCK).build(request, "hook-1", ResolvedText::plain);
        JsonNode json = JsonSafe.parse(built.json()).node();
        assertThat(json.at("/context/userId").asString()).isEqualTo("Practitioner/practitioner-1");
        assertThat(json.at("/prefetch/practitionerBundle/entry/0/resource/identifier/0/value").asString()).isEqualTo("1234567893");
        assertThat(built.notes()).noneMatch(note -> note.contains("vendor practitioner id"));
    }

    @Test
    void dtrNamesThePractitionerByTheVendorId() {
        DtrPackageRequest request = DtrPackageRequest.builder().member(MEMBER).provider(vendor("P123"))
                .procedureCodes(List.of(CodeInput.of("E0601"))).build();
        DtrRequestBuilder.Package built = new DtrRequestBuilder(null).buildPackage(request, ResolvedText::plain);
        assertThat(built.json()).contains("\"Practitioner/P123\"").doesNotContain("practitioner-1\"");
        assertThat(built.notes()).contains("The Practitioner was sent with the id P123, the vendor practitioner id given, so every reference "
                + "to the provider reads Practitioner/P123.");
    }
}
