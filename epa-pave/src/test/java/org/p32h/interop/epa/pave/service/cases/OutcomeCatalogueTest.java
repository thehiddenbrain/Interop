package org.p32h.interop.epa.pave.service.cases;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;

class OutcomeCatalogueTest {

    private final OutcomeCatalogue catalogue = new OutcomeCatalogue().register(Protocol.CRD, CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS);

    @Test
    void aLedgerCodeIsTurnedBackIntoThePlainWordsOfItsOwnProtocol() {
        assertThat(catalogue.label(Protocol.CRD, "NO_AUTH_REQUIRED")).isEqualTo("Prior authorization is not required");
        assertThat(catalogue.tone(Protocol.CRD, "NO_AUTH_REQUIRED")).isEqualTo(Tone.CLEAR);
        assertThat(catalogue.find(Protocol.CRD, "NO_DETERMINATION")).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(catalogue.knows(Protocol.CRD)).isTrue();
        assertThat(catalogue.knows(Protocol.PAS)).isFalse();
        assertThat(catalogue.knows(null)).isFalse();
    }

    @Test
    void theOutcomesThatAreAboutThisToolBelongToEveryProtocol() {
        for (Protocol protocol : new Protocol[] {Protocol.CRD, Protocol.PAS, null}) {
            assertThat(catalogue.find(protocol, "BLOCKED")).isEqualTo(RunOutcome.BLOCKED);
            assertThat(catalogue.label(protocol, "TOOL_ERROR")).isEqualTo(RunOutcome.TOOL_ERROR.label());
            assertThat(catalogue.tone(protocol, "BLOCKED")).isEqualTo(Tone.BLOCKED);
        }
    }

    @Test
    void aCodeThisToolDoesNotKnowIsShownAsWrittenAndNeverGuessed() {
        // A line written by a later version, or a protocol whose outcomes are not registered.
        assertThat(catalogue.label(Protocol.CRD, "SOMETHING_NEW")).isEqualTo("SOMETHING_NEW");
        assertThat(catalogue.label(Protocol.PAS, "NO_AUTH_REQUIRED")).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(catalogue.tone(Protocol.PAS, "NO_AUTH_REQUIRED")).isEqualTo(Tone.NEUTRAL);
        assertThat(catalogue.find(Protocol.PAS, "NO_AUTH_REQUIRED")).isNull();
        assertThat(catalogue.label(Protocol.CRD, null)).isNull();
        assertThat(catalogue.label(Protocol.CRD, " ")).isNull();
        assertThat(catalogue.tone(null, null)).isEqualTo(Tone.NEUTRAL);
    }

    @Test
    void aDeclaredExpectationIsCheckedAgainstTheProtocolItIsFor() {
        assertThat(catalogue.expectationProblems(Protocol.CRD, "NO_AUTH_REQUIRED; pa-needed=no-auth")).isEmpty();
        assertThat(catalogue.expectationProblems(Protocol.CRD, "APPROVED")).singleElement().asString()
                .startsWith("\"APPROVED\" is not an outcome this operation can produce.");
        assertThat(catalogue.expectationProblems(Protocol.CRD, "pa-neded=no-auth")).singleElement().asString()
                .startsWith("\"pa-neded\" is not an element this operation returns.");
        assertThat(catalogue.expectationProblems(Protocol.CRD, " ")).isEmpty();
        // Not registered: the service that runs the case decides, so nothing is refused here on a guess.
        assertThat(catalogue.expectationProblems(Protocol.PAS, "APPROVED")).isEmpty();
        assertThat(catalogue.expectationProblems(null, "APPROVED")).isEmpty();
    }
}
