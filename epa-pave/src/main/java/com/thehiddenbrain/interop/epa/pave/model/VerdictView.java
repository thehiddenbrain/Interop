package com.thehiddenbrain.interop.epa.pave.model;

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
 */
public record VerdictView(String headline, String outcomeCode, String outcomeLabel, Tone tone, List<String> supporting) {

    public VerdictView {
        supporting = supporting == null ? List.of() : List.copyOf(supporting);
    }

    public static VerdictView of(OutcomeCode outcome, String headline, List<String> supporting) {
        return new VerdictView(headline, outcome.name(), outcome.label(), outcome.tone(), supporting);
    }
}
