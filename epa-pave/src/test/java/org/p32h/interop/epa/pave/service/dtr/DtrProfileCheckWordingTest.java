package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.Finding;

/** The one finding for what a DTR request left out says sent only where an answer arrived. */
class DtrProfileCheckWordingTest {

    private static final List<DtrProfileCheck.Gap> GAPS = List.of(new DtrProfileCheck.Gap("Parameters.coverage", "No coverage was given."));

    @Test
    void aRequestWithNoAnswerWasBuiltWithoutWhatItLeftOutAndNeverSentWithoutIt() {
        Finding answered = DtrProfileCheck.finding(GAPS, true, true);
        Finding nothingArrived = DtrProfileCheck.finding(GAPS, true, false);
        assertThat(answered.detail()).startsWith("This tool sent the request without Parameters.coverage.");
        assertThat(nothingArrived.detail()).startsWith("This tool built the request without Parameters.coverage.").doesNotContain("sent the request");
        assertThat(nothingArrived.title()).isEqualTo("The request left out 1 element DTR 2.0.1 requires");
        // The two-argument form is the one for a request that was answered.
        assertThat(DtrProfileCheck.finding(GAPS, true)).isEqualTo(answered);
    }
}
