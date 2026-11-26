package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.StartupChecks;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;

/**
 * Submit sends on the wire what it sent before the coverage check and the questionnaire request
 * took the diagnosis and the servicing provider (D-321): the diagnosis on the Claim, the servicing
 * provider on the care team, and the order with neither reasonCode nor performer. Each body is held
 * whole against the one the commit before that change built for the same case.
 */
class SubmitUnchangedTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T09:00:00Z"), ZoneId.of("UTC"));
    private static final String CLAIM = "claim-00000000-0000-4000-8000-000000000001";
    private static final String BUNDLE = "urn:uuid:00000000-0000-4000-8000-0000000000b1";

    private static Function<String, ResolvedText> variables(Map<String, String> values) {
        return template -> {
            String name = template.substring(2, template.length() - 2);
            String value = values.get(name);
            return value == null ? new ResolvedText(template, null, null, List.of(), List.of(name)) : ResolvedText.plain(value);
        };
    }

    private static PasRequestBuilder builder(PasGuide guide) {
        AtomicInteger next = new AtomicInteger();
        return new PasRequestBuilder(new FakeCodeList(), StartupChecks.ATTACHMENT_FHIR, CLOCK,
                () -> UUID.fromString(String.format("00000000-0000-4000-8000-%012d", next.incrementAndGet()))).under(guide);
    }

    private static PasSubmitRequest request(ProviderDetail servicing) {
        return PasSubmitRequest.builder()
                .member(MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname")
                        .dateOfBirth("1970-01-31").gender("female").lineOfBusiness("Medicare").coverageStatus("active").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").vendorPractitionerId("prac-001")
                        .build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"), CodeInput.of("E0601")))
                .diagnosisCodes(List.of(CodeInput.of("G47.33"), new CodeInput("E66.01", "urn:placeholder:dx", null), CodeInput.of("12345")))
                .dateOfService("2026-03-20")
                .placeOfService("11")
                .servicingProvider(servicing)
                .build();
    }

    private static final Map<String, ProviderDetail> SERVICING = Map.of(
            "person", ProviderDetail.builder().npi("9999999995").firstName("Sam").lastName("Servicing").phone("555-0100").build(),
            "organization", ProviderDetail.builder().npi("9999999995").organizationName("Placeholder Sleep Laboratory").build(),
            "shared", ProviderDetail.builder().vendorPractitionerId("prac-001").lastName("Provider").build());

    private static String expected(String name) throws IOException {
        try (InputStream in = SubmitUnchangedTest.class.getResourceAsStream("/pas/unchanged/" + name + ".json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void eachSubmitBodyIsTheOneTheCommitBeforeBuilt() throws IOException {
        Map<String, String> values = Map.of("payerName", "Placeholder Health Plan", "payerIdentifier", "PAYER-001");
        for (PasGuide guide : List.of(PasGuide.V201, PasGuide.V221)) {
            for (String kind : List.of("person", "organization", "shared")) {
                String name = kind + "-" + (guide.is221() ? "221" : "201");
                String body = builder(guide).submit(request(SERVICING.get(kind)), CLAIM, true, BUNDLE, variables(values)).json();
                // Recorded on e3d0ad8, the commit this work started from.
                assertThat(body).as(name).isEqualTo(expected(name));
            }
        }
    }
}
