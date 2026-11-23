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
 * "Compare with a request that works" names, per resource type, what the pasted request's resource
 * carries and the tool's does not (D-310), as a coverage check in the sample's arrangement says it
 * in its notes. The element by element list gives each by path; the operator had to gather them by
 * hand. Names only: the pasted values are placeholders that no line may carry.
 */
class SampleElementsCompareTest {

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
    void eachResourceTypeBothCarryNamesWhatYoursHasAndTheToolsDoesNot() {
        RequestComparison comparison = compare(SampleElementsTest.SAMPLE);

        assertThat(comparison.elements()).containsExactly(
                "Your request's ServiceRequest carries reasonCode and performer, which the tool's ServiceRequest does not.",
                "Your request's Patient carries meta, extension, active, telecom, address and communication, which the tool's Patient "
                        + "does not.",
                // The plan is read out of your request into the case, so the tool's Coverage carries that class and only group is named.
                "Your request's Coverage carries relationship and class of type group, which the tool's Coverage does not.");
        String all = String.join(" ", comparison.elements());
        for (String value : List.of("SAMPLE-DX-1", "sample-prac", "Sample Street", "555-0100", "GROUP-PLACEHOLDER", "self-sample")) {
            assertThat(all).doesNotContain(value);
        }
    }

    @Test
    void aRequestTheToolsResourcesCoverSaysNothing() {
        RequestComparison comparison = compare("{\"hook\": \"order-sign\", \"context\": {\"patientId\": \"sample-pat\"}, \"prefetch\": "
                + "{\"patient\": {\"resourceType\": \"Patient\", \"id\": \"sample-pat\"}}}");

        assertThat(comparison.elements()).isNull();
    }
}
