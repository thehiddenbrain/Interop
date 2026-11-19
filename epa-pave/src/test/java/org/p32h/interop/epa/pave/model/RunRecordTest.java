package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RunRecordTest {

    private static RunRecord.Builder minimal() {
        return RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome(CrdOutcome.NO_AUTH_REQUIRED).result(TestResult.UNVERIFIED);
    }

    @Test
    void theRecordHasNowhereToPutMemberDataOrWireJson() {
        // The ledger stays free of member data because the record has no component that could hold any.
        List<String> forbidden = List.of("firstname", "lastname", "name", "dateofbirth", "dob", "birth", "address", "gender",
                "subscriber", "request", "response", "body", "wire", "json", "payload");
        for (RecordComponent component : RunRecord.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            for (String word : forbidden) {
                assertThat(name).as("component " + component.getName()).doesNotContain(word);
            }
        }
    }

    @Test
    void theAlwaysFieldsAreWrittenEvenWhenNull() {
        JsonNode json = JsonSafe.parse(JsonSafe.mapper().writeValueAsString(minimal().build())).node();
        for (String field : List.of("runId", "timestamp", "protocol", "target", "profile", "environment", "environmentKind",
                "scenarioId", "procedureCodes", "lineOfBusiness", "outcome", "coverageCodes", "delegatedVendor", "httpStatus",
                "elapsedMillis", "errorType", "expected", "result", "note", "testerRef")) {
            assertThat(json.has(field)).as(field + " is always written").isTrue();
        }
        assertThat(json.get("recordType").asString()).isEqualTo("run");
        assertThat(json.get("result").asString()).isEqualTo("unverified");
        assertThat(json.get("errorType").asString()).isEqualTo("NONE");
        assertThat(json.get("scenarioId").isNull()).isTrue();
    }

    @Test
    void theConditionalFieldsAreWrittenOnlyWhenPresent() {
        JsonNode bare = JsonSafe.parse(JsonSafe.mapper().writeValueAsString(minimal().build())).node();
        for (String field : List.of("memberId", "memberToken", "memberIdLast4", "npi", "dateOfService", "chainId", "suiteRunId",
                "caseId", "replayOfRunId", "hook", "targetSource", "correlation", "findings", "unrecordedInputs", "slow")) {
            assertThat(bare.has(field)).as(field + " is omitted when not set").isFalse();
        }
        JsonNode full = JsonSafe.parse(JsonSafe.mapper().writeValueAsString(minimal().memberId("S123456789").npi("1234567893")
                .dateOfService("2026-02-01").chainId("chain-1").hook("order-sign").slow(true)
                .correlation(Map.of(RunRecord.CORR_CLAIM_ID, "c1")).findings(List.of("SLOW_RESPONSE"))
                .unrecordedInputs(List.of(RunRecord.INPUT_BODY, RunRecord.INPUT_MEMBER_DETAILS)).build())).node();
        assertThat(full.get("memberId").asString()).isEqualTo("S123456789");
        // The record names what it could not keep. It holds the names only, never the values.
        assertThat(full.get("unrecordedInputs").toString()).isEqualTo("[\"body\",\"member-details\"]");
        assertThat(full.get("correlation").get("claimId").asString()).isEqualTo("c1");
        assertThat(full.get("slow").asBoolean()).isTrue();
    }

    @Test
    void fieldsAreWrittenInTheDeclaredOrder() {
        String line = JsonSafe.mapper().writeValueAsString(minimal().chainId("chain-1").build());
        List<String> names = JsonSafe.properties(JsonSafe.parse(line).node()).stream().map(Map.Entry::getKey).toList();
        assertThat(names.subList(0, 9)).containsExactly("recordType", "runId", "timestamp", "protocol", "operation", "target",
                "profile", "environment", "environmentKind");
        assertThat(names.indexOf("outcome")).isLessThan(names.indexOf("result"));
        assertThat(names.indexOf("testerRef")).isLessThan(names.indexOf("chainId"));
    }

    @Test
    void aLineIsOneLineAndRoundTrips() {
        RunRecord record = minimal().target("onyx").profile("mock").environment("Onyx Sandbox", EnvironmentKind.SANDBOX)
                .scenarioId("crd-02").procedureCodes(List.of("95810")).lineOfBusiness("Medicare")
                .coverageCodes(List.of("covered=covered", "pa-needed=no-auth")).httpStatus(200).elapsedMillis(42)
                .expected("NO_AUTH_REQUIRED").result(TestResult.PASS).note("line one\nline two").testerRef("QA-17").build();
        String line = JsonSafe.mapper().writeValueAsString(record);
        assertThat(line).doesNotContain("\n").doesNotContain("\r");
        RunRecord back = JsonSafe.mapper().readValue(line, RunRecord.class);
        assertThat(back).isEqualTo(record);
        assertThat(back.note()).isEqualTo("line one\nline two");
    }

    @Test
    void aLineWrittenByANewerVersionStillReads() {
        String line = "{\"recordType\":\"run\",\"runId\":\"run-9\",\"timestamp\":\"2026-01-05T10:15:30.000-05:00\","
                + "\"protocol\":\"PAS\",\"operation\":\"PAS_SUBMIT\",\"outcome\":\"APPROVED\",\"result\":\"pass\","
                + "\"elapsedMillis\":5,\"addedLater\":{\"x\":1}}";
        RunRecord record = JsonSafe.mapper().readValue(line, RunRecord.class);
        assertThat(record.runId()).isEqualTo("run-9");
        assertThat(record.procedureCodes()).isEmpty();
        assertThat(record.correlation()).isEmpty();
    }

    @Test
    void aRecordThatCouldNotBeTracedIsRefused() {
        assertThatThrownBy(() -> minimal().runId(" ").build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> minimal().timestamp(null).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> minimal().outcome((String) null).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> minimal().outcome("").build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> minimal().result(null).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> minimal().protocol(null).build()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toBuilderCopiesEveryComponent() {
        RunRecord record = minimal().target("onyx").profile("mock").environment("E", EnvironmentKind.UAT).scenarioId("s")
                .procedureCodes(List.of("1")).lineOfBusiness("Commercial").coverageCodes(List.of("a=b")).delegatedVendor("Evolent")
                .httpStatus(200).elapsedMillis(7).errorType(OnyxFailureType.TIMEOUT).expected("x").note("n").testerRef("t")
                .chainId("c").suiteRunId("su").caseId("ca").replayOfRunId("r").hook("order-sign").attachmentMode("x12-275").nextAction(NextAction.FLOW_ENDS)
                .targetSource("order-sign Happy Path").memberId("S123456789")
                .memberToken("tok").memberIdLast4("6789").npi("1234567893").dateOfService("2026-02-01")
                .correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim-1")).findings(List.of("SLOW_RESPONSE"))
                .unrecordedInputs(List.of(RunRecord.INPUT_RUN_VALUES)).slow(true).build();
        assertThat(record.toBuilder().build()).isEqualTo(record);
        // Guards the copy against a component being added to the record and forgotten in toBuilder.
        long nonNull = Arrays.stream(RunRecord.class.getRecordComponents()).filter(c -> {
            try {
                return c.getAccessor().invoke(record) != null;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }).count();
        assertThat(nonNull).isEqualTo(RunRecord.class.getRecordComponents().length);
    }
}
