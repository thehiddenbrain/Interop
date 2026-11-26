package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RunEnvelopeTest {

    private static RunEnvelope.Builder<String> complete() {
        RunRecord record = RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.PAS)
                .operation(EpaOperation.PAS_SUBMIT).outcome("DENIED").result(TestResult.PASS).build();
        return RunEnvelope.<String>builder().runId("run-1").protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT)
                .verdict(new VerdictView("The authorization was denied.", "DENIED", "Denied", Tone.STOP, List.of()))
                .expectation(new ExpectationView("DENIED", "Denied", "DENIED", "Denied", TestResult.PASS,
                        "The outcome was \"Denied\" as expected."))
                .nextStep(NextStepView.of(NextAction.FLOW_ENDS, "The flow ends here.", "The payer has decided."))
                .ledger(new LedgerReceipt(true, record, null, 1));
    }

    @Test
    void theAuthorizationOutcomeAndTheTestResultAreSeparateFacts() {
        // The case the whole screen is built around: a run expected to be denied, and denied, is a passed test.
        JsonNode json = JsonSafe.parse(JsonSafe.mapper().writeValueAsString(complete().build())).node();
        assertThat(json.at("/verdict/outcomeLabel").asString()).isEqualTo("Denied");
        assertThat(json.at("/expectation/result").asString()).isEqualTo("pass");
        assertThat(json.at("/verdict").has("result")).isFalse();
        assertThat(json.at("/expectation").has("tone")).isFalse();
    }

    @Test
    void anEnvelopeMissingAnyOfTheFourAnswersIsRefused() {
        assertThatThrownBy(() -> complete().verdict(null).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> complete().expectation(null).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> complete().nextStep(null).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> complete().ledger(null).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> complete().runId(" ").build()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void listsAreNeverNull() {
        RunEnvelope<String> envelope = complete().determination(null).findings(null).additionalFields(null).build();
        assertThat(envelope.determination()).isEmpty();
        assertThat(envelope.findings()).isEmpty();
        assertThat(envelope.additionalFields()).isEmpty();
    }

    @Test
    void slowIsDerivedFromTheThreshold() {
        assertThat(Timing.of(16000, 15000).slow()).isTrue();
        assertThat(Timing.of(15000, 15000).slow()).isFalse();
        assertThat(Timing.of(99999, 0).slow()).isFalse();
    }
}
