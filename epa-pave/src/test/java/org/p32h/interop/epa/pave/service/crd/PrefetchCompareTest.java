package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.compare.RequestComparison;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;

/**
 * "Compare with a request that works" names a difference of arrangement as one (D-309): a prefetch
 * key only in the pasted request, only in PAVE's, or holding another kind of thing in each, by key
 * and kind and never by value. On the first run the operator compared the two bodies side by side
 * and read many unrelated differences where there was one: the prefetch arranged another way.
 */
class PrefetchCompareTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private final FakeGateway gateway = new FakeGateway();

    private RequestComparison compare(String pasted) {
        CrdRunRequest form = CrdRunRequest.builder().hook("order-sign").rawBody(pasted)
                .member(MemberDetail.builder().memberId("S123456789").build())
                .provider(ProviderDetail.builder().npi("1234567893").lastName("Provider").build())
                .procedureCodes(List.of(CodeInput.of("E0601"))).dateOfService("2026-03-20").build();
        return new CrdRequestComparer(new CrdRequestBuilder(null, CLOCK)).compare(form, template -> gateway.resolve(template, Map.of()));
    }

    @Test
    void theSamplesKeysAgainstTheNamedBundlesAreOneDifferenceOfArrangementKeyByKey() {
        RequestComparison comparison = compare("""
                {"hook": "order-sign", "hookInstance": "x", "context": {"patientId": "sample-pat"},
                 "prefetch": {"patient": {"resourceType": "Patient", "id": "sample-pat", "name": [{"family": "SampleFamily"}]},
                              "coverage": {"resourceType": "Bundle", "type": "searchset", "entry": [{"resource": {"resourceType": "Coverage"}}]},
                              "practitionerBundle": {"resourceType": "Practitioner"}}}
                """);

        assertThat(comparison.arrangement()).containsExactly(
                "The prefetch key \"patient\" is only in your request, holding a Patient.",
                "The prefetch key \"coverage\" is only in your request, holding a Bundle of type searchset holding Coverage.",
                "The prefetch key \"practitionerBundle\" holds a Practitioner in your request and a Bundle of type collection holding "
                        + "Practitioner in the tool's.",
                "The prefetch key \"patientBundle\" is only in the tool's request, holding a Bundle of type collection holding Patient.",
                "The prefetch key \"coverageBundle\" is only in the tool's request, holding a Bundle of type collection holding Coverage.",
                "The prefetch key \"practitionerRoleBundle\" is only in the tool's request, holding a Bundle of type collection holding "
                        + "PractitionerRole.",
                "The prefetch key \"serviceRequestBundle\" is only in the tool's request, holding a Bundle of type collection holding "
                        + "ServiceRequest.",
                "These are differences of arrangement: CDS Hooks fixes no names for the prefetch, and a service reads the keys it names. "
                        + "Prefetch As the collection's sample, in Configuration > Environments > Edit > Request shapes, sends the keys of "
                        + "the collection's coverage-check sample, each built from the case.");
        assertThat(String.join(" ", comparison.arrangement())).doesNotContain("SampleFamily").doesNotContain("sample-pat");
    }

    @Test
    void twoPrefetchesArrangedAlikeOrNoPrefetchSayNothingOfArrangement() {
        RequestComparison none = compare("{\"hook\": \"order-sign\", \"context\": {\"patientId\": \"sample-pat\"}}");
        assertThat(none.arrangement()).isNull();
        String alike = "{\"prefetch\": {\"patientBundle\": {\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": "
                + "[{\"resource\": {\"resourceType\": \"Patient\"}}]}}}";
        assertThat(CrdRequestComparer.arrangement(alike, alike)).isEmpty();
        assertThat(CrdRequestComparer.arrangement(alike, "not json")).isEmpty();
    }
}
