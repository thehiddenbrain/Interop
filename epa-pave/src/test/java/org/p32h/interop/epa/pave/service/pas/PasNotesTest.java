package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The notes of a preview say what would be sent: nothing was. */
class PasNotesTest {

    @Test
    void aPreviewSaysWhatWouldBeSentAndARunKeepsThePastTense() {
        List<String> run = List.of("Attachment 1 was sent with no document type.", "No heartbeat period was sent: the variable x is not set.",
                "The relationship was not sent.", "HCPCS Level II codes were sent with the system y.", PasNotes.summary(List.of("PASClaim item.category")));
        assertThat(PasNotes.asPreview(run)).containsExactly("Attachment 1 would be sent with no document type.",
                "No heartbeat period would be sent: the variable x is not set.", "The relationship would not be sent.",
                "HCPCS Level II codes would be sent with the system y.", "This request would go out without 1 element PAS 2.0.1 requires, because "
                        + "nobody supplied it: PASClaim item.category. The service may refuse the request for that alone.");
        // Words that only contain the tense are left alone.
        assertThat(PasNotes.asPreview(List.of("The resent claim was sentenced."))).containsExactly("The resent claim was sentenced.");
    }
}
