package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.web.CallWording.Call;

/** A refusal of what was submitted is worded for the call that was made: only a send speaks of sending. */
class CallWordingTest {

    private static ErrorView refusal(int fields) {
        List<FieldError> errors = fields == 1 ? List.of(new FieldError("provider.npi", "Not an NPI."))
                : List.of(new FieldError("provider.npi", "Not an NPI."), new FieldError("procedureCodes", "Choose a code."));
        return ErrorView.validation(new ValidationException(errors).getMessage(), errors);
    }

    @Test
    void eachEndpointIsReadForWhatItDoes() {
        assertThat(CallWording.of("POST", "/api/crd")).isEqualTo(Call.SEND);
        assertThat(CallWording.of("POST", "/api/pas/submit")).isEqualTo(Call.SEND);
        assertThat(CallWording.of("DELETE", "/api/pas/subscriptions/SUB-1")).isEqualTo(Call.SEND);
        assertThat(CallWording.of("POST", "/api/raw")).isEqualTo(Call.SEND);
        assertThat(CallWording.of("POST", "/api/runs/replay")).isEqualTo(Call.SEND);
        assertThat(CallWording.of("POST", "/api/suites")).isEqualTo(Call.SEND);
        assertThat(CallWording.of("POST", "/api/crd/preview")).isEqualTo(Call.PREVIEW);
        assertThat(CallWording.of("POST", "/api/raw/preview")).isEqualTo(Call.PREVIEW);
        assertThat(CallWording.of("POST", "/api/config/resolve")).isEqualTo(Call.PREVIEW);
        assertThat(CallWording.of("POST", "/api/suites/preview")).isEqualTo(Call.SUITE_PREVIEW);
        assertThat(CallWording.of("POST", "/api/crd/compare")).isEqualTo(Call.COMPARE);
        assertThat(CallWording.of("POST", "/api/config/writes")).isEqualTo(Call.UNLOCK);
        assertThat(CallWording.of("POST", "/api/config/environments")).isEqualTo(Call.UPLOAD);
        assertThat(CallWording.of("POST", "/api/config/collections")).isEqualTo(Call.UPLOAD);
        assertThat(CallWording.of("PUT", "/api/config/collections/c-1/mapping")).isEqualTo(Call.CHANGE);
        assertThat(CallWording.of("GET", "/api/members/S123456789")).isEqualTo(Call.LOOKUP);
        assertThat(CallWording.of("GET", "/api/providers")).isEqualTo(Call.LOOKUP);
        assertThat(CallWording.of("GET", "/api/runs")).isEqualTo(Call.OTHER);
    }

    @Test
    void aCountAgreesWithItsNoun() {
        assertThat(refusal(1).title()).isEqualTo("The form has 1 problem. Nothing was sent.");
        assertThat(refusal(2).title()).isEqualTo("The form has 2 problems. Nothing was sent.");
        assertThat(refusal(2).title()).doesNotContain("(s)");
    }

    @Test
    void onlyASendSaysNothingWasSentOrSendAgain() {
        ErrorView send = CallWording.word(refusal(2), Call.SEND);
        assertThat(send.title()).isEqualTo("The form has 2 problems. Nothing was sent.");
        assertThat(send.action()).isEqualTo("Correct the marked fields and send again.");

        ErrorView compare = CallWording.word(refusal(2), Call.COMPARE);
        assertThat(compare.title()).isEqualTo("The form has 2 problems. Nothing was compared.");
        assertThat(compare.action()).isEqualTo("Correct the marked fields and compare again.");

        ErrorView preview = CallWording.word(refusal(1), Call.PREVIEW);
        assertThat(preview.title()).isEqualTo("The form has 1 problem. The request is not shown.");
        assertThat(preview.action()).isEqualTo("Correct the marked field and show the request again.");

        ErrorView unlock = CallWording.word(refusal(1), Call.UNLOCK);
        assertThat(unlock.title()).isEqualTo("The form has 1 problem. Nothing was changed.");
        assertThat(unlock.action()).isEqualTo("Correct the marked field and try again.");

        for (Call call : List.of(Call.PREVIEW, Call.SUITE_PREVIEW, Call.COMPARE, Call.UNLOCK, Call.UPLOAD, Call.LOOKUP, Call.CHANGE)) {
            ErrorView worded = CallWording.word(refusal(2), call);
            assertThat(worded.title()).as(call.name()).doesNotContain("Nothing was sent");
            assertThat(worded.action()).as(call.name()).doesNotContain("send again");
            assertThat(worded.fieldErrors()).as(call.name()).hasSize(2);
        }
    }

    @Test
    void aStepThatSendsAgainIsReworded() {
        ErrorView configuration = new ErrorView(ErrorKind.CONFIGURATION, "CRD order-sign cannot be sent: the url part {id} has no value. "
                + "Nothing was sent.", "Give the identifier the url is waiting for, then send again.", null, List.of(), List.of(), null);
        ErrorView preview = CallWording.word(configuration, Call.PREVIEW);
        assertThat(preview.title()).isEqualTo("CRD order-sign cannot be sent: the url part {id} has no value. The request is not shown.");
        assertThat(preview.action()).isEqualTo("Give the identifier the url is waiting for, then show the request again.");
    }

    @Test
    void aRefusalThatSaysNothingOfASendAndAnEndpointOfUnknownPurposeKeepTheirWords() {
        ErrorView upload = ErrorView.of(ErrorKind.VALIDATION, "This file is a Postman collection, not an environment.",
                "Upload the environment export.");
        assertThat(CallWording.word(upload, Call.UPLOAD)).isEqualTo(upload);
        assertThat(CallWording.word(refusal(2), Call.OTHER)).isEqualTo(refusal(2));
    }
}
