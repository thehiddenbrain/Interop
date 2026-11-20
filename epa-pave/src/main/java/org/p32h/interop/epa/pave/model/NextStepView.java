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
 * @param carryLabels for every key of {@code carry}, the words a business tester reads for it, from
 *                 {@link #CARRY_LABELS}. Null when nothing is carried
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NextStepView(NextAction action, String headline, String reason, Map<String, List<String>> carry,
                           Map<String, String> carryLabels) {

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
    /**
     * What CRD said the documentation is for, as the codes of its doc-purpose answer. DTR states
     * the intended use of a questionnaire response from it.
     */
    public static final String CARRY_DOC_PURPOSE = "docPurpose";
    /**
     * A reference to a partly completed QuestionnaireResponse that a CRD 2.0.1 coverage information
     * answer named for DTR to start from (its response sub-extension). It is the reference, never the
     * response itself, so it stays apart from {@link #CARRY_QUESTIONNAIRE_RESPONSE}, which holds a
     * completed response as JSON for PAS to submit. A reference is an address and can hold an
     * identifier, so it is shown on a result and never written to the ledger or a log.
     */
    public static final String CARRY_QUESTIONNAIRE_RESPONSE_REF = "questionnaireResponseRef";
    /** A completed QuestionnaireResponse as JSON text, for the prior authorization request to include. */
    public static final String CARRY_QUESTIONNAIRE_RESPONSE = "questionnaireResponse";
    /** The run this step follows from. */
    public static final String CARRY_RUN_ID = "runId";
    /** The identifier the tool put on a submitted claim, for asking about its status. */
    public static final String CARRY_CLAIM_ID = "claimId";
    /** The id of a subscription a step created or read. */
    public static final String CARRY_SUBSCRIPTION_ID = "subscriptionId";

    /**
     * The words for each carry key, for the three protocols in one place, so that the step that
     * hands a value on and every screen that shows it name it alike.
     */
    public static final Map<String, String> CARRY_LABELS = Map.ofEntries(
            Map.entry(CARRY_QUESTIONNAIRE, "Questionnaires the payer named"),
            Map.entry(CARRY_COVERAGE_ASSERTION_ID, "Coverage assertion id"),
            Map.entry(CARRY_ORDER, "The order the answer applies to"),
            Map.entry(CARRY_DTR_LINK_URL, "Address of the DTR app the payer's card links to"),
            Map.entry(CARRY_DTR_APP_CONTEXT, "What the payer's card hands to the DTR app"),
            Map.entry(CARRY_DELEGATED_VENDOR, "Vendor the payer delegates to"),
            Map.entry(CARRY_PROCEDURE_CODES, "Procedure codes"),
            Map.entry(CARRY_DOC_PURPOSE, "What CRD said the documentation is for"),
            Map.entry(CARRY_QUESTIONNAIRE_RESPONSE_REF, "Partly completed questionnaire response CRD named"),
            Map.entry(CARRY_QUESTIONNAIRE_RESPONSE, "Completed questionnaire responses"),
            Map.entry(CARRY_RUN_ID, "The run this step follows from"),
            Map.entry(CARRY_CLAIM_ID, "Claim identifier"),
            Map.entry(CARRY_SUBSCRIPTION_ID, "Subscription id"));

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
        Map<String, String> labels = new LinkedHashMap<>();
        // A key with no words of its own is named by the key, never left out: every value carried is labelled.
        copy.keySet().forEach(key -> labels.put(key, CARRY_LABELS.getOrDefault(key, key)));
        carryLabels = labels.isEmpty() ? null : java.util.Collections.unmodifiableMap(labels);
    }

    public NextStepView(NextAction action, String headline, String reason, Map<String, List<String>> carry) {
        this(action, headline, reason, carry, null);
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
