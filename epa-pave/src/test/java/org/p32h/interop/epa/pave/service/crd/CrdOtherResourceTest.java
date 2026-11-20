package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.crd.CrdResponseKind;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

/**
 * A FHIR resource of another kind sent in answer to a CRD hook is not a CDS Hooks response with
 * no cards. It is said to be what it says it is, and its outcome can never be taken for a service
 * that answered and returned no determination: a run or a suite row expecting that does not pass.
 */
class CrdOtherResourceTest {

    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"id\":\"zz-other\",\"name\":[{\"family\":\"Placeholder\"}]}";

    private final CrdResponseMapper mapper = new CrdResponseMapper();

    @Test
    void aPatientIsSaidToBeAPatientAndIsNotNoDetermination() {
        CrdReading reading = mapper.read(TestExchanges.crd(200, PATIENT));
        assertThat(reading.detail().responseKind()).isEqualTo(CrdResponseKind.OTHER_JSON);
        assertThat(reading.detail().responseNote()).isEqualTo("The body says it is a Patient resource.");
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.MALFORMED_RESPONSE).isNotEqualTo(CrdOutcome.NO_DETERMINATION);
        // Nothing of it is dropped: it is all among the returned fields.
        assertThat(reading.additionalFields()).extracting(AdditionalField::path)
                .contains("resourceType", "id", "name[0].family");
    }

    @Test
    void aRunExpectingNoDeterminationDoesNotPassOnIt() {
        CrdReading reading = mapper.read(TestExchanges.crd(200, PATIENT));
        assertThat(Expectations.assess("NO_DETERMINATION", reading.outcome(), reading.asked(), reading.answers(), CrdOutcome.values(),
                CrdCodes.EXPECTATION_ELEMENTS).result()).isEqualTo(TestResult.FAIL);
    }

    @Test
    void aCdsHooksResponseAndAnOperationOutcomeAreStillReadAsBefore() {
        assertThat(mapper.read(TestExchanges.crd(200, "{\"cards\":[]}")).outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(mapper.read(TestExchanges.crd(200, "{\"cards\":[]}")).detail().responseKind()).isEqualTo(CrdResponseKind.CDS_HOOKS);
        assertThat(mapper.read(TestExchanges.crd(400, "{\"resourceType\":\"OperationOutcome\",\"issue\":[]}")).detail().responseKind())
                .isEqualTo(CrdResponseKind.OPERATION_OUTCOME);
        // A resourceType that is blank names no resource, and the object is read as a CDS Hooks response.
        assertThat(mapper.read(TestExchanges.crd(200, "{\"resourceType\":\"\",\"cards\":[]}")).detail().responseKind())
                .isEqualTo(CrdResponseKind.CDS_HOOKS);
        assertThat(List.of(CrdOutcome.values())).contains(CrdOutcome.MALFORMED_RESPONSE);
    }

    @Test
    void theHeadlineSaysWhatCameAndNeverThatAReadableBodyCouldNotBeRead() {
        var patient = TestExchanges.crd(200, PATIENT);
        assertThat(CrdVerdicts.verdict(mapper.read(patient), patient).headline())
                .isEqualTo("The service answered with a FHIR Patient resource, not a CDS Hooks response (HTTP 200).");
        var array = TestExchanges.crd(200, "[{\"cards\":[]}]");
        assertThat(CrdVerdicts.verdict(mapper.read(array), array).headline())
                .isEqualTo("The service answered with JSON that is not a CDS Hooks response (HTTP 200).");
        // A resourceType that is not shaped like a resource type is not put into the headline.
        var odd = TestExchanges.crd(200, "{\"resourceType\":\"see the attachment\"}");
        assertThat(CrdVerdicts.verdict(mapper.read(odd), odd).headline())
                .isEqualTo("The service answered with JSON that is not a CDS Hooks response (HTTP 200).");
        // A body that is not JSON still could not be read, and says so.
        var garbled = TestExchanges.crd(200, "{\"cards\": [");
        assertThat(CrdVerdicts.verdict(mapper.read(garbled), garbled).headline())
                .isEqualTo("The service answered with a body that could not be read (HTTP 200).");
    }
    private static final String OUTCOME_ERRORS = "{\"resourceType\":\"OperationOutcome\",\"issue\":["
            + "{\"severity\":\"error\",\"code\":\"invalid\",\"diagnostics\":\"zz bad order\"},"
            + "{\"severity\":\"information\",\"code\":\"informational\"}]}";

    @Test
    void anOperationOutcomeWithASuccessStatusIsNotNoDeterminationAndItsIssuesStayIssues() {
        var exchange = TestExchanges.crd(200, OUTCOME_ERRORS);
        CrdReading reading = mapper.read(exchange);
        assertThat(reading.detail().responseKind()).isEqualTo(CrdResponseKind.OPERATION_OUTCOME);
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.MALFORMED_RESPONSE);
        assertThat(reading.detail().issues()).extracting(issue -> issue.code().orNull())
                .containsExactly("invalid", "informational");
        assertThat(Expectations.assess("NO_DETERMINATION", reading.outcome(), reading.asked(), reading.answers(), CrdOutcome.values(),
                CrdCodes.EXPECTATION_ELEMENTS).result()).isEqualTo(TestResult.FAIL);
        assertThat(CrdVerdicts.verdict(reading, exchange).headline()).isEqualTo("The service answered with a FHIR OperationOutcome, "
                + "not a CDS Hooks response (HTTP 200). Its issues are listed in the response detail.");
        // It is not called an answer that carried no coverage information: it was not an answer of that kind.
        List<org.p32h.interop.epa.pave.model.Finding> findings = new CrdConformanceChecker(new org.p32h.interop.epa.pave.testsupport.FakeCodeList(),
                org.p32h.interop.epa.pave.config.EpaProperties.of(java.util.Map.of("mode", "mock"))).check(reading, exchange, null, null);
        assertThat(findings).extracting(org.p32h.interop.epa.pave.model.Finding::title).doesNotContain("No coverage information in the response");
        // With an error status it is the refusal it always was.
        assertThat(mapper.read(TestExchanges.crd(400, OUTCOME_ERRORS)).outcome()).isEqualTo(CrdOutcome.REJECTED);
    }

    @Test
    void theNextStepForAReadableAnswerOfTheWrongKindIsToReportItNotToCheckTheEnvironment() {
        for (String body : List.of(PATIENT, OUTCOME_ERRORS)) {
            var exchange = TestExchanges.crd(200, body);
            var next = CrdVerdicts.nextStep(mapper.read(exchange), exchange, "run-1");
            assertThat(next.action()).isEqualTo(org.p32h.interop.epa.pave.model.NextAction.REPORT_NO_DETERMINATION);
            assertThat(next.headline()).isEqualTo("Next: record run run-1 as \"no determination returned\" and report it to the people "
                    + "who look after the service.");
            assertThat(next.reason()).startsWith("The service was reached and answered, but not with a CDS Hooks response");
        }
        // A body that could not be read is still a finding about the environment.
        var garbled = TestExchanges.crd(200, "{\"cards\": [");
        assertThat(CrdVerdicts.nextStep(mapper.read(garbled), garbled, "run-1").action())
                .isEqualTo(org.p32h.interop.epa.pave.model.NextAction.CHECK_ENVIRONMENT);
    }
}
