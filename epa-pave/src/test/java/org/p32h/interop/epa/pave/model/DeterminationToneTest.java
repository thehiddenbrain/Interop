package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;
import org.p32h.interop.epa.pave.service.pas.PasCodes;
import tools.jackson.databind.JsonNode;

/**
 * The tone of a determination row's code (plan 7.5): derived from the element and the code on every
 * constructor, absent for a code with no tone or one this tool does not know, and only ever for a
 * code the services' own code lists recognise.
 */
class DeterminationToneTest {

    private static Tone tone(String system, String code) {
        return new DeterminationRow(null, "Row", code, null, system, true, Presence.VALUE).tone();
    }

    @Test
    void crdCodesTakeTheTonesThePlanGives() {
        assertThat(tone(CrdCodes.COVERED, "covered")).isEqualTo(Tone.CLEAR);
        assertThat(tone(CrdCodes.COVERED, "not-covered")).isEqualTo(Tone.STOP);
        assertThat(tone(CrdCodes.COVERED, "conditional")).isEqualTo(Tone.ACTION);
        assertThat(tone(CrdCodes.PA_NEEDED, "auth-needed")).isEqualTo(Tone.ACTION);
        assertThat(tone(CrdCodes.PA_NEEDED, "no-auth")).isEqualTo(Tone.CLEAR);
        assertThat(tone(CrdCodes.PA_NEEDED, "satisfied")).isEqualTo(Tone.CLEAR);
        assertThat(tone(CrdCodes.PA_NEEDED, "performpa")).isEqualTo(Tone.ACTION);
        assertThat(tone(CrdCodes.PA_NEEDED, "conditional")).isEqualTo(Tone.ACTION);
        assertThat(tone(CrdCodes.DOC_NEEDED, "clinical")).isEqualTo(Tone.ACTION);
        assertThat(tone(CrdCodes.DOC_NEEDED, "admin")).isEqualTo(Tone.ACTION);
        assertThat(tone(CrdCodes.DOC_NEEDED, "both")).isEqualTo(Tone.ACTION);
        assertThat(tone(CrdCodes.DOC_NEEDED, "conditional")).isEqualTo(Tone.ACTION);
        assertThat(tone(CrdCodes.DOC_NEEDED, CrdCodes.NO_DOC)).isEqualTo(Tone.CLEAR);
    }

    @Test
    void elementsThatSayWhatAndNotWhetherCarryNoTone() {
        assertThat(tone(CrdCodes.DOC_PURPOSE, "withpa")).isNull();
        assertThat(tone(CrdCodes.INFO_NEEDED, "performer")).isNull();
        assertThat(tone(PasCodes.CLAIM_OUTCOME, "complete")).isNull();
    }

    @Test
    void pasReviewActionsTakeTheTonesThePlanGives() {
        assertThat(tone(PasCodes.REVIEW_ACTION, PasCodes.APPROVED)).isEqualTo(Tone.CLEAR);
        assertThat(tone(PasCodes.REVIEW_ACTION, PasCodes.PARTIAL)).isEqualTo(Tone.ACTION);
        assertThat(tone(PasCodes.REVIEW_ACTION, PasCodes.DENIED)).isEqualTo(Tone.STOP);
        assertThat(tone(PasCodes.REVIEW_ACTION, PasCodes.PENDED)).isEqualTo(Tone.PENDING);
        assertThat(tone(PasCodes.REVIEW_ACTION, PasCodes.MODIFIED)).isEqualTo(Tone.CLEAR);
        assertThat(tone(PasCodes.REVIEW_ACTION, PasCodes.CANCELLED)).isEqualTo(Tone.NEUTRAL);
        assertThat(tone(PasCodes.REVIEW_ACTION, PasCodes.CONTACT_PAYER)).isEqualTo(Tone.ACTION);
        assertThat(tone(PasCodes.REVIEW_ACTION, PasCodes.NO_ACTION)).isEqualTo(Tone.CLEAR);
    }

    @Test
    void anUnknownCodeAnAbsentCodeAndAnotherCaseGetNoTone() {
        assertThat(tone(CrdCodes.PA_NEEDED, "made-up")).isNull();
        assertThat(tone(CrdCodes.PA_NEEDED, "AUTH-NEEDED")).isNull();
        assertThat(tone(PasCodes.REVIEW_ACTION, "A5")).isNull();
        assertThat(DeterminationRow.notReturned(null, "Covered", CrdCodes.COVERED).tone()).isNull();
        assertThat(tone(null, "covered")).isNull();
    }

    @Test
    void theToneIsDerivedWhateverACallerPassesAndEveryConstructorDerivesIt() {
        DeterminationRow forced = new DeterminationRow(null, "Covered", "covered", null, CrdCodes.COVERED, true, Presence.VALUE, null,
                Tone.STOP);
        assertThat(forced.tone()).isEqualTo(Tone.CLEAR);
        DeterminationRow noValue = new DeterminationRow(null, "Covered", "not-covered", null, CrdCodes.COVERED, true, Presence.VALUE, true);
        assertThat(noValue.tone()).isEqualTo(Tone.STOP);
        DeterminationRow made = new DeterminationRow(null, "Covered", "made-up", null, CrdCodes.COVERED, false, Presence.VALUE, null,
                Tone.CLEAR);
        assertThat(made.tone()).isNull();
    }

    @Test
    void theToneIsOnTheWireWhenThereIsOneAndLeftOffOtherwise() {
        JsonNode toned = JsonSafe.mapper().valueToTree(new DeterminationRow(null, "Decision", "A4", null, PasCodes.REVIEW_ACTION, true,
                Presence.VALUE));
        assertThat(toned.get("tone").asString()).isEqualTo("PENDING");
        JsonNode plain = JsonSafe.mapper().valueToTree(DeterminationRow.notReturned(null, "Decision", PasCodes.REVIEW_ACTION));
        assertThat(plain.has("tone")).isFalse();
    }

    @Test
    void everyTonedCodeIsOneTheServicesCodeListsRecognise() {
        for (Map.Entry<String, Map<String, Tone>> element : CodeTones.all().entrySet()) {
            for (String code : element.getValue().keySet()) {
                String name = element.getKey() + " " + code;
                if (element.getKey().equals(PasCodes.REVIEW_ACTION)) {
                    assertThat(PasCodes.codes(PasCodes.REVIEW_ACTION)).as(name).contains(code);
                } else if (code.equals(CrdCodes.NO_DOC)) {
                    // no-doc is outside every CRD value set; the services read it as "no documentation is needed".
                    assertThat(CrdCodes.display(element.getKey(), code)).as(name).startsWith("No documentation is needed");
                } else {
                    assertThat(CrdCodes.display(element.getKey(), code)).as(name).isNotNull();
                }
            }
        }
    }
}
