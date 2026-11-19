package org.p32h.interop.epa.pave.service.dtr;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import tools.jackson.databind.JsonNode;

/** What the DTR tests share: a fixed clock, a synthetic member and provider, and exchanges read the way the DTR clients read them. */
final class DtrFixtures {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    static final String SURNAME = "Placeholder-Surname";
    static final String BIRTH = "1970-01-31";
    static final String NPI = "1234567893";

    private DtrFixtures() {
    }

    static MemberDetail member(String memberId) {
        return MemberDetail.builder().memberId(memberId).firstName("Given").lastName(SURNAME).dateOfBirth(BIRTH).gender("female")
                .lineOfBusiness("Medicare").subscriberId("SUB-PLACEHOLDER-1").build();
    }

    static ProviderDetail provider() {
        return ProviderDetail.builder().npi(NPI).firstName("Pat").lastName("Provider").build();
    }

    static DtrPackageRequest.Builder packageRequest(String memberId) {
        return DtrPackageRequest.builder().member(member(memberId)).provider(provider())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).dateOfService("2026-03-20");
    }

    /** A response to a DTR operation, with the vendor signals applied as the DTR clients apply them. */
    static VendorExchange answered(EpaOperation operation, int status, String body) {
        VendorExchange exchange = TestExchanges.of(operation, status, body, true);
        return exchange.withSignals(OnyxDtrConventions.signals(exchange));
    }

    static JsonNode json(String text) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        if (!parsed.ok()) {
            throw new AssertionError("Not JSON: " + parsed.error());
        }
        return parsed.node();
    }

    /** The answers a scenario file lists, each as the request would carry it. */
    static List<DtrAnswerInput> answers(JsonNode list) {
        List<DtrAnswerInput> out = new ArrayList<>();
        for (JsonNode answer : JsonSafe.elements(list)) {
            out.add(new DtrAnswerInput(JsonSafe.text(answer, "linkId"), JsonSafe.elements(answer, "values")));
        }
        return out;
    }

    /** The text of each element of a list of strings. */
    static List<String> texts(JsonNode list) {
        return JsonSafe.elements(list).stream().map(JsonSafe::text).toList();
    }
}
