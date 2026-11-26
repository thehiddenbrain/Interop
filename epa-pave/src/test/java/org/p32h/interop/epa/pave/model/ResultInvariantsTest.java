package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The parts of a result refuse to exist hollow. A verdict with no headline, a test result with no
 * result, or a receipt that claims a record it does not hold would each answer none of the
 * tester's four questions while looking as if it had.
 */
class ResultInvariantsTest {

    private static RunRecord record() {
        return RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome(CrdOutcome.NO_AUTH_REQUIRED).result(TestResult.UNVERIFIED).build();
    }

    @Test
    void aVerdictNeedsAHeadlineAnOutcomeAndATone() {
        assertThatThrownBy(() -> new VerdictView(null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerdictView(" ", "X", "x", Tone.CLEAR, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VerdictView("Headline.", "X", "x", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> VerdictView.of(CrdOutcome.NO_AUTH_REQUIRED, "Prior authorization is not required.", null)).doesNotThrowAnyException();
    }

    @Test
    void aTestResultNeedsBothSidesBeforeItCanPassOrFail() {
        assertThatThrownBy(() -> new ExpectationView(null, null, null, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        // A pass with nothing declared would be a pass against nothing.
        assertThatThrownBy(() -> new ExpectationView(null, null, "NO_AUTH_REQUIRED", "label", TestResult.PASS, "why"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpectationView(" ", null, "NO_AUTH_REQUIRED", "label", TestResult.FAIL, "why"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ExpectationView(null, null, "NO_AUTH_REQUIRED", "label", TestResult.UNVERIFIED, "why")).doesNotThrowAnyException();
        assertThatCode(() -> new ExpectationView("NO_AUTH_REQUIRED", "label", "NO_AUTH_REQUIRED", "label", TestResult.PASS, "why"))
                .doesNotThrowAnyException();
    }

    @Test
    void aNextStepNeedsAnActionAndAnInstruction() {
        assertThatThrownBy(() -> NextStepView.of(null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NextStepView.of(NextAction.REVIEW, " ", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReceiptAlwaysHoldsTheRecordAndSaysWhyWhenItWasNotWritten() {
        assertThatThrownBy(() -> new LedgerReceipt(true, null, null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerReceipt(false, record(), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new LedgerReceipt(false, record(), "This run was NOT recorded.", null)).doesNotThrowAnyException();
    }

    @Test
    void aLookupResultAlwaysSaysHowTheLookupEnded() {
        assertThatThrownBy(() -> new MemberLookupResult(null, MemberIds.forms("S123456789"), null, List.of(), List.of(), List.of(), null,
                null, "message")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anExchangeRefusesStatesThatCannotHaveHappened() {
        WireRequest request = new WireRequest("POST", "https://example.invalid/x", "https://example.invalid/x", List.of(), "{}", true, null);
        // Each row: status, body, parsed, parse error, failure type.
        Object[][] impossible = {
                {null, null, false, null, null},                                   // nothing said about failure
                {500, "{}", true, null, OnyxFailureType.NONE},                     // a 500 that is fine
                {200, "{", false, "not JSON", OnyxFailureType.NONE},               // unreadable and fine
                {200, "{}", true, null, OnyxFailureType.SERVER_ERROR},             // a server error with 200
                {503, "{}", true, null, OnyxFailureType.BAD_REQUEST},              // a 4xx kind with 503
                {200, "{}", true, null, OnyxFailureType.BAD_REQUEST},
                {400, "{", false, "not JSON", OnyxFailureType.MALFORMED_RESPONSE}, // a 400 has its own failure
                {200, "{}", true, null, OnyxFailureType.MALFORMED_RESPONSE},       // malformed yet parsed
                {200, "{}", true, "not JSON", OnyxFailureType.MALFORMED_RESPONSE},
                {200, null, false, null, OnyxFailureType.TIMEOUT},                 // no response, with a status
                {null, "{}", false, null, OnyxFailureType.UNAVAILABLE},            // no response, with a body
                {null, null, false, null, OnyxFailureType.BAD_REQUEST}};           // a response kind with no status
        for (Object[] row : impossible) {
            Integer status = (Integer) row[0];
            String body = (String) row[1];
            assertThatThrownBy(() -> new VendorExchange(request, status, List.of(), body, null,
                    (Boolean) row[2] ? JsonSafe.parse("{}").node() : null, (String) row[3], 5, (OnyxFailureType) row[4], null, Set.of(), null,
                    null, List.of())).as(status + " " + row[4] + " " + row[3]).isInstanceOf(IllegalArgumentException.class);
        }
        // A success with no body is free of failure when the operation may answer with none.
        VendorExchange empty = new VendorExchange(request, 204, List.of(), null, null, null, null, 5, OnyxFailureType.NONE, null, Set.of(),
                null, null, List.of());
        assertThat(empty.ok()).isTrue();
        assertThat(empty.json()).isNull();
        assertThat(empty.wireResponse().byteLength()).isZero();
    }

    @Test
    void theByteCountIsWhatArrivedAndNotWhatTheDecodedTextWouldEncodeTo() {
        WireRequest request = new WireRequest("POST", "https://example.invalid/x", "https://example.invalid/x", List.of(), "{}", true, null);
        VendorExchange given = new VendorExchange(request, 200, List.of(), "{}", 15L, JsonSafe.parse("{}").node(), null, 5,
                OnyxFailureType.NONE, null, Set.of(), null, null, List.of());
        assertThat(given.wireResponse().byteLength()).isEqualTo(15L);
        assertThat(given.withSignals(Set.of()).withScenario("s", "how").withElapsedMillis(9).responseBytes()).isEqualTo(15L);
        assertThat(new VendorExchange(request, null, List.of(), null, 7L, null, null, 5, OnyxFailureType.TIMEOUT, null, Set.of(), null,
                null, List.of()).wireResponse().byteLength()).isNull();
    }

    @Test
    void aWireHeaderAndAVariableUseMaskThemselvesWhoeverBuildsThem() {
        assertThat(new WireHeader("Authorization", "Bearer placeholder-access-token").value()).isEqualTo("Bearer [redacted]");
        assertThat(new WireHeader("authorization", "placeholderrawtoken extra").value()).isEqualTo("[redacted]");
        assertThat(new WireHeader("Cookie", "JSESSIONID=PLACEHOLDERSESSION; route=abc").value()).isEqualTo("[redacted]");
        assertThat(new WireHeader("X-Api-Key", "placeholder key with spaces").value()).isEqualTo("[redacted]");
        assertThat(new WireHeader("Set-Cookie", "session=placeholder").value()).isEqualTo("[redacted]");
        // Already redacted, or not a credential: unchanged.
        assertThat(new WireHeader("Authorization", "Basic [redacted]").value()).isEqualTo("Basic [redacted]");
        assertThat(new WireHeader("Content-Type", "application/json").value()).isEqualTo("application/json");
        assertThat(new WireHeader("Authorization", null).value()).isNull();

        VariableUse secret = new VariableUse("clientSecret", VariableSource.ENVIRONMENT, true, "placeholder-secret-value");
        assertThat(secret.display()).isEqualTo("[secret clientSecret]");
        assertThat(JsonSafe.mapper().writeValueAsString(secret)).doesNotContain("placeholder-secret-value");
        assertThat(new VariableUse("baseUrl", VariableSource.ENVIRONMENT, false, "https://onyx.example.invalid").display())
                .isEqualTo("https://onyx.example.invalid");
        assertThat(new VariableUse("clientSecret", VariableSource.UNRESOLVED, true, null).display()).isNull();
    }

    @Test
    void aHeaderSaysWhenItsValueCouldNotBeResolved() {
        ResolvedHeader resolved = new ResolvedHeader("Accept", "application/json", "application/json");
        assertThat(resolved.resolved()).isTrue();
        assertThat(resolved.unresolved()).isEmpty();
        ResolvedHeader missing = ResolvedHeader.unresolved("X-Api-Key", List.of("apiKey"));
        assertThat(missing.resolved()).isFalse();
        assertThat(missing.value()).isNull();
        assertThat(JsonSafe.mapper().valueToTree(missing).propertyNames()).containsExactlyInAnyOrder("name", "unresolved");
        assertThat(new ResolvedHeader("X-Api-Key", null, null).resolved()).isFalse();
        assertThat(Map.of("k", resolved).toString()).doesNotContain("application/json");
    }
}
