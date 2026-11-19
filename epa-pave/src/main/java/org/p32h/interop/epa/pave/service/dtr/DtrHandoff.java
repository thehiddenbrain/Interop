package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Texts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one DTR step hands the step after it, so a tester retypes nothing and the tool keeps no
 * session between steps.
 *
 * <p>The rule: a step hands on everything it was handed, and replaces what it establishes itself,
 * which is the questionnaire, the procedure codes, the run later steps are linked to and, once the
 * response is complete, the QuestionnaireResponse. What CRD said about the coverage assertion, the
 * order and the delegated vendor therefore still reaches PAS after any number of DTR steps.
 *
 * @param followsRunId   the run a later step is linked to. Every step of answering one
 *                       questionnaire, and the submission that carries the answers, is linked to the
 *                       package fetch the questionnaire came from. A step that was handed no run
 *                       hands on its own, so a later step is still linked to something that exists
 * @param procedureCodes the procedure codes the case is about
 * @param questionnaires the canonical url of each questionnaire the step is about
 * @param given          what this step was handed, exactly as it arrived
 */
public record DtrHandoff(String followsRunId, List<String> procedureCodes, List<String> questionnaires, Map<String, List<String>> given) {

    /**
     * The keys a DTR step passes on unchanged. They are the ones CRD writes and a DTR step has
     * nothing to add to. A key that is not one of the CARRY constants is never passed on.
     */
    private static final List<String> PASSED_ON = List.of(NextStepView.CARRY_COVERAGE_ASSERTION_ID, NextStepView.CARRY_ORDER,
            NextStepView.CARRY_DELEGATED_VENDOR, NextStepView.CARRY_DTR_LINK_URL, NextStepView.CARRY_DTR_APP_CONTEXT);

    public DtrHandoff {
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        questionnaires = questionnaires == null ? List.of() : List.copyOf(questionnaires);
        given = given == null ? Map.of() : given;
    }

    /**
     * What the next step starts from.
     *
     * @param completedResponse the completed QuestionnaireResponse as compact JSON text, or null
     *                          while the response is not complete. An unfinished response is never
     *                          handed on under this key, because the next step that reads it is the
     *                          prior authorization request
     */
    public Map<String, List<String>> carry(String completedResponse) {
        Map<String, List<String>> carry = new LinkedHashMap<>();
        for (String key : PASSED_ON) {
            carry.put(key, values(given, key));
        }
        carry.put(NextStepView.CARRY_PROCEDURE_CODES, procedureCodes);
        carry.put(NextStepView.CARRY_QUESTIONNAIRE, questionnaires);
        carry.put(NextStepView.CARRY_RUN_ID, Texts.present(followsRunId) ? List.of(followsRunId) : List.of());
        carry.put(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, Texts.present(completedResponse) ? List.of(completedResponse) : List.of());
        return carry;
    }

    /** The values handed on under a key, without blanks and without repeats. Empty when there are none. */
    public static List<String> values(Map<String, List<String>> carry, String key) {
        List<String> out = new ArrayList<>();
        List<String> values = carry == null ? null : carry.get(key);
        if (values != null) {
            for (String value : values) {
                if (Texts.present(value) && !out.contains(value.strip())) {
                    out.add(value.strip());
                }
            }
        }
        return out;
    }

    /** The first value handed on under a key, or null. */
    public static String first(Map<String, List<String>> carry, String key) {
        List<String> values = values(carry, key);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Prints the names of what is handed on and never the values: a completed response holds the tester's answers. */
    @Override
    public String toString() {
        return "DtrHandoff[given=" + given.keySet() + "]";
    }
}
