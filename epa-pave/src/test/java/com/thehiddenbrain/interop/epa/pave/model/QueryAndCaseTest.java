package com.thehiddenbrain.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryAndCaseTest {

    @Test
    void anUnsetFilterDoesNotFilter() {
        RunQuery all = RunQuery.all();
        assertThat(all.from()).isNull();
        assertThat(all.to()).isNull();
        assertThat(all.protocol()).isNull();
        assertThat(all.result()).isNull();
        assertThat(all.outcome()).isNull();
        assertThat(all.chainId()).isNull();
        assertThat(all.suiteRunId()).isNull();
        assertThat(all.correlationKey()).isNull();
        assertThat(all.correlationValue()).isNull();
        assertThat(all.limit()).isNull();
    }

    @Test
    void theBuilderCoversTheHistoryFiltersTogether() {
        RunQuery query = RunQuery.builder().from(LocalDate.of(2026, 1, 1)).to(LocalDate.of(2026, 1, 31)).protocol(Protocol.PAS)
                .result(TestResult.FAIL).outcome("DENIED").limit(50).build();
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(query.to()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(query.protocol()).isEqualTo(Protocol.PAS);
        assertThat(query.result()).isEqualTo(TestResult.FAIL);
        assertThat(query.outcome()).isEqualTo("DENIED");
        assertThat(query.limit()).isEqualTo(50);
    }

    @Test
    void theFactoriesSetOnlyWhatTheyName() {
        assertThat(RunQuery.forChain("chain-1").chainId()).isEqualTo("chain-1");
        assertThat(RunQuery.forChain("chain-1").suiteRunId()).isNull();
        assertThat(RunQuery.forSuite("suite-1").suiteRunId()).isEqualTo("suite-1");
        RunQuery correlated = RunQuery.correlated(RunRecord.CORR_SUBSCRIPTION_ID, "sub-1");
        assertThat(correlated.correlationKey()).isEqualTo("subscriptionId");
        assertThat(correlated.correlationValue()).isEqualTo("sub-1");
        assertThat(RunQuery.between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)).to()).isEqualTo(LocalDate.of(2026, 1, 2));
    }

    @Test
    void aCaseCarriesIdentifiersOnlyAndItsCollectionsAreNeverNull() {
        CaseSpec bare = CaseSpec.builder().caseId("c1").operation(EpaOperation.CRD_ORDER_SIGN).build();
        assertThat(bare.procedureCodes()).isEmpty();
        assertThat(bare.correlation()).isEmpty();
        assertThat(bare.chain()).isFalse();

        CaseSpec inquiry = CaseSpec.builder().caseId("c2").operation(EpaOperation.PAS_INQUIRE).memberId("S123456789")
                .npi("1234567893").procedureCodes(List.of("95810")).correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim-1")).build();
        assertThat(inquiry.correlation()).containsEntry("claimId", "claim-1");
        // There is no component for a name or a date of birth: those are resolved again when the case runs.
        assertThat(java.util.Arrays.stream(CaseSpec.class.getRecordComponents()).map(c -> c.getName().toLowerCase()))
                .noneMatch(name -> name.contains("name") || name.contains("birth") || name.contains("dob") || name.contains("address"));
    }

    @Test
    void aCaseOutcomeNamesItsFinalRun() {
        RunRecord first = RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome("AUTH_REQUIRED").result(TestResult.UNVERIFIED).build();
        RunRecord last = first.toBuilder().runId("run-2").protocol(Protocol.PAS).operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED").build();
        CaseSpec spec = CaseSpec.builder().caseId("c1").chain(true).build();
        assertThat(new CaseOutcome(spec, CaseStatus.COMPLETED, null, List.of(first, last)).finalRun().runId()).isEqualTo("run-2");
        assertThat(new CaseOutcome(spec, CaseStatus.NOT_CONFIGURED, "no url", null).finalRun()).isNull();
        assertThat(new CaseOutcome(spec, CaseStatus.BLOCKED, "locked", null).runs()).isEmpty();
    }
}
