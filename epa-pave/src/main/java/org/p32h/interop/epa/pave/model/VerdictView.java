package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The plain language answer at the top of a result. This is the authorization outcome: what the
 * service said. It is a different fact from the test result in {@link ExpectationView}.
 *
 * @param headline     one sentence a business tester can act on
 * @param outcomeCode  the outcome code written to the ledger
 * @param outcomeLabel the outcome in plain language
 * @param tone         what the outcome means, which decides its colour
 * @param supporting   further plain sentences, such as the delegated vendor or the documentation wanted
 * @param maskedReading the line that says this verdict was read from a mask and not from what the
 *                     service sent ({@link MaskedReading#line()}), drawn under the headline. Left out
 *                     of the JSON when no mask fell on a value the verdict is read by, so every
 *                     other verdict reads as it did
 */
public record VerdictView(String headline, String outcomeCode, String outcomeLabel, Tone tone, List<String> supporting,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String maskedReading) {

    public VerdictView {
        if (Texts.blank(headline) || Texts.blank(outcomeCode) || Texts.blank(outcomeLabel) || tone == null) {
            throw new IllegalArgumentException("A verdict needs a headline, an outcome code, an outcome label and a tone.");
        }
        supporting = supporting == null ? List.of() : List.copyOf(supporting);
        maskedReading = Texts.present(maskedReading) ? maskedReading : null;
    }

    public VerdictView(String headline, String outcomeCode, String outcomeLabel, Tone tone, List<String> supporting) {
        this(headline, outcomeCode, outcomeLabel, tone, supporting, null);
    }

    /** The same verdict, its headline and the rules behind it unchanged, with the line about a masked reading in it. */
    public VerdictView withMaskedReading(String line) {
        return new VerdictView(headline, outcomeCode, outcomeLabel, tone, supporting, line);
    }

    public static VerdictView of(OutcomeCode outcome, String headline, List<String> supporting) {
        return new VerdictView(headline, outcome.name(), outcome.label(), outcome.tone(), supporting);
    }

    /** Prints the outcome only. A supporting line can hold an authorization number. */
    @Override
    public String toString() {
        return "VerdictView[" + outcomeCode + ", " + tone + "]";
    }
}
