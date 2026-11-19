package com.thehiddenbrain.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A record's default toString prints every component. For a type that holds member data or wire
 * JSON that would put it in a log the first time somebody logged the object, so those types
 * override toString. These tests fail if an override is removed.
 */
class RedactionTest {

    private static final String SURNAME = "Placeholder-Surname";
    private static final String MEMBER_ID = "S123456789";
    private static final String BODY = "{\"name\":\"" + SURNAME + "\"}";

    @Test
    void memberTypesDoNotPrintMemberData() {
        PostalAddress address = new PostalAddress("1 Placeholder Street", null, "Sampletown", "MA", "00000");
        MemberDetail member = MemberDetail.builder().memberId(MEMBER_ID).firstName("Given").lastName(SURNAME)
                .dateOfBirth("1970-01-01").address(address).subscriberId("SUB-1").build();
        assertThat(member.toString()).doesNotContain(SURNAME).doesNotContain(MEMBER_ID).doesNotContain("1970").doesNotContain("SUB-1");
        assertThat(address.toString()).doesNotContain("Placeholder Street").doesNotContain("Sampletown");
        assertThat(new MemberIdForms(MEMBER_ID, MEMBER_ID, false).toString()).doesNotContain(MEMBER_ID);
        MemberLookupResult result = new MemberLookupResult(LookupStatus.FOUND, MemberIds.forms(MEMBER_ID), member, List.of(),
                List.of(), List.of(), null, null, "found");
        assertThat(result.toString()).doesNotContain(SURNAME).doesNotContain(MEMBER_ID);
        assertThat(CaseSpec.builder().memberId(MEMBER_ID).caseId("c1").build().toString()).doesNotContain(MEMBER_ID);
    }

    @Test
    void wireTypesDoNotPrintBodies() {
        WireRequest request = new WireRequest("POST", "https://example.invalid/x", "{{base}}/x", List.of(), BODY, true, null);
        WireResponse response = new WireResponse(200, List.of(), BODY, 10L, true, null);
        WireView wire = new WireView(request, response, MemberIds.forms(MEMBER_ID));
        VendorExchange exchange = new VendorExchange(request, 200, List.of(), BODY, JsonSafe.parse(BODY).node(), null, 5,
                OnyxFailureType.NONE, null, Set.of());
        for (Object value : new Object[] {request, response, wire, exchange}) {
            assertThat(value.toString()).as(value.getClass().getSimpleName()).doesNotContain(SURNAME).doesNotContain(MEMBER_ID);
        }
    }

    @Test
    void resolvedTypesPrintOnlyTheMaskedForm() {
        ResolvedText text = new ResolvedText("{{clientSecret}}", "[secret clientSecret]", "hunter2-value",
                List.of(new VariableUse("clientSecret", VariableSource.ENVIRONMENT, true, "[secret clientSecret]")), List.of());
        ResolvedUrl url = new ResolvedUrl("{{base}}/x?key={{apiKey}}", "https://example.invalid/x?key=[secret apiKey]",
                "https://example.invalid/x?key=hunter2-value", List.of(), List.of(), "test");
        ResolvedHeader header = new ResolvedHeader("X-Api-Key", "[secret apiKey]", "hunter2-value");
        for (Object value : new Object[] {text, url, header}) {
            assertThat(value.toString()).as(value.getClass().getSimpleName()).doesNotContain("hunter2-value");
            assertThat(JsonSafe.mapper().writeValueAsString(value)).as(value.getClass().getSimpleName() + " as JSON")
                    .doesNotContain("hunter2-value");
        }
    }

    @Test
    void anEnvelopeDoesNotPrintItsDetailOrWire() {
        RunRecord record = RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome(CrdOutcome.NO_AUTH_REQUIRED).result(TestResult.UNVERIFIED).build();
        RunEnvelope<String> envelope = RunEnvelope.<String>builder().runId("run-1").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN)
                .verdict(VerdictView.of(CrdOutcome.NO_AUTH_REQUIRED, "Prior authorization is not required.", List.of()))
                .expectation(Expectations.assess(null, CrdOutcome.NO_AUTH_REQUIRED, null, CrdOutcome.values()))
                .nextStep(NextStepView.of(NextAction.FLOW_ENDS, "The flow ends here.", "No authorization is needed."))
                .ledger(new LedgerReceipt(true, record, null, 1)).detail(BODY).build();
        assertThat(envelope.toString()).doesNotContain(SURNAME);
    }
}
