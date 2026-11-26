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
 * The vendor's own patient id (R-40): the Patient's id and CRD's patientId when given, with a
 * note; patient-1 with a note when not; never a form of the member ID.
 */
class VendorPatientIdTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC);
    private static final ProviderDetail PROVIDER = ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();

    private static MemberDetail member(String vendorPatientId) {
        return MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname").gender("female")
                .vendorPatientId(vendorPatientId).build();
    }

    private static CrdRequestBuilder.Built crd(MemberDetail member) {
        CrdRunRequest request = CrdRunRequest.builder().member(member).provider(PROVIDER).procedureCodes(List.of(CodeInput.of("E0601"))).build();
        return new CrdRequestBuilder(null, CLOCK).build(request, "hook-1", ResolvedText::plain);
    }

    @Test
    void theVendorPatientIdIsThePatientsIdAndThePatientIdWithANote() {
        CrdRequestBuilder.Built built = crd(member("PATIENT-PLACEHOLDER-1"));
        JsonNode json = JsonSafe.parse(built.json()).node();
        assertThat(json.at("/context/patientId").asString()).isEqualTo("PATIENT-PLACEHOLDER-1");
        assertThat(json.at("/prefetch/patientBundle/entry/0/resource/id").asString()).isEqualTo("PATIENT-PLACEHOLDER-1");
        assertThat(json.at("/prefetch/coverageBundle/entry/0/resource/beneficiary/reference").asString()).isEqualTo("Patient/PATIENT-PLACEHOLDER-1");
        assertThat(json.at("/context/draftOrders/entry/0/resource/subject/reference").asString()).isEqualTo("Patient/PATIENT-PLACEHOLDER-1");
        assertThat(built.json()).doesNotContain("patient-1\"");
        assertThat(built.notes()).contains("The Patient and patientId were sent with the id PATIENT-PLACEHOLDER-1, the vendor patient id given.");
    }

    @Test
    void withoutItThePatientIsPatientOneWithANoteAndNeverTheMemberId() {
        CrdRequestBuilder.Built built = crd(member(null));
        JsonNode json = JsonSafe.parse(built.json()).node();
        assertThat(json.at("/context/patientId").asString()).isEqualTo("patient-1");
        assertThat(json.at("/prefetch/patientBundle/entry/0/resource/id").asString()).isEqualTo("patient-1");
        assertThat(json.at("/context/patientId").asString()).isNotEqualTo("S123456789");
        assertThat(built.notes()).contains("The Patient and patientId were sent with the id patient-1, this tool's own: no vendor patient id "
                + "was given. Whether the service accepts a patient id its FHIR server does not hold is not known; give the vendor patient id "
                + "under Edit member fields to send that instead.");
    }

    @Test
    void dtrSendsThePatientUnderTheVendorPatientId() {
        DtrPackageRequest request = DtrPackageRequest.builder().member(member("PATIENT-PLACEHOLDER-1")).provider(PROVIDER)
                .procedureCodes(List.of(CodeInput.of("E0601"))).build();
        DtrRequestBuilder.Package built = new DtrRequestBuilder(null).buildPackage(request, ResolvedText::plain);
        assertThat(built.json()).contains("\"Patient/PATIENT-PLACEHOLDER-1\"").doesNotContain("patient-1\"");
        assertThat(built.notes()).contains("The Patient was sent with the id PATIENT-PLACEHOLDER-1, the vendor patient id given.");
    }

    @Test
    void theCharactersOfAVendorPatientIdAreChecked() {
        assertThat(new RunInputs(CLOCK).member(member("PATIENT-PLACEHOLDER-1")).errors()).isEmpty();
        assertThat(new RunInputs(CLOCK).member(member("PATIENT 1")).errors()).containsExactly(new FieldError("member.vendorPatientId",
                "A vendor patient id holds letters, digits, - and . only, at most 64 characters. It is sent as a FHIR resource id, which "
                        + "allows nothing else."));
        assertThat(CaseResources.patientId(member("  PATIENT-PLACEHOLDER-1 "))).isEqualTo("PATIENT-PLACEHOLDER-1");
        assertThat(CaseResources.patientId(null)).isEqualTo("patient-1");
    }
}
