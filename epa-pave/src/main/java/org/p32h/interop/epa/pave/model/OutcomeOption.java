package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

/**
 * One outcome an operation can produce, as the browser needs it: the code a tester declares as an
 * expectation and the ledger stores, with its plain language label and its tone.
 *
 * <p>The outcome enums live in Java. Served like this, the expectation picker and the history list
 * read them from the server and never keep a copy that can fall out of date.
 *
 * @param shortLabel the outcome in a few words, served as {@code short}: a Java name cannot be
 *                   {@code short}, and the screens read the word the plan gives them
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutcomeOption(String code, String label, @JsonProperty("short") String shortLabel, Tone tone, boolean fault) {

    /** An option without its short words, as the callers written before short words existed build one. */
    public OutcomeOption(String code, String label, Tone tone, boolean fault) {
        this(code, label, null, tone, fault);
    }

    public static OutcomeOption of(OutcomeCode outcome) {
        return new OutcomeOption(outcome.name(), outcome.label(), outcome.shortLabel(), outcome.tone(), outcome.fault());
    }

    /** The options for one or more vocabularies, in the order given. */
    public static List<OutcomeOption> listOf(OutcomeCode[]... vocabularies) {
        List<OutcomeOption> out = new ArrayList<>();
        for (OutcomeCode[] vocabulary : vocabularies) {
            for (OutcomeCode outcome : vocabulary) {
                out.add(of(outcome));
            }
        }
        return out;
    }
}
