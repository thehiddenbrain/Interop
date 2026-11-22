package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.service.RunStamps;
import tools.jackson.databind.JsonNode;

/**
 * What the browser can rely on in the JSON it is sent, and what it may post. The frontend is
 * written against these shapes without seeing the Java, so each fact here is a promise.
 */
class BrowserContractTest {

    private static JsonNode json(Object value) {
        return JsonSafe.mapper().valueToTree(value);
    }

    @Test
    void noEnvironmentIsAFlagAndNeverAnEmptyObjectToBeInterpreted() {
        JsonNode none = json(EnvironmentRef.none());
        assertThat(none.get("active").asBoolean()).isFalse();
        assertThat(none.has("name")).isFalse();
        JsonNode active = json(new EnvironmentRef("Onyx Sandbox", EnvironmentKind.SANDBOX));
        assertThat(active.get("active").asBoolean()).isTrue();
        assertThat(active.get("name").asString()).isEqualTo("Onyx Sandbox");
        assertThat(active.get("kind").asString()).isEqualTo("sandbox");
        // Nothing else is derived onto the wire, and the flag can be read back without complaint.
        assertThat(active.propertyNames()).containsExactlyInAnyOrder("name", "kind", "active");
        assertThat(JsonSafe.mapper().readValue(active.toString(), EnvironmentRef.class))
                .isEqualTo(new EnvironmentRef("Onyx Sandbox", EnvironmentKind.SANDBOX));
    }

    @Test
    void aNullIsLeftOutOfWhatTheBrowserIsSent() {
        assertThat(json(ResolvedUrl.notConfigured("not configured")).has("display")).isFalse();
        assertThat(json(new ResolvedText("{{x}}", null, null, List.of(), List.of("x"))).has("display")).isFalse();
        assertThat(json(NextStepView.of(NextAction.REVIEW, "Next.", "Because.")).propertyNames())
                .containsExactly("action", "headline", "reason", "carry");
        assertThat(json(CaseSpec.builder().caseId("c1").build()).has("memberId")).isFalse();
        assertThat(json(new ScenarioList(false, null, null, null, null, null)).has("reason")).isFalse();
    }

    @Test
    void anOutcomeCarriesItsLabelAndToneSoTheBrowserKeepsNoCopyOfTheEnums() {
        List<OutcomeOption> options = OutcomeOption.listOf(CrdOutcome.values(), RunOutcome.values());
        assertThat(options).hasSize(CrdOutcome.values().length + RunOutcome.values().length);
        assertThat(options).contains(new OutcomeOption("NO_AUTH_REQUIRED", "Prior authorization is not required", "PA not required", Tone.CLEAR, false),
                new OutcomeOption("BLOCKED", "Blocked: nothing was sent", "Blocked", Tone.BLOCKED, false),
                new OutcomeOption("TOOL_ERROR", "This tool failed after the call was made", "Tool error", Tone.FAULT, true));
        JsonNode option = json(OutcomeOption.of(CrdOutcome.NO_DETERMINATION));
        assertThat(option.propertyNames()).containsExactly("code", "label", "short", "tone", "fault");
    }

    @Test
    void anEntryInTheReturnedFieldsSaysWhatKindOfEntryItIs() {
        assertThat(json(new AdditionalField("cards[0].x", Presence.VALUE, "1")).get("kind").asString()).isEqualTo("FIELD");
        assertThat(json(AdditionalField.unparsedBody("<html>")).get("kind").asString()).isEqualTo("RAW_BODY");
        assertThat(json(AdditionalField.bodyNote("The body repeats a key.")).get("kind").asString()).isEqualTo("NOTE");
    }

    @Test
    void theLedgerReceiptAlwaysCarriesTheRecord() {
        RunRecord record = RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome(CrdOutcome.NO_AUTH_REQUIRED).result(TestResult.UNVERIFIED)
                .nextAction(NextAction.FLOW_ENDS).build();
        JsonNode kept = json(new LedgerReceipt(true, record, null, 3));
        assertThat(kept.get("record").get("runId").asString()).isEqualTo("run-1");
        assertThat(kept.get("record").get("nextAction").asString()).isEqualTo("FLOW_ENDS");
        assertThat(kept.get("runsToday").asInt()).isEqualTo(3);
        JsonNode notKept = json(new LedgerReceipt(false, record, "This run was NOT recorded.", null));
        assertThat(notKept.get("recorded").asBoolean()).isFalse();
        assertThat(notKept.get("record").get("runId").asString()).isEqualTo("run-1");
        // "The ledger cannot say" is left out. It is not written as zero.
        assertThat(notKept.has("runsToday")).isFalse();
        // A line written before the next step was recorded is still readable.
        assertThat(JsonSafe.mapper().readValue(JsonSafe.mapper().writeValueAsString(record).replace(",\"nextAction\":\"FLOW_ENDS\"", ""),
                RunRecord.class).nextAction()).isNull();
    }

    @Test
    void aSuiteRowBindsWithoutEveryFieldAndKeepsWhatItDidNotUnderstand() {
        CaseSpec minimal = JsonSafe.mapper().readValue("{\"caseId\":\"c1\",\"memberId\":\"S123456789\",\"operation\":\"CRD_ORDER_SIGN\"}",
                CaseSpec.class);
        // A row that leaves chain out is not a chain. It must not fail to bind.
        assertThat(minimal.chain()).isFalse();
        assertThat(minimal.unrecognised()).isEmpty();
        CaseSpec misspelt = JsonSafe.mapper().readValue("{\"caseId\":\"c1\",\"chain\":true,\"expectd\":\"NO_AUTH_REQUIRED\"}", CaseSpec.class);
        assertThat(misspelt.chain()).isTrue();
        assertThat(misspelt.expected()).isNull();
        assertThat(misspelt.unrecognised()).isEqualTo(Map.of("expectd", "NO_AUTH_REQUIRED"));
        assertThat(CaseSpec.builder().caseId("c1").configEpoch(4L).build().configEpoch()).isEqualTo(4L);
    }

    @Test
    void aCaseOutcomeSaysWhichRunsTheLedgerDidNotKeep() {
        CaseSpec spec = CaseSpec.builder().caseId("c1").build();
        assertThat(new CaseOutcome(spec, CaseStatus.COMPLETED, null, List.of()).notRecorded()).isEmpty();
        CaseOutcome unkept = new CaseOutcome(spec, CaseStatus.COMPLETED, null, List.of(), List.of("This run was NOT recorded."));
        assertThat(json(unkept).get("notRecorded").get(0).asString()).isEqualTo("This run was NOT recorded.");
    }

    @Test
    void aChainIdIsOnlyEverOneThisToolMinted() {
        assertThat(RunStamps.isChainId(RunStamps.newChainId())).isTrue();
        for (String other : new String[] {null, "", "new", "chain-", "chain-1", "chain-not-a-uuid", "run-00000000-0000-4000-8000-000000000001",
                "chain-00000000-0000-4000-8000-000000000001 and a note", "CHAIN-00000000-0000-4000-8000-000000000001"}) {
            assertThat(RunStamps.isChainId(other)).as("[" + other + "]").isFalse();
        }
    }
}
