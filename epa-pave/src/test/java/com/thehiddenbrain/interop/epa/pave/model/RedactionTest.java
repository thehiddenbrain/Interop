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
                OnyxFailureType.NONE, null, Set.of(), null);
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
    void aLookupUrlThatHoldsTheMemberIdIsNotPrinted() {
        // The wire view has to show the url that was sent, member ID included. Nothing else may.
        ResolvedUrl url = new ResolvedUrl("https://example.invalid/members/{id}", "https://example.invalid/members/" + MEMBER_ID,
                "https://example.invalid/members/" + MEMBER_ID, List.of(), List.of(), "lookup.member.url");
        assertThat(url.toString()).doesNotContain(MEMBER_ID).contains("{id}");
        com.thehiddenbrain.interop.epa.pave.service.config.PreparedCall call =
                com.thehiddenbrain.interop.epa.pave.service.config.TestPreparedCalls.with(EpaOperation.MEMBER_LOOKUP, "GET", url,
                        List.of(new ResolvedHeader("X-Api-Key", "[secret memberApiKey]", "hunter2-value")), EnvironmentRef.none(), null, false);
        assertThat(call.toString()).doesNotContain(MEMBER_ID).doesNotContain("hunter2-value");
        com.thehiddenbrain.interop.epa.pave.service.config.CallIntent intent =
                com.thehiddenbrain.interop.epa.pave.service.config.CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", MEMBER_ID);
        assertThat(intent.toString()).doesNotContain(MEMBER_ID);
        assertThat(intent.urlValues()).containsEntry("id", MEMBER_ID);
    }

    @Test
    void viewValuesAreNotPrinted() {
        // Every view type is built from Shown, so redacting it there covers all of them.
        assertThat(Shown.of(SURNAME).toString()).isEqualTo("Shown[VALUE]");
        assertThat(new LookupField("lastName", "Last name", Shown.of(SURNAME), "name.family").toString()).doesNotContain(SURNAME);
        assertThat(new AdditionalField("member.nickname", Presence.VALUE, "\"" + SURNAME + "\"").toString()).doesNotContain(SURNAME);
        assertThat(new VariableUse("memberId", VariableSource.RUN_VALUE, false, MEMBER_ID).toString()).doesNotContain(MEMBER_ID);
        assertThat(new IssueView(Shown.of("error"), Shown.of("not-found"), Shown.of(SURNAME + " not found"), Shown.absent(),
                List.of(), List.of(), List.of()).toString()).doesNotContain(SURNAME);
        assertThat(RunQuery.correlated(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001").toString()).doesNotContain("PA-PLACEHOLDER-0001");
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
