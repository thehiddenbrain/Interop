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
}
