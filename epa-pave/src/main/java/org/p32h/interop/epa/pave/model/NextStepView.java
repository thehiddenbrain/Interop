package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the tester should do next, stated by the tool so nobody has to infer it.
 *
 * <p>{@code carry} is how one step of the journey hands the next what it starts from, so a tester
 * does not retype anything. Its keys are the CARRY constants below and nothing else, because the
 * step that writes a value and the step that reads it are built separately. A value is a list,
 * since a response can name several questionnaires.
 *
 * @param action   the step the screen can offer
 * @param headline the instruction in plain language
 * @param reason   why this is the next step, tied to what the response said
 * @param carry    values the next step starts from, by CARRY key
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NextStepView(NextAction action, String headline, String reason, Map<String, List<String>> carry) {

    /** The canonical url of each questionnaire the payer named, in the order returned. */
    public static final String CARRY_QUESTIONNAIRE = "questionnaire";
    /** The coverage assertion id of the answer that asked for documentation. */
    public static final String CARRY_COVERAGE_ASSERTION_ID = "coverageAssertionId";
    /** The order the answer applies to, for example ServiceRequest/service-request-1. */
    public static final String CARRY_ORDER = "order";
    /** The url of a SMART link a CRD card carried for DTR. */
    public static final String CARRY_DTR_LINK_URL = "dtrLinkUrl";
    /** The appContext of that link. */
    public static final String CARRY_DTR_APP_CONTEXT = "dtrAppContext";
    /** The delegated vendor a response named. */
    public static final String CARRY_DELEGATED_VENDOR = "delegatedVendor";
    /** The procedure codes the step was about. */
    public static final String CARRY_PROCEDURE_CODES = "procedureCodes";
    /** A completed QuestionnaireResponse as JSON text, for the prior authorization request to include. */
    public static final String CARRY_QUESTIONNAIRE_RESPONSE = "questionnaireResponse";
    /** The run this step follows from. */
    public static final String CARRY_RUN_ID = "runId";
    /** The identifier the tool put on a submitted claim, for asking about its status. */
    public static final String CARRY_CLAIM_ID = "claimId";
    /** The id of a subscription a step created or read. */
    public static final String CARRY_SUBSCRIPTION_ID = "subscriptionId";

    public NextStepView {
        if (action == null || Texts.blank(headline)) {
            throw new IllegalArgumentException("A next step needs an action and an instruction.");
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (carry != null) {
            carry.forEach((key, values) -> {
                if (values != null && !values.isEmpty()) {
                    copy.put(key, List.copyOf(values));
                }
            });
        }
        carry = java.util.Collections.unmodifiableMap(copy);
    }

    public static NextStepView of(NextAction action, String headline, String reason) {
        return new NextStepView(action, headline, reason, Map.of());
    }

    /** The first value carried under a key, or null. */
    public String first(String key) {
        List<String> values = carry.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** Prints the action and the names of what is carried, never the values: they can be an authorization number or a tester's answers. */
    @Override
    public String toString() {
        return "NextStepView[" + action + ", carry=" + carry.keySet() + "]";
    }
}
