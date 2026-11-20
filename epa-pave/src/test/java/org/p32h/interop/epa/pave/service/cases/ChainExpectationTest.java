package org.p32h.interop.epa.pave.service.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;
import org.p32h.interop.epa.pave.service.pas.PasCodes;

class ChainExpectationTest {

    private final OutcomeCatalogue outcomes = new OutcomeCatalogue()
            .register(Protocol.CRD, CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS)
            .register(Protocol.PAS, PasOutcome.values(), PasCodes.EXPECTATION_ELEMENTS);

    @Test
    void eachPartOfAChainsExpectationGoesToTheStepItNames() {
        ChainExpectation expectation = ChainExpectation.parse(" CRD: AUTH_REQUIRED ; crd: pa-needed=auth-needed\npas : APPROVED", outcomes);
        assertThat(expectation.problems()).isEmpty();
        // Each step is handed its own part, written as its own service reads an expectation.
        assertThat(expectation.byStep()).containsExactly(Map.entry(Protocol.CRD, "AUTH_REQUIRED; pa-needed=auth-needed"),
                Map.entry(Protocol.PAS, "APPROVED"));
        assertThat(expectation.of(Protocol.CRD)).isEqualTo("AUTH_REQUIRED; pa-needed=auth-needed");
        assertThat(expectation.of(Protocol.DTR)).isNull();
    }

    @Test
    void aCodeAssertionAboutOneProcedureCodeKeepsItsOwnColon() {
        ChainExpectation expectation = ChainExpectation.parse("crd: 95810: pa-needed=auth-needed", outcomes);
        assertThat(expectation.problems()).isEmpty();
        assertThat(expectation.of(Protocol.CRD)).isEqualTo("95810: pa-needed=auth-needed");
    }

    @Test
    void nothingExpectedIsNothingExpectedAndNotAProblem() {
        for (String blank : new String[] {null, "", "  "}) {
            ChainExpectation expectation = ChainExpectation.parse(blank, outcomes);
            assertThat(expectation.byStep()).isEmpty();
            assertThat(expectation.problems()).isEmpty();
        }
        // Separators alone read as an expectation and compare nothing, so they are refused before anything is sent.
        assertThat(ChainExpectation.parse(" ; \n ;", outcomes).problems()).containsExactly(OutcomeCatalogue.NO_TERMS);
    }

    @Test
    void aPartThatNamesNoStepIsRefusedAndNeverGivenToWhicheverStepRanLast() {
        ChainExpectation expectation = ChainExpectation.parse("NO_AUTH_REQUIRED; pas: APPROVED; pa-needed=no-auth; xyz: APPROVED; crd:", outcomes);
        assertThat(expectation.problems()).hasSize(4);
        assertThat(expectation.problems().get(0)).isEqualTo("\"NO_AUTH_REQUIRED\" does not say which step of the chain it is about. "
                + "Start it with crd:, dtr: or pas:, for example crd: AUTH_REQUIRED; pas: APPROVED.");
        assertThat(expectation.problems().get(1)).startsWith("\"pa-needed=no-auth\" does not say which step");
        assertThat(expectation.problems().get(2)).startsWith("\"xyz: APPROVED\" does not say which step");
        // It names its step and lacks the outcome, which is what it is told.
        assertThat(expectation.problems().get(3)).isEqualTo("\"crd:\" names the CRD step and no outcome. Write the outcome after the colon, "
                + "for example crd: AUTH_REQUIRED.");
        // The one part that did name its step is still read.
        assertThat(expectation.of(Protocol.PAS)).isEqualTo("APPROVED");
    }

    @Test
    void eachStepsPartIsCheckedAgainstThatStepsOwnOutcomes() {
        // APPROVED is a PAS outcome. CRD has no such outcome, and saying so before sending saves a wasted run.
        ChainExpectation crossed = ChainExpectation.parse("crd: APPROVED; pas: NO_AUTH_REQUIRED", outcomes);
        assertThat(crossed.problems()).hasSize(2);
        assertThat(crossed.problems().get(0)).startsWith("For the CRD step: \"APPROVED\" is not an outcome this operation can produce.");
        assertThat(crossed.problems().get(1)).startsWith("For the PAS step: \"NO_AUTH_REQUIRED\" is not an outcome this operation can produce.");
        // A step whose outcomes this catalogue does not hold is left to the service that runs it.
        assertThat(ChainExpectation.parse("dtr: ANYTHING", outcomes).problems()).isEmpty();
    }
}
